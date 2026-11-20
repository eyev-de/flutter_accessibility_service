package slayer.accessibility.service.flutter_accessibility_service;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.Locale;

/**
 * Measurement for the snap-to-item node fetch (APPS-112), log tag "SnapNodes".
 *
 * - Walk stats (both the compact snapshot walk and the legacy map walk) are recorded from the node-query executor,
 *   the main-thread encode and post delay from the main thread: guarded by {@code LOCK}.
 * - Event counters are incremented in onAccessibilityEvent (main thread) and read/reset by the summary tick (main
 *   thread): plain fields, main thread only.
 * - Frame gap probe: a Choreographer loop on the main thread that runs while walks are requested (stops 3s after the
 *   last request) and records the time between consecutive frame callbacks. On this Flutter version the main thread
 *   is also every engine's Dart UI thread, so a long gap is pointer lag.
 * - Node-change pushes (main thread) and event delivery delay / content change types (onAccessibilityEvent, main
 *   thread): plain fields, main thread only. Node refreshes are recorded from the node-refresh executor: LOCK.
 * - One summary line per 5s window, only for windows in which at least one walk ran, a node refresh was answered or
 *   a node-change push was sent.
 *
 * Debug builds only: everything above (collection, summary tick, frame gap probe, slow-walk line) runs only when the
 * HOST app is debuggable (ApplicationInfo.FLAG_DEBUGGABLE, resolved once by {@link #configure}; off until known). In a
 * release build every recorder returns at once. The one exception is {@link #noteSnapshotBudget}: a rare, rate-limited
 * Log.w (at most once per 60s) when snapshot walks keep getting truncated by the time budgets.
 *
 * Never logs coordinates, text or other node content - counts and durations only.
 */
final class SnapNodeStats {
    static final String TAG = "SnapNodes";

    private static final long SUMMARY_INTERVAL_MS = 5000;
    private static final long PROBE_IDLE_STOP_NANOS = 3_000_000_000L;
    private static final long SLOW_WALK_LOG_INTERVAL_MS = 1000;
    static final int SLOW_WALK_MS = 250;
    private static final int GAP_CAPACITY = 600;
    private static final double GAP_JANK_MS = 32.0;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    // Consecutive budget-truncated snapshot walks before the release Log.w, and its minimum interval.
    static final int BUDGET_TRUNCATION_WARN_STREAK = 5;
    private static final long BUDGET_TRUNCATION_WARN_INTERVAL_MS = 60_000;

    private static volatile boolean enabled;
    private static volatile boolean enabledResolved;

    private SnapNodeStats() {}

    /**
     * Resolves once whether the host app is debuggable, which enables the stats and their log lines. Called when the
     * service connects and when the plugin attaches; later calls are no-ops.
     */
    static void configure(Context context) {
        if (enabledResolved || context == null) return;
        try {
            final ApplicationInfo info = context.getApplicationInfo();
            if (info == null) return;
            enabled = (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            enabledResolved = true;
        } catch (RuntimeException e) {
            // Stays disabled; the next configure call tries again.
        }
    }

    /** Whether stats are collected (debuggable host app). Callers skip their measuring when false. */
    static boolean isEnabled() {
        return enabled;
    }

    // Why a window of a snapshot walk is truncated (index into TRUNCATION_REASONS; NONE = complete).
    static final int TRUNCATED_NONE = 0;
    static final int TRUNCATED_WINDOW_BUDGET = 1;
    static final int TRUNCATED_TOTAL_BUDGET = 2;
    static final int TRUNCATED_CAP = 3;
    static final int TRUNCATED_NO_ROOT = 4;
    static final int TRUNCATED_ERROR = 5;
    static final String[] TRUNCATION_REASONS = {"none", "windowBudget", "totalBudget", "cap", "noRoot", "error"};

    // ---- events (main thread only) ----
    static int eventsStructural;
    static int eventsContent;
    static int eventsTextOnly;
    static int eventsOwn;
    private static int eventDelayCount;
    private static long eventDelayMsSum;
    private static long eventDelayMsMax;
    // Content change types of the counted (non-text-only) content events; one event can set several bits.
    private static int ctUndefined;
    private static int ctSubtree;
    private static int ctPaneAppeared;
    private static int ctPaneDisappeared;
    private static int ctOther;
    private static int pushesSent;

    private static final int CT_KNOWN = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            | AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
            | AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED;

    // ---- node refresh stats (LOCK); index = REFRESH_RESULTS position ----
    private static final String[] REFRESH_RESULTS = {"ok", "missing", "gone", "hidden", "moved", "error"};
    private static final int[] refreshCounts = new int[REFRESH_RESULTS.length];
    private static int refreshCalls;
    private static long refreshMsSum;
    private static int refreshMsMax;

    // ---- walk stats (LOCK) ----
    private static int snapshotWalks;
    private static int legacyWalks;
    private static int unchangedWalks;
    private static int truncatedWalks;
    private static long walkMsSum;
    private static int walkMsMax;
    private static long windowsSum;
    private static long visitedSum;
    private static int visitedMax;
    private static long actionableSum;
    private static int actionableMax;
    private static long prunedSum;
    private static long depthCappedSum;
    private static int mainCount;
    private static double encodeMsSum;
    private static double encodeMsMax;
    private static double postDelayMsSum;
    private static double postDelayMsMax;
    private static long lastSlowWalkLogMs;
    // Snapshot walks only (LOCK).
    private static long snapshotWalkNanosSum;
    private static long snapshotVisitedSum;
    private static long slowFetchSum;
    private static int slowFetchMax;
    private static long windowsTruncatedSum;
    private static final int[] truncatedWindowsByReason = new int[TRUNCATION_REASONS.length];
    private static int lastPrefetchStrategy = Integer.MIN_VALUE;
    // Release anomaly line (LOCK): consecutive snapshot walks truncated by windowBudget/totalBudget.
    private static int budgetTruncationStreak;
    private static boolean budgetTruncationWarned;
    private static long lastBudgetTruncationWarnMs;

    // ---- summary tick + probe (main thread only) ----
    private static boolean tickScheduled;
    private static long windowStartMs;
    private static volatile long lastWalkRequestNanos;
    private static boolean probeRunning;
    private static long lastFrameNanos;
    private static final float[] gaps = new float[GAP_CAPACITY];
    private static int gapCount;
    private static int gapWrite;
    private static int gapsOver32;
    private static float gapMax;

    private static final Runnable startOnMain = SnapNodeStats::startOnMainThread;

    private static final Runnable summaryTick = new Runnable() {
        @Override
        public void run() {
            boolean hadActivity = emitSummary();
            if (hadActivity) {
                windowStartMs = SystemClock.uptimeMillis();
                mainHandler.postDelayed(this, SUMMARY_INTERVAL_MS);
            } else {
                tickScheduled = false;
            }
        }
    };

    private static final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            // Measure when the callback actually ran on main, not the vsync timestamp: a blocked main thread delays
            // the callback, which is exactly what the pointer feels.
            long now = System.nanoTime();
            if (lastFrameNanos != 0) {
                float gapMs = (now - lastFrameNanos) / 1_000_000f;
                gaps[gapWrite] = gapMs;
                gapWrite = (gapWrite + 1) % GAP_CAPACITY;
                if (gapCount < GAP_CAPACITY) gapCount++;
                if (gapMs > GAP_JANK_MS) gapsOver32++;
                if (gapMs > gapMax) gapMax = gapMs;
            }
            lastFrameNanos = now;
            if (now - lastWalkRequestNanos > PROBE_IDLE_STOP_NANOS) {
                probeRunning = false;
                lastFrameNanos = 0;
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** A walk (either API) was requested. Starts the summary tick and the frame gap probe if they are not running. */
    static void noteWalkRequested() {
        if (!enabled) return;
        lastWalkRequestNanos = System.nanoTime();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startOnMainThread();
        } else {
            mainHandler.post(startOnMain);
        }
    }

    private static void startOnMainThread() {
        startTickOnMainThread();
        if (!probeRunning) {
            probeRunning = true;
            lastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private static void startTickOnMainThread() {
        if (!tickScheduled) {
            tickScheduled = true;
            resetWindow();
            windowStartMs = SystemClock.uptimeMillis();
            mainHandler.postDelayed(summaryTick, SUMMARY_INTERVAL_MS);
        }
    }

    private static final Runnable startTickOnMain = SnapNodeStats::startTickOnMainThread;

    /** Delivery delay of one counted window event (now - event time). Main thread, allocation-free. */
    static void noteEventDelay(long delayMs) {
        if (!enabled) return;
        if (delayMs < 0) delayMs = 0;
        eventDelayCount++;
        eventDelayMsSum += delayMs;
        if (delayMs > eventDelayMsMax) eventDelayMsMax = delayMs;
    }

    /** Change type bits of one counted (non-text-only) content event. Main thread, allocation-free. */
    static void noteContentChangeTypes(int changeTypes) {
        if (!enabled) return;
        if (changeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) {
            ctUndefined++;
            return;
        }
        if ((changeTypes & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0) ctSubtree++;
        if ((changeTypes & AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) != 0) ctPaneAppeared++;
        if ((changeTypes & AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) != 0) ctPaneDisappeared++;
        if ((changeTypes & ~CT_KNOWN) != 0) ctOther++;
    }

    /** One node-change push went out to the listening engines. Main thread. Starts the summary tick. */
    static void notePushSent() {
        if (!enabled) return;
        pushesSent++;
        startTickOnMainThread();
    }

    /** One answered refreshInteractiveNode call. Called on the node-refresh executor. Starts the summary tick. */
    static void recordRefresh(String result, int ms) {
        if (!enabled) return;
        synchronized (LOCK) {
            int index = REFRESH_RESULTS.length - 1; // unknown -> error
            for (int i = 0; i < REFRESH_RESULTS.length; i++) {
                if (REFRESH_RESULTS[i].equals(result)) {
                    index = i;
                    break;
                }
            }
            refreshCounts[index]++;
            refreshCalls++;
            refreshMsSum += ms;
            if (ms > refreshMsMax) refreshMsMax = ms;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startTickOnMainThread();
        } else {
            mainHandler.post(startTickOnMain);
        }
    }

    /** Human-readable prefetch strategy for the log lines. */
    static String prefetchName(int strategy) {
        if (strategy == Integer.MIN_VALUE) return "-";
        if (strategy < 0) return "platform";
        if (strategy == (AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST
                | AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE)) return "depth";
        if (strategy == (AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
                | AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE)) return "hybrid";
        return "0x" + Integer.toHexString(strategy);
    }

    /**
     * One finished snapshot walk. Called on the node-query executor. slowFetches = getRoot/getChild calls that took
     * at least 1ms (cache-miss proxy); truncatedByReason = truncated windows per TRUNCATION_REASONS index.
     */
    static void recordSnapshotWalk(long walkNanos, int windows, int visited, int actionable, int pruned, int depthCapped,
            boolean unchanged, boolean truncated, int prefetchStrategy, int slowFetches, int windowsTruncated,
            int[] truncatedByReason) {
        if (!enabled) return;
        final int walkMs = (int) (walkNanos / 1_000_000L);
        synchronized (LOCK) {
            snapshotWalkNanosSum += walkNanos;
            snapshotVisitedSum += visited;
            slowFetchSum += slowFetches;
            if (slowFetches > slowFetchMax) slowFetchMax = slowFetches;
            windowsTruncatedSum += windowsTruncated;
            for (int i = 0; i < truncatedWindowsByReason.length && i < truncatedByReason.length; i++) {
                truncatedWindowsByReason[i] += truncatedByReason[i];
            }
            lastPrefetchStrategy = prefetchStrategy;
            String extra = null;
            if (walkMs >= SLOW_WALK_MS) {
                StringBuilder reasons = new StringBuilder();
                for (int i = 1; i < truncatedByReason.length; i++) {
                    if (truncatedByReason[i] == 0) continue;
                    if (reasons.length() > 0) reasons.append(',');
                    reasons.append(TRUNCATION_REASONS[i]).append(':').append(truncatedByReason[i]);
                }
                extra = String.format(Locale.US, " prefetch=%s slowFetches=%d windowsTruncated=%d reasons=%s",
                        prefetchName(prefetchStrategy), slowFetches, windowsTruncated,
                        reasons.length() > 0 ? reasons : "-");
            }
            recordWalkLocked(false, walkMs, windows, visited, actionable, pruned, depthCapped, unchanged, truncated, extra);
        }
    }

    /** Names of the web role ids 1..16 plus 99 (slot 16), in the order of webRoleSlot. */
    static final String[] WEB_ROLE_NAMES = {"link", "button", "checkBox", "radio", "switch", "toggleButton", "menuItem",
            "tab", "option", "comboBox", "textField", "slider", "treeItem", "disclosure", "heading", "image", "other"};

    /** Slot of a web role id in WEB_ROLE_NAMES, -1 for 0 (none) or an unknown id. */
    static int webRoleSlot(int role) {
        if (role >= 1 && role <= 16) return role - 1;
        if (role == 99) return 16;
        return -1;
    }

    /**
     * One changed snapshot walk that visited web content (debug builds only; counts only - no text, ids or
     * coordinates). Called on the node-query executor.
     */
    static void logWebWalk(int webVisited, int targets, int byClickable, int byRole, int byClass, int offscreenPruned,
            int depthCapped, int[] roleCounts) {
        if (!enabled) return;
        final StringBuilder sb = new StringBuilder(160);
        sb.append("web: nodes=").append(webVisited)
                .append(" targets=").append(targets)
                .append(" byClickable=").append(byClickable)
                .append(" byRole=").append(byRole)
                .append(" byClass=").append(byClass)
                .append(" offscreenPruned=").append(offscreenPruned)
                .append(" depthCapped=").append(depthCapped)
                .append(" roles");
        boolean any = false;
        for (int i = 0; i < roleCounts.length && i < WEB_ROLE_NAMES.length; i++) {
            if (roleCounts[i] == 0) continue;
            sb.append(' ').append(WEB_ROLE_NAMES[i]).append('=').append(roleCounts[i]);
            any = true;
        }
        if (!any) sb.append(" -");
        Log.d(TAG, sb.toString());
    }

    /** One finished walk. Called on the node-query executor. */
    static void recordWalk(boolean legacy, int walkMs, int windows, int visited, int actionable, int pruned,
            int depthCapped, boolean unchanged, boolean truncated) {
        if (!enabled) return;
        synchronized (LOCK) {
            recordWalkLocked(legacy, walkMs, windows, visited, actionable, pruned, depthCapped, unchanged, truncated, null);
        }
    }

    /** Caller holds LOCK. slowWalkExtra is appended to the slow-walk line. */
    private static void recordWalkLocked(boolean legacy, int walkMs, int windows, int visited, int actionable,
            int pruned, int depthCapped, boolean unchanged, boolean truncated, String slowWalkExtra) {
        if (legacy) legacyWalks++; else snapshotWalks++;
        if (unchanged) unchangedWalks++;
        if (truncated) truncatedWalks++;
        walkMsSum += walkMs;
        if (walkMs > walkMsMax) walkMsMax = walkMs;
        windowsSum += windows;
        visitedSum += visited;
        if (visited > visitedMax) visitedMax = visited;
        actionableSum += actionable;
        if (actionable > actionableMax) actionableMax = actionable;
        prunedSum += pruned;
        depthCappedSum += depthCapped;
        if (walkMs >= SLOW_WALK_MS) {
            long now = SystemClock.uptimeMillis();
            if (now - lastSlowWalkLogMs >= SLOW_WALK_LOG_INTERVAL_MS) {
                lastSlowWalkLogMs = now;
                Log.d(TAG, String.format(Locale.US,
                        "slow walk: api=%s walkMs=%d windows=%d visited=%d actionable=%d pruned=%d depthCapped=%d unchanged=%b truncated=%b%s",
                        legacy ? "legacy" : "snapshot", walkMs, windows, visited, actionable, pruned, depthCapped, unchanged, truncated,
                        slowWalkExtra != null ? slowWalkExtra : ""));
            }
        }
    }

    /**
     * Budget truncation of one finished snapshot walk, in every build. Called on the node-query executor. After
     * BUDGET_TRUNCATION_WARN_STREAK consecutive walks with at least one window truncated by windowBudget or
     * totalBudget, logs one Log.w with counts only, at most once per BUDGET_TRUNCATION_WARN_INTERVAL_MS. A walk
     * without budget truncation resets the streak.
     */
    static void noteSnapshotBudget(int walkMs, int windows, int[] truncatedByReason) {
        final int windowBudget = truncatedByReason[TRUNCATED_WINDOW_BUDGET];
        final int totalBudget = truncatedByReason[TRUNCATED_TOTAL_BUDGET];
        int streak;
        synchronized (LOCK) {
            if (windowBudget + totalBudget == 0) {
                budgetTruncationStreak = 0;
                return;
            }
            streak = ++budgetTruncationStreak;
            if (streak < BUDGET_TRUNCATION_WARN_STREAK) return;
            final long now = SystemClock.uptimeMillis();
            if (budgetTruncationWarned && now - lastBudgetTruncationWarnMs < BUDGET_TRUNCATION_WARN_INTERVAL_MS) return;
            budgetTruncationWarned = true;
            lastBudgetTruncationWarnMs = now;
        }
        Log.w(TAG, String.format(Locale.US,
                "repeated budget truncation: %d consecutive snapshot walks truncated (last: walkMs=%d windows=%d windowBudget=%d totalBudget=%d)",
                streak, walkMs, windows, windowBudget, totalBudget));
    }

    /** Main-thread part of one fetch: executor-done -> main-runnable-start delay and the result.success encode time. */
    static void recordMain(long postDelayNanos, long encodeNanos) {
        if (!enabled) return;
        double postDelayMs = postDelayNanos / 1_000_000.0;
        double encodeMs = encodeNanos / 1_000_000.0;
        synchronized (LOCK) {
            mainCount++;
            postDelayMsSum += postDelayMs;
            if (postDelayMs > postDelayMsMax) postDelayMsMax = postDelayMs;
            encodeMsSum += encodeMs;
            if (encodeMs > encodeMsMax) encodeMsMax = encodeMs;
        }
    }

    /**
     * Logs the window's summary when it had walks, refreshes or pushes, then resets. Main thread. Returns whether
     * there was any such activity.
     */
    private static boolean emitSummary() {
        String line = null;
        synchronized (LOCK) {
            int walks = snapshotWalks + legacyWalks;
            if (walks > 0 || refreshCalls > 0 || pushesSent > 0) {
                double walkDiv = Math.max(1, walks);
                float p95 = 0f;
                if (gapCount > 0) {
                    float[] sorted = Arrays.copyOf(gaps, gapCount);
                    Arrays.sort(sorted);
                    p95 = sorted[Math.min(gapCount - 1, (int) Math.ceil(gapCount * 0.95) - 1)];
                }
                line = String.format(Locale.US,
                        "summary %ds: walks=%d/%d (snapshot/legacy) unchanged=%d truncated=%d walkMs avg=%.1f max=%d windows avg=%.1f"
                                + " visited avg=%.0f max=%d actionable avg=%.0f max=%d pruned avg=%.1f depthCapped avg=%.1f"
                                + " prefetch=%s msPerNode=%.3f slowFetches avg=%.1f max=%d"
                                + " truncatedWindows=%d (windowBudget=%d totalBudget=%d cap=%d noRoot=%d error=%d)"
                                + " encodeMs avg=%.2f max=%.2f postDelayMs avg=%.2f max=%.2f"
                                + " events structural=%d content=%d textOnly=%d own=%d eventDelayMs avg=%.1f max=%d"
                                + " contentTypes undefined=%d subtree=%d paneIn=%d paneOut=%d other=%d pushes=%d"
                                + " refresh n=%d ok=%d missing=%d gone=%d hidden=%d moved=%d error=%d refreshMs avg=%.1f max=%d"
                                + " gaps n=%d p95=%.1f max=%.1f over32=%d",
                        Math.round((SystemClock.uptimeMillis() - windowStartMs) / 1000.0),
                        snapshotWalks, legacyWalks, unchangedWalks, truncatedWalks,
                        walkMsSum / walkDiv, walkMsMax, windowsSum / walkDiv,
                        visitedSum / walkDiv, visitedMax, actionableSum / walkDiv, actionableMax,
                        prunedSum / walkDiv, depthCappedSum / walkDiv,
                        prefetchName(lastPrefetchStrategy),
                        snapshotVisitedSum > 0 ? snapshotWalkNanosSum / 1_000_000.0 / snapshotVisitedSum : 0.0,
                        snapshotWalks > 0 ? slowFetchSum / (double) snapshotWalks : 0.0, slowFetchMax,
                        windowsTruncatedSum, truncatedWindowsByReason[TRUNCATED_WINDOW_BUDGET],
                        truncatedWindowsByReason[TRUNCATED_TOTAL_BUDGET], truncatedWindowsByReason[TRUNCATED_CAP],
                        truncatedWindowsByReason[TRUNCATED_NO_ROOT], truncatedWindowsByReason[TRUNCATED_ERROR],
                        mainCount > 0 ? encodeMsSum / mainCount : 0.0, encodeMsMax,
                        mainCount > 0 ? postDelayMsSum / mainCount : 0.0, postDelayMsMax,
                        eventsStructural, eventsContent, eventsTextOnly, eventsOwn,
                        eventDelayCount > 0 ? eventDelayMsSum / (double) eventDelayCount : 0.0, eventDelayMsMax,
                        ctUndefined, ctSubtree, ctPaneAppeared, ctPaneDisappeared, ctOther, pushesSent,
                        refreshCalls, refreshCounts[0], refreshCounts[1], refreshCounts[2], refreshCounts[3],
                        refreshCounts[4], refreshCounts[5], refreshCalls > 0 ? refreshMsSum / (double) refreshCalls : 0.0,
                        refreshMsMax,
                        gapCount, p95, gapMax, gapsOver32);
            }
        }
        if (line != null) Log.d(TAG, line);
        resetWindow();
        return line != null;
    }

    private static void resetWindow() {
        synchronized (LOCK) {
            snapshotWalks = 0;
            legacyWalks = 0;
            unchangedWalks = 0;
            truncatedWalks = 0;
            walkMsSum = 0;
            walkMsMax = 0;
            windowsSum = 0;
            visitedSum = 0;
            visitedMax = 0;
            actionableSum = 0;
            actionableMax = 0;
            prunedSum = 0;
            depthCappedSum = 0;
            snapshotWalkNanosSum = 0;
            snapshotVisitedSum = 0;
            slowFetchSum = 0;
            slowFetchMax = 0;
            windowsTruncatedSum = 0;
            Arrays.fill(truncatedWindowsByReason, 0);
            lastPrefetchStrategy = Integer.MIN_VALUE;
            mainCount = 0;
            encodeMsSum = 0;
            encodeMsMax = 0;
            postDelayMsSum = 0;
            postDelayMsMax = 0;
            Arrays.fill(refreshCounts, 0);
            refreshCalls = 0;
            refreshMsSum = 0;
            refreshMsMax = 0;
        }
        eventDelayCount = 0;
        eventDelayMsSum = 0;
        eventDelayMsMax = 0;
        ctUndefined = 0;
        ctSubtree = 0;
        ctPaneAppeared = 0;
        ctPaneDisappeared = 0;
        ctOther = 0;
        pushesSent = 0;
        eventsStructural = 0;
        eventsContent = 0;
        eventsTextOnly = 0;
        eventsOwn = 0;
        gapCount = 0;
        gapWrite = 0;
        gapsOver32 = 0;
        gapMax = 0f;
    }
}
