package slayer.accessibility.service.flutter_accessibility_service;

import static slayer.accessibility.service.flutter_accessibility_service.Constants.*;
import static slayer.accessibility.service.flutter_accessibility_service.FlutterAccessibilityServicePlugin.CACHED_TAG;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.RequiresApi;


import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.FlutterInjector;
import io.flutter.plugin.common.EventChannel;


public class AccessibilityListener extends AccessibilityService {
    private static WindowManager mWindowManager;
    private static FlutterView mOverlayView;
    static private boolean isOverlayShown = false;
    private static AccessibilityListener serviceInstance;
    
    // Multiple overlay management
    private static final HashMap<Integer, AccessibilityOverlay> activeOverlays = new HashMap<>();
    private static FlutterEngineGroup engineGroup;
    private static final int CACHE_SIZE = 4 * 1024 * 1024; // 4Mib
    private static final int maxDepth = 20;
    private static LruCache<String, AccessibilityNodeInfo> nodeMap =
            new LruCache<>(CACHE_SIZE);
    private static final int DEFAULT_MAX_TREE_DEPTH = 15;
    private int maximumTreeDepth = DEFAULT_MAX_TREE_DEPTH;
    
    // Message routing system
    private static final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<String>> messageQueues = new ConcurrentHashMap<>();
    private static final String MESSAGE_INTENT = "ACCESSIBILITY_MESSAGE";
    private static volatile boolean isMainAppListening = false;

    // Set true whenever the foreground window changes so the Dart snap controller
    // can refetch the interactive-node list early instead of waiting for its poll tick.
    private static volatile boolean nodesDirty = true;

    /// Returns true (and clears the flag) if the window content/state changed since the
    /// last call. Used by the gaze "snap to item" feature to invalidate its node cache.
    public static boolean consumeNodesDirty() {
        boolean dirty = nodesDirty;
        nodesDirty = false;
        return dirty;
    }
    
    // Monotonic tree-change counters for the snap-to-item refetch policy (APPS-112). Never reset, so every engine can
    // track them independently (unlike nodesDirty, which the first consumer clears for everyone).
    // structural: TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED. content: TYPE_WINDOW_CONTENT_CHANGED that is not
    // text-only. Events of our own package (overlays, main app) bump neither.
    private static final AtomicLong structuralGeneration = new AtomicLong();
    private static final AtomicLong contentGeneration = new AtomicLong();
    private static final int TEXT_ONLY_CONTENT_CHANGE_MASK = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
            | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
            | AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION;
    // Cached in onServiceConnected so onAccessibilityEvent compares without allocating.
    private static volatile String ownPackageName;

    // Node-change push (APPS-112 follow-up): one EventSink per listening engine. Mutated on the main thread by the
    // plugin's stream handlers, read by countNodeChangeEvent (main thread) - copy-on-write so the common-case
    // isEmpty() check and the send loop never need a lock.
    private static final CopyOnWriteArraySet<EventChannel.EventSink> nodeChangeSinks = new CopyOnWriteArraySet<>();
    private static final long NODE_CHANGE_PUSH_DELAY_MS = 16;
    private static final Handler nodeChangeHandler = new Handler(Looper.getMainLooper());
    // Main thread only.
    private static boolean nodeChangePushScheduled;
    private static final Runnable nodeChangePushRunnable = AccessibilityListener::sendNodeChangePush;

    // Actionable nodes of the snapshot walks per windowId (snapshot window order), by the same id the Dart decoder
    // builds: a complete window holds the refs of its last walk, a truncated one also keeps its earlier refs (the Dart
    // side carries those nodes). Built on the node-query executor, published whole via this volatile field and never
    // mutated afterwards, so the refresh executor can read it without a lock. The legacy getInteractiveNodes walk does
    // not touch it.
    private static volatile Map<Integer, Map<String, AccessibilityNodeInfo>> snapshotNodeRefs = Collections.emptyMap();

    public static long getStructuralGeneration() {
        return structuralGeneration.get();
    }

    public static long getContentGeneration() {
        return contentGeneration.get();
    }

    /// Registers an engine's node-change sink. Main thread.
    public static void addNodeChangeSink(EventChannel.EventSink sink) {
        if (sink != null) nodeChangeSinks.add(sink);
    }

    /// Unregisters an engine's node-change sink. Main thread.
    public static void removeNodeChangeSink(EventChannel.EventSink sink) {
        if (sink != null) nodeChangeSinks.remove(sink);
    }

    /// Schedules one coalesced push of the current counters ~16ms from now, unless one is already pending or nobody
    /// listens. Main thread; allocation-free.
    private static void scheduleNodeChangePush() {
        if (nodeChangePushScheduled || nodeChangeSinks.isEmpty()) return;
        nodeChangePushScheduled = true;
        nodeChangeHandler.postDelayed(nodeChangePushRunnable, NODE_CHANGE_PUSH_DELAY_MS);
    }

    private static void sendNodeChangePush() {
        nodeChangePushScheduled = false;
        if (nodeChangeSinks.isEmpty()) return;
        HashMap<String, Object> state = new HashMap<>(4);
        state.put("structural", structuralGeneration.get());
        state.put("content", contentGeneration.get());
        state.put("connected", serviceInstance != null);
        for (EventChannel.EventSink sink : nodeChangeSinks) {
            try {
                sink.success(state);
            } catch (Exception e) {
                Log.w("AccessibilityListener", "node change push failed: " + e.getMessage());
            }
        }
        SnapNodeStats.notePushSent();
    }

    /// Drops the node references of the last snapshot walk (service not connected, failed walk).
    public static void clearSnapshotNodeRefs() {
        snapshotNodeRefs = Collections.emptyMap();
    }

    /// Re-reads one node of the snapshot walks (looked up over all windows) from its app (refresh() bypasses the
    /// accessibility cache) and checks it is still attached, visible, enabled and contains (x, y). Returns "ok", "missing", "gone", "hidden",
    /// "moved" or "error". Runs on the plugin's node-refresh executor, never on the main thread.
    public static String refreshInteractiveNode(String id, int x, int y) {
        if (serviceInstance == null || id == null) return "error";
        try {
            AccessibilityNodeInfo node = null;
            for (Map<String, AccessibilityNodeInfo> windowRefs : snapshotNodeRefs.values()) {
                node = windowRefs.get(id);
                if (node != null) break;
            }
            if (node == null) return "missing";
            if (!node.refresh()) return "gone";
            if (!node.isVisibleToUser() || !node.isEnabled()) return "hidden";
            final Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.contains(x, y)) return "moved";
            return "ok";
        } catch (Exception e) {
            Log.w("AccessibilityListener", "refreshInteractiveNode failed: " + e.getMessage());
            return "error";
        }
    }

    public static AccessibilityNodeInfo getNodeInfo(String id) {
        return nodeMap.get(id);
    }

    /// Finds the deepest actionable node whose bounds contain (x, y) in the active
    /// window and invokes the given AccessibilityNodeInfo action on it. Works on
    /// views that reject synthesized touch gestures (e.g. FLAG_SECURE surfaces).
    /// Returns true iff a matching node was found and performAction succeeded.
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    public static boolean performActionAtPoint(int x, int y, int action) {
        if (serviceInstance == null) {
            Log.e("AccessibilityListener", "Service not connected - cannot perform action at point");
            return false;
        }
        // Target the top-most window that actually contains the point. getRootInActiveWindow()
        // only returns the input-focused window (e.g. the app), so taps on a soft keyboard / IME
        // or any window layered above the active one would otherwise search the wrong tree and
        // hit a stray node behind the visible surface. Falls back to the active window.
        AccessibilityNodeInfo root = findTopmostWindowRootAtPoint(x, y);
        if (root == null) {
            try {
                root = serviceInstance.getRootInActiveWindow();
            } catch (Exception e) {
                Log.e("AccessibilityListener", "getRootInActiveWindow failed: " + e.getMessage());
                return false;
            }
        }
        if (root == null) {
            Log.w("AccessibilityListener", "No root node for point (" + x + ", " + y + ")");
            return false;
        }
        AccessibilityNodeInfo target = findDeepestActionableNode(root, x, y, action, 0);
        if (target == null) {
            Log.d("AccessibilityListener", "No actionable node at (" + x + ", " + y + ") for action " + action);
            return false;
        }
        try {
            boolean success = target.performAction(action);
            Log.d("AccessibilityListener", "performAction " + action + " at (" + x + ", " + y + ") on '" + target.getViewIdResourceName() + "' = " + success);
            return success;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "performAction threw: " + e.getMessage());
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private static AccessibilityNodeInfo findDeepestActionableNode(
            AccessibilityNodeInfo node, int x, int y, int action, int depth) {
        if (node == null || depth > maxDepth) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return null;

        // Prefer the deepest match: recurse into children first.
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo match = findDeepestActionableNode(child, x, y, action, depth + 1);
            if (match != null) return match;
        }

        if (!node.isVisibleToUser() || !node.isEnabled()) return null;
        if (action == AccessibilityNodeInfo.ACTION_CLICK && node.isClickable()) return node;
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && node.isLongClickable()) return node;
        return null;
    }

    /// Returns the root node of the visually top-most window whose bounds contain the point,
    /// ignoring our own accessibility overlays. This makes node clicks land in the correct
    /// window (e.g. the IME/soft-keyboard window) instead of always the input-focused window.
    /// Returns null if no window contains the point or windows cannot be enumerated.
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private static AccessibilityNodeInfo findTopmostWindowRootAtPoint(int x, int y) {
        if (serviceInstance == null) return null;
        List<AccessibilityWindowInfo> windows;
        try {
            windows = serviceInstance.getWindows();
        } catch (Exception e) {
            Log.e("AccessibilityListener", "getWindows failed: " + e.getMessage());
            return null;
        }
        if (windows == null || windows.isEmpty()) return null;
        AccessibilityWindowInfo best = null;
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            // Never target our own gaze cursor / highlight overlays.
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            w.getBoundsInScreen(bounds);
            if (!bounds.contains(x, y)) continue;
            // Higher layer == visually on top; pick the front-most window containing the point.
            if (best == null || w.getLayer() > best.getLayer()) best = w;
        }
        if (best == null) return null;
        try {
            return best.getRoot();
        } catch (Exception e) {
            Log.e("AccessibilityListener", "window.getRoot failed: " + e.getMessage());
            return null;
        }
    }

    /// Enumerates every actionable (clickable / long-clickable / scrollable / editable) node
    /// currently on screen together with its screen bounds. Used by the gaze-driven
    /// "snap to item" feature, which computes the nearest snap target in Dart. Returns a
    /// fresh, fully-serialized list each call (no AccessibilityNodeInfo handles retained).
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static List<Map<String, Object>> getInteractiveNodes() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (serviceInstance == null) {
            Log.e("AccessibilityListener", "Service not connected - cannot enumerate nodes");
            return out;
        }
        // Stats only (SnapNodeStats, debug builds); the walk itself is unchanged.
        final boolean stats = SnapNodeStats.isEnabled();
        final long startNanos = stats ? System.nanoTime() : 0L;
        final int[] visited = new int[1];
        int walkedWindows = 0;
        try {
            List<AccessibilityWindowInfo> windows = serviceInstance.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    // Skip our own highlight/overlay windows so they never become snap targets.
                    if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null) {
                        walkedWindows++;
                        collectInteractive(root, out, 0, w.getType(), visited);
                    }
                }
            } else {
                AccessibilityNodeInfo root = serviceInstance.getRootInActiveWindow();
                if (root != null) {
                    walkedWindows++;
                    collectInteractive(root, out, 0, -1, visited);
                }
            }
        } catch (Exception e) {
            Log.e("AccessibilityListener", "getInteractiveNodes failed: " + e.getMessage());
        }
        if (stats) {
            int walkMs = (int) ((System.nanoTime() - startNanos) / 1_000_000L);
            SnapNodeStats.recordWalk(true, walkMs, walkedWindows, visited[0], out.size(), 0, 0, false, false);
        }
        return out;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private static void collectInteractive(AccessibilityNodeInfo node,
            List<Map<String, Object>> out, int depth, int windowType, int[] visited) {
        if (node == null || depth > maxDepth) return;
        visited[0]++;
        if (node.isVisibleToUser() && node.isEnabled()) {
            boolean actionable = node.isClickable() || node.isLongClickable()
                    || node.isScrollable() || node.isEditable();
            if (actionable) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (b.width() > 0 && b.height() > 0) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("bounds", getBoundingPoints(b));
                    m.put("isClickable", node.isClickable());
                    m.put("isLongClickable", node.isLongClickable());
                    m.put("isScrollable", node.isScrollable());
                    m.put("isEditable", node.isEditable());
                    m.put("isFocusable", node.isFocusable());
                    m.put("text", node.getText() != null ? node.getText().toString() : null);
                    m.put("contentDescription", node.getContentDescription() != null
                            ? node.getContentDescription().toString() : null);
                    m.put("viewId", node.getViewIdResourceName());
                    m.put("className", node.getClassName() != null ? node.getClassName().toString() : null);
                    m.put("windowType", windowType);
                    String idBase = node.getViewIdResourceName() != null
                            ? node.getViewIdResourceName()
                            : (node.getClassName() != null ? node.getClassName().toString() : "node");
                    m.put("id", idBase + "@" + b.left + "," + b.top + "," + b.right + "," + b.bottom);
                    out.add(m);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectInteractive(child, out, depth + 1, windowType, visited);
        }
    }

    /// Compact, bounded variant of getInteractiveNodes for the snap-to-item refetch policy (APPS-112). Same
    /// actionable rule and window set, but:
    /// - invisible subtrees whose bounds lie entirely outside their window are pruned (off-screen web content),
    /// - each window stops after maxVisitedPerWindow visited / maxActionablePerWindow actionable nodes or after
    ///   windowBudgetMs (that window is truncated, the walk continues with the next one), the whole walk after
    ///   timeBudgetMs (the current and all remaining windows are truncated),
    /// - nodes are fetched with prefetchStrategy (AccessibilityNodeInfo FLAG_PREFETCH_* for getRoot(int) /
    ///   getChild(int, int)); -1 keeps the plain getRoot() / getChild(int),
    /// - web content (below a node with className android.webkit.WebView): Chromium's chromeRole / clickableScore /
    ///   offscreen extras are read once per node, scrolled-off nodes (offscreen extra + empty bounds) are pruned with
    ///   their subtree, an interactive role or a control className makes a node actionable even when it is not
    ///   clickable, and the depth cap counts MAX_WEB_DEPTH levels from the web root (maxDepth elsewhere),
    /// - the result is a flat int[] (9 per node: l, t, r, b, flags (bit 32 = web content), windowType, parentIndex
    ///   (nearest appended ancestor, -1 none), webRole, clickableScore) plus a flat string list (4 per node:
    ///   viewId, className, text, contentDescription; the last two truncated to 64 chars), built here on the caller's
    ///   (background) thread so the platform-thread encode stays cheap,
    /// - "windowInts" lists every considered window (9 ints: windowId, windowType, nodeStart, nodeCount, truncated,
    ///   l, t, r, b), also the ones without a root or never reached (truncated, nodeCount 0),
    /// - a 64-bit signature over geometry + identity (not text) and the window breakdown lets an identical walk
    ///   answer "unchanged" without any node payload; a walk cut short by a cap, a budget or an exception never does.
    /// The change generations are read BEFORE the walk, so an event during the walk shows up as a later change.
    /// Runs on the node-query executor; never on the main thread.
    public static HashMap<String, Object> getInteractiveNodeSnapshot(Long lastSignature, int maxVisitedPerWindow,
            int maxActionablePerWindow, int timeBudgetMs, int windowBudgetMs, int prefetchStrategy) {
        final long structuralGen = structuralGeneration.get();
        final long contentGen = contentGeneration.get();
        final AccessibilityListener service = serviceInstance;
        if (service == null) {
            clearSnapshotNodeRefs();
            return emptyNodeSnapshot(structuralGen, contentGen);
        }
        SnapshotWalk walk = new SnapshotWalk(maxVisitedPerWindow, maxActionablePerWindow, timeBudgetMs, windowBudgetMs,
                sanitizePrefetchStrategy(prefetchStrategy));
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    // Skip our own highlight/overlay windows so they never become snap targets.
                    if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                    walk.walkWindow(w);
                }
            } else {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) walk.walkActiveRoot(root);
            }
        } catch (Exception e) {
            walk.truncated = true;
            Log.e("AccessibilityListener", "getInteractiveNodeSnapshot failed: " + e.getMessage());
        }
        return walk.finish(lastSignature, structuralGen, contentGen);
    }

    private static volatile boolean invalidPrefetchLogged;
    // AccessibilityNodeInfo.FLAG_PREFETCH_MASK / FLAG_PREFETCH_DESCENDANTS_MASK are @hide.
    private static final int PREFETCH_MASK = 0x3f;
    private static final int PREFETCH_DESCENDANTS_MASK = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
            | AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST
            | AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST;

    /// A strategy the platform would reject (bits outside FLAG_PREFETCH_MASK, more than one descendant strategy -
    /// AccessibilityInteractionClient throws IllegalArgumentException) falls back to the plain calls (-1).
    static int sanitizePrefetchStrategy(int strategy) {
        if (strategy < 0) return -1;
        final int descendants = strategy & PREFETCH_DESCENDANTS_MASK;
        if ((strategy & ~PREFETCH_MASK) != 0 || (descendants & (descendants - 1)) != 0) {
            if (!invalidPrefetchLogged) {
                invalidPrefetchLogged = true;
                Log.w("AccessibilityListener", "invalid prefetchStrategy " + strategy + ", using the platform default");
            }
            return -1;
        }
        return strategy;
    }

    /// Result shape of getInteractiveNodeSnapshot when the service is not connected: changed, signature 0, no nodes.
    public static HashMap<String, Object> emptyNodeSnapshot(long structuralGen, long contentGen) {
        HashMap<String, Object> m = new HashMap<>(20);
        m.put("unchanged", false);
        m.put("signature", 0L);
        m.put("ints", new int[0]);
        m.put("strings", new ArrayList<String>(0));
        m.put("windowInts", new int[0]);
        m.put("structuralGeneration", structuralGen);
        m.put("contentGeneration", contentGen);
        m.put("walkMs", 0);
        m.put("windows", 0);
        m.put("visited", 0);
        m.put("actionable", 0);
        m.put("pruned", 0);
        m.put("depthCapped", 0);
        m.put("truncated", false);
        return m;
    }

    /// One getInteractiveNodeSnapshot walk. Not thread-safe; one instance per call.
    /// Node recycling: the plugin's minSdk is 35 and AccessibilityNodeInfo.recycle() is a no-op since API 33, so
    /// nodes are simply dropped.
    private static final class SnapshotWalk {
        // left, top, right, bottom, flags, windowType, parentIndex, webRole, clickableScore.
        private static final int INT_STRIDE = 9;
        private static final int STRING_STRIDE = 4;
        private static final int FLAG_WEB_CONTENT = 32;
        // Depth cap below a web root (Chrome's own UI above the root already uses ~8-12 of the 20 general levels).
        private static final int MAX_WEB_DEPTH = 40;
        private static final String WEB_ROOT_CLASS = "android.webkit.WebView";
        private static final String EXTRA_CHROME_ROLE = "AccessibilityNodeInfo.chromeRole";
        private static final String EXTRA_CLICKABLE_SCORE = "AccessibilityNodeInfo.clickableScore";
        private static final String EXTRA_OFFSCREEN = "AccessibilityNodeInfo.offscreen";
        // Mirror of the Dart webRole* ids (interactive_nodes_snapshot.dart); 99 = any other role string.
        static final int WEB_ROLE_OTHER = 99;
        static final int WEB_ROLE_MAX_INTERACTIVE = 14;
        private static final HashMap<String, Integer> WEB_ROLES = new HashMap<>(64);
        private static final HashSet<String> WEB_TARGET_CLASSES = new HashSet<>(Arrays.asList(
                "android.widget.Button", "android.widget.CheckBox", "android.widget.RadioButton", "android.widget.Switch",
                "android.widget.ToggleButton", "android.widget.EditText", "android.widget.Spinner", "android.widget.SeekBar",
                "android.widget.ImageButton"));

        static {
            WEB_ROLES.put("link", 1);
            WEB_ROLES.put("button", 2);
            WEB_ROLES.put("popUpButton", 2);
            WEB_ROLES.put("checkBox", 3);
            WEB_ROLES.put("menuItemCheckBox", 3);
            WEB_ROLES.put("radioButton", 4);
            WEB_ROLES.put("menuItemRadio", 4);
            WEB_ROLES.put("switch", 5);
            WEB_ROLES.put("toggleButton", 6);
            WEB_ROLES.put("menuItem", 7);
            WEB_ROLES.put("tab", 8);
            WEB_ROLES.put("listBoxOption", 9);
            WEB_ROLES.put("menuListOption", 9);
            WEB_ROLES.put("comboBoxSelect", 10);
            WEB_ROLES.put("comboBoxMenuButton", 10);
            WEB_ROLES.put("textField", 11);
            WEB_ROLES.put("searchBox", 11);
            WEB_ROLES.put("spinButton", 11);
            WEB_ROLES.put("textFieldWithComboBox", 11);
            WEB_ROLES.put("slider", 12);
            WEB_ROLES.put("treeItem", 13);
            WEB_ROLES.put("disclosureTriangle", 14);
            WEB_ROLES.put("heading", 15);
            WEB_ROLES.put("image", 16);
        }

        /// Role id of a chromeRole extra: 0 none/empty, 99 any role string outside the table.
        static int webRoleId(CharSequence role) {
            if (role == null || role.length() == 0) return 0;
            final Integer id = WEB_ROLES.get(role.toString());
            return id != null ? id : WEB_ROLE_OTHER;
        }

        private static final int WINDOW_INT_STRIDE = 9;
        private static final int MAX_TEXT_LENGTH = 64;
        private static final long SLOW_FETCH_NANOS = 1_000_000L;
        private static final long FNV_OFFSET = 0xcbf29ce484222325L;
        private static final long FNV_PRIME = 0x100000001b3L;

        private final int maxVisitedPerWindow;
        private final int maxActionablePerWindow;
        private final long startNanos;
        private final long budgetNanos;
        private final long windowBudgetNanos;
        private final int prefetchStrategy;
        // Per-fetch timing (slowFetches) is stats only: debug builds.
        private final boolean measure = SnapNodeStats.isEnabled();
        private final Rect bounds = new Rect();

        private int[] ints = new int[INT_STRIDE * 64];
        private int intCount;
        private final ArrayList<String> strings = new ArrayList<>(STRING_STRIDE * 64);
        private long hash = FNV_OFFSET;
        private final ArrayList<SnapshotWindowEntry> entries = new ArrayList<>(8);

        int windows;
        int visited;
        int actionable;
        int pruned;
        int depthCapped;
        int slowFetches;
        // Web content (debug `web:` line only, besides offscreenPruned which also counts into pruned).
        int webVisited;
        int webTargets;
        int webByClickable;
        int webByRole;
        int webByClass;
        int webOffscreenPruned;
        int webDepthCapped;
        final int[] webRoleCounts = new int[SnapNodeStats.WEB_ROLE_NAMES.length];
        // Extras of the node being visited (set by readWebExtras).
        private int extraRole;
        private int extraScore;
        private boolean extraOffscreen;
        boolean truncated;
        boolean stopAll;
        final int[] truncatedByReason = new int[SnapNodeStats.TRUNCATION_REASONS.length];

        // Per window.
        private SnapshotWindowEntry current;
        private Rect windowBounds;
        private int windowType;
        private long windowStartNanos;
        private int windowVisited;
        private int windowActionable;
        private boolean stopWindow;

        SnapshotWalk(int maxVisitedPerWindow, int maxActionablePerWindow, int timeBudgetMs, int windowBudgetMs,
                int prefetchStrategy) {
            this.maxVisitedPerWindow = maxVisitedPerWindow;
            this.maxActionablePerWindow = maxActionablePerWindow;
            this.budgetNanos = timeBudgetMs * 1_000_000L;
            this.windowBudgetNanos = windowBudgetMs * 1_000_000L;
            this.prefetchStrategy = prefetchStrategy;
            this.startNanos = System.nanoTime();
        }

        /// One window of getWindows(): always produces exactly one entry.
        void walkWindow(AccessibilityWindowInfo w) {
            final Rect wb = new Rect();
            w.getBoundsInScreen(wb);
            final SnapshotWindowEntry entry = new SnapshotWindowEntry(w.getId(), w.getType(), wb, actionable);
            entries.add(entry);
            if (!stopAll && System.nanoTime() - startNanos > budgetNanos) stopAll = true;
            if (stopAll) {
                entry.reason = SnapNodeStats.TRUNCATED_TOTAL_BUDGET;
                return;
            }
            AccessibilityNodeInfo root;
            try {
                final long t0 = measure ? System.nanoTime() : 0L;
                root = prefetchStrategy >= 0 ? w.getRoot(prefetchStrategy) : w.getRoot();
                if (measure && System.nanoTime() - t0 >= SLOW_FETCH_NANOS) slowFetches++;
            } catch (RuntimeException e) {
                Log.w("AccessibilityListener", "snapshot getRoot failed: " + e.getMessage());
                entry.reason = SnapNodeStats.TRUNCATED_ERROR;
                truncated = true;
                return;
            }
            if (root == null) {
                // Not a cut-short walk (the old walk skipped such windows silently), but the window's list is not
                // authoritative either: truncated in windowInts, without the walk-level truncated flag.
                entry.reason = SnapNodeStats.TRUNCATED_NO_ROOT;
                return;
            }
            walkEntry(entry, root, wb.isEmpty() ? null : wb, w.getType());
        }

        /// The getRootInActiveWindow fallback: one entry, type -1, bounds of the root, no off-window pruning.
        void walkActiveRoot(AccessibilityNodeInfo root) {
            final Rect rb = new Rect();
            root.getBoundsInScreen(rb);
            final SnapshotWindowEntry entry = new SnapshotWindowEntry(root.getWindowId(), -1, rb, actionable);
            entries.add(entry);
            walkEntry(entry, root, null, -1);
        }

        private void walkEntry(SnapshotWindowEntry entry, AccessibilityNodeInfo root, Rect windowBounds, int windowType) {
            current = entry;
            this.windowBounds = windowBounds;
            this.windowType = windowType;
            windowStartNanos = System.nanoTime();
            windowVisited = 0;
            windowActionable = 0;
            stopWindow = false;
            windows++;
            try {
                visit(root, 0, -1, -1);
            } catch (RuntimeException e) {
                Log.w("AccessibilityListener", "snapshot window walk failed: " + e.getMessage());
                if (entry.reason == SnapNodeStats.TRUNCATED_NONE) entry.reason = SnapNodeStats.TRUNCATED_ERROR;
                truncated = true;
            } finally {
                entry.nodeCount = actionable - entry.nodeStart;
                current = null;
            }
        }

        private void stopCurrentWindow(int reason) {
            stopWindow = true;
            truncated = true;
            if (current.reason == SnapNodeStats.TRUNCATED_NONE) current.reason = reason;
        }

        /// webRootDepth: depth of the nearest android.webkit.WebView ancestor, -1 outside web content. parentIndex: node
        /// index of the nearest ancestor that was appended, -1 when none.
        private void visit(AccessibilityNodeInfo node, int depth, int webRootDepth, int parentIndex) {
            if (stopWindow || stopAll) return;
            visited++;
            windowVisited++;
            if (windowVisited > maxVisitedPerWindow) {
                stopCurrentWindow(SnapNodeStats.TRUNCATED_CAP);
                return;
            }
            // Every node: one nanoTime (~25ns) is noise next to a node fetch, and a slow app (several ms per cache
            // miss) would otherwise overshoot the budgets by dozens of fetches.
            final long now = System.nanoTime();
            if (now - startNanos > budgetNanos) {
                stopAll = true;
                stopCurrentWindow(SnapNodeStats.TRUNCATED_TOTAL_BUDGET);
                return;
            }
            if (now - windowStartNanos > windowBudgetNanos) {
                stopCurrentWindow(SnapNodeStats.TRUNCATED_WINDOW_BUDGET);
                return;
            }
            node.getBoundsInScreen(bounds);
            final boolean visible = node.isVisibleToUser();
            if (!visible && windowBounds != null && !bounds.isEmpty() && !Rect.intersects(bounds, windowBounds)) {
                pruned++;
                return;
            }
            final boolean inWeb = webRootDepth >= 0;
            if (inWeb) {
                webVisited++;
                readWebExtras(node);
                // Scrolled-off web nodes stay isVisibleToUser() with bounds clipped to nothing: the prune above never
                // fires for them and a long page would eat the visited cap.
                if (extraOffscreen && bounds.isEmpty()) {
                    pruned++;
                    webOffscreenPruned++;
                    return;
                }
            }
            final CharSequence classNameSeq = node.getClassName();
            final String className = classNameSeq != null ? classNameSeq.toString() : null;
            int childParentIndex = parentIndex;
            if (visible && node.isEnabled() && bounds.width() > 0 && bounds.height() > 0) {
                final boolean clickable = node.isClickable();
                final boolean longClickable = node.isLongClickable();
                final boolean scrollable = node.isScrollable();
                final boolean editable = node.isEditable();
                final boolean byFlags = clickable || longClickable || scrollable || editable;
                boolean byRole = false;
                boolean byClass = false;
                if (!byFlags && inWeb) {
                    byRole = extraRole >= 1 && extraRole <= WEB_ROLE_MAX_INTERACTIVE;
                    byClass = !byRole && className != null && WEB_TARGET_CLASSES.contains(className);
                }
                if (byFlags || byRole || byClass) {
                    int flags = (clickable ? 1 : 0) | (longClickable ? 2 : 0) | (scrollable ? 4 : 0)
                            | (editable ? 8 : 0) | (node.isFocusable() ? 16 : 0) | (inWeb ? FLAG_WEB_CONTENT : 0);
                    childParentIndex = actionable;
                    append(node, className, flags, parentIndex, inWeb ? extraRole : 0, inWeb ? extraScore : 0);
                    if (inWeb) {
                        webTargets++;
                        if (byFlags) {
                            webByClickable++;
                        } else if (byRole) {
                            webByRole++;
                        } else {
                            webByClass++;
                        }
                        final int roleSlot = SnapNodeStats.webRoleSlot(extraRole);
                        if (roleSlot >= 0) webRoleCounts[roleSlot]++;
                    }
                    if (windowActionable >= maxActionablePerWindow) {
                        stopCurrentWindow(SnapNodeStats.TRUNCATED_CAP);
                        return;
                    }
                }
            }
            final int childCount = node.getChildCount();
            // The WebView itself is the web root: its descendants are web content, counted from here.
            final int childWebRootDepth = inWeb ? webRootDepth : (WEB_ROOT_CLASS.equals(className) ? depth : -1);
            final boolean capped = childWebRootDepth >= 0 ? depth - childWebRootDepth >= MAX_WEB_DEPTH : depth >= maxDepth;
            if (capped) {
                if (childCount > 0) {
                    depthCapped++;
                    if (inWeb) webDepthCapped++;
                }
                return;
            }
            for (int i = 0; i < childCount; i++) {
                if (stopWindow || stopAll) return;
                final long t0 = measure ? System.nanoTime() : 0L;
                AccessibilityNodeInfo child = prefetchStrategy >= 0 ? node.getChild(i, prefetchStrategy) : node.getChild(i);
                if (measure && System.nanoTime() - t0 >= SLOW_FETCH_NANOS) slowFetches++;
                if (child != null) visit(child, depth + 1, childWebRootDepth, childParentIndex);
            }
        }

        /// Chromium's per-node extras (parceled with the node, no IPC). Defaults on a missing bundle or any failure.
        private void readWebExtras(AccessibilityNodeInfo node) {
            extraRole = 0;
            extraScore = 0;
            extraOffscreen = false;
            try {
                final Bundle extras = node.getExtras();
                if (extras == null || extras.isEmpty()) return;
                extraRole = webRoleId(extras.getCharSequence(EXTRA_CHROME_ROLE));
                extraScore = extras.getInt(EXTRA_CLICKABLE_SCORE, 0);
                extraOffscreen = extras.getBoolean(EXTRA_OFFSCREEN, false);
            } catch (RuntimeException e) {
                extraRole = 0;
                extraScore = 0;
                extraOffscreen = false;
            }
        }

        private void append(AccessibilityNodeInfo node, String className, int flags, int parentIndex, int webRole,
                int clickableScore) {
            if (intCount + INT_STRIDE > ints.length) ints = Arrays.copyOf(ints, ints.length * 2);
            ints[intCount] = bounds.left;
            ints[intCount + 1] = bounds.top;
            ints[intCount + 2] = bounds.right;
            ints[intCount + 3] = bounds.bottom;
            ints[intCount + 4] = flags;
            ints[intCount + 5] = windowType;
            ints[intCount + 6] = parentIndex;
            ints[intCount + 7] = webRole;
            ints[intCount + 8] = clickableScore;
            intCount += INT_STRIDE;
            String viewId = node.getViewIdResourceName();
            strings.add(viewId);
            strings.add(className);
            strings.add(truncateText(node.getText()));
            strings.add(truncateText(node.getContentDescription()));
            // Same id as decodeCompactInteractiveNodes on the Dart side; the first node wins on a duplicate id.
            final String idBase = viewId != null ? viewId : (className != null ? className : "node");
            current.refs.putIfAbsent(idBase + "@" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom, node);
            mix(bounds.left);
            mix(bounds.top);
            mix(bounds.right);
            mix(bounds.bottom);
            mix(flags);
            mix(windowType);
            mix(viewId != null ? viewId.hashCode() : 0);
            mix(className != null ? className.hashCode() : 0);
            mix(parentIndex);
            mix(webRole);
            mix(clickableScore);
            actionable++;
            windowActionable++;
        }

        private void mix(int v) {
            hash = (hash ^ (v & 0xffffffffL)) * FNV_PRIME;
        }

        private static String truncateText(CharSequence cs) {
            if (cs == null) return null;
            if (cs.length() <= MAX_TEXT_LENGTH) return cs.toString();
            int end = MAX_TEXT_LENGTH;
            // Never split a surrogate pair.
            if (Character.isHighSurrogate(cs.charAt(end - 1))) end--;
            return cs.subSequence(0, end).toString();
        }

        HashMap<String, Object> finish(Long lastSignature, long structuralGen, long contentGen) {
            final long walkNanos = System.nanoTime() - startNanos;
            final int walkMs = (int) (walkNanos / 1_000_000L);
            final int[] windowInts = new int[entries.size() * WINDOW_INT_STRIDE];
            int windowsTruncated = 0;
            for (int i = 0; i < entries.size(); i++) {
                final SnapshotWindowEntry e = entries.get(i);
                final boolean windowTruncated = e.reason != SnapNodeStats.TRUNCATED_NONE;
                if (windowTruncated) {
                    windowsTruncated++;
                    truncatedByReason[e.reason]++;
                }
                final int o = i * WINDOW_INT_STRIDE;
                windowInts[o] = e.windowId;
                windowInts[o + 1] = e.windowType;
                windowInts[o + 2] = e.nodeStart;
                windowInts[o + 3] = e.nodeCount;
                windowInts[o + 4] = windowTruncated ? 1 : 0;
                windowInts[o + 5] = e.bounds.left;
                windowInts[o + 6] = e.bounds.top;
                windowInts[o + 7] = e.bounds.right;
                windowInts[o + 8] = e.bounds.bottom;
                // The window breakdown is part of the content: a window that opens/closes without nodes, a root
                // that goes missing or comes back must not be answered as unchanged.
                mix(e.windowId);
                mix(e.windowType);
                mix(e.nodeCount);
                mix(windowTruncated ? 1 : 0);
                mix(e.bounds.left);
                mix(e.bounds.top);
                mix(e.bounds.right);
                mix(e.bounds.bottom);
            }
            mix(actionable);
            final long signature = hash;
            final boolean unchanged = !truncated && lastSignature != null && signature == lastSignature;
            HashMap<String, Object> m = new HashMap<>(20);
            m.put("unchanged", unchanged);
            m.put("signature", signature);
            if (!unchanged) {
                m.put("ints", intCount == ints.length ? ints : Arrays.copyOf(ints, intCount));
                m.put("strings", strings);
                m.put("windowInts", windowInts);
            }
            m.put("structuralGeneration", structuralGen);
            m.put("contentGeneration", contentGen);
            m.put("walkMs", walkMs);
            m.put("windows", windows);
            m.put("visited", visited);
            m.put("actionable", actionable);
            m.put("pruned", pruned);
            m.put("depthCapped", depthCapped);
            m.put("truncated", truncated);
            publishNodeRefs();
            SnapNodeStats.noteSnapshotBudget(walkMs, windows, truncatedByReason);
            if (measure) {
                SnapNodeStats.recordSnapshotWalk(walkNanos, windows, visited, actionable, pruned, depthCapped, unchanged,
                        truncated, prefetchStrategy, slowFetches, windowsTruncated, truncatedByReason);
                if (!unchanged && webVisited > 0) {
                    SnapNodeStats.logWebWalk(webVisited, webTargets, webByClickable, webByRole, webByClass,
                            webOffscreenPruned, webDepthCapped, webRoleCounts);
                }
            }
            return m;
        }

        /// Per-window node refs for refreshInteractiveNode: a complete window's new refs replace its old ones, a
        /// truncated window keeps its old refs plus the new ones (new wins on the same id) because the Dart side
        /// carries that window's last complete nodes; windows no longer listed are dropped. Published as a new map,
        /// never mutated afterwards.
        private void publishNodeRefs() {
            final Map<Integer, Map<String, AccessibilityNodeInfo>> previous = snapshotNodeRefs;
            final LinkedHashMap<Integer, Map<String, AccessibilityNodeInfo>> next = new LinkedHashMap<>(entries.size() * 2);
            for (SnapshotWindowEntry e : entries) {
                Map<String, AccessibilityNodeInfo> refs = e.refs;
                if (e.reason != SnapNodeStats.TRUNCATED_NONE) {
                    final Map<String, AccessibilityNodeInfo> old = previous.get(e.windowId);
                    if (old != null && !old.isEmpty()) {
                        final HashMap<String, AccessibilityNodeInfo> merged = new HashMap<>(old);
                        merged.putAll(e.refs);
                        refs = merged;
                    }
                }
                final Map<String, AccessibilityNodeInfo> existing = next.get(e.windowId);
                if (existing != null) {
                    // Same window id listed twice (should not happen): keep both, first wins.
                    final HashMap<String, AccessibilityNodeInfo> combined = new HashMap<>(refs);
                    combined.putAll(existing);
                    refs = combined;
                }
                if (!refs.isEmpty()) next.put(e.windowId, refs);
            }
            snapshotNodeRefs = next;
        }
    }

    /// One considered window of a snapshot walk.
    private static final class SnapshotWindowEntry {
        final int windowId;
        final int windowType;
        final Rect bounds;
        final int nodeStart;
        int nodeCount;
        int reason = SnapNodeStats.TRUNCATED_NONE;
        final HashMap<String, AccessibilityNodeInfo> refs = new HashMap<>();

        SnapshotWindowEntry(int windowId, int windowType, Rect bounds, int nodeStart) {
            this.windowId = windowId;
            this.windowType = windowType;
            this.bounds = bounds;
            this.nodeStart = nodeStart;
        }
    }

    /// Bumps the change generations for one window event (APPS-112). Runs on the main thread, which on this Flutter
    /// version is also every engine's Dart UI thread: no allocation, no node access - a CharSequence compare against
    /// the cached own package and one int read.
    private static void countNodeChangeEvent(AccessibilityEvent event, int type) {
        final boolean stats = SnapNodeStats.isEnabled();
        if (stats) SnapNodeStats.noteEventDelay(SystemClock.uptimeMillis() - event.getEventTime());
        final String own = ownPackageName;
        final CharSequence pkg = event.getPackageName();
        if (own != null && pkg != null && own.contentEquals(pkg)) {
            if (stats) SnapNodeStats.eventsOwn++;
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            final int changeTypes = event.getContentChangeTypes();
            if (changeTypes != 0 && (changeTypes & ~TEXT_ONLY_CONTENT_CHANGE_MASK) == 0) {
                // Text / content description / state description only (video time labels, counters): no node
                // appears, disappears or moves, so the snap targets are unchanged.
                if (stats) SnapNodeStats.eventsTextOnly++;
                return;
            }
            contentGeneration.incrementAndGet();
            if (stats) {
                SnapNodeStats.eventsContent++;
                SnapNodeStats.noteContentChangeTypes(changeTypes);
            }
        } else {
            structuralGeneration.incrementAndGet();
            if (stats) SnapNodeStats.eventsStructural++;
        }
        scheduleNodeChangePush();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        // Lightweight "window changed" signal for the gaze snap-to-item feature.
        // Do NOT serialize the tree or broadcast here — just flag the node list as stale so
        // the Dart snap controller can refetch early. Full tree streaming stays disabled below.
        if (accessibilityEvent != null) {
            final int type = accessibilityEvent.getEventType();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                nodesDirty = true;
                countNodeChangeEvent(accessibilityEvent, type);
            }
        }
        // try {
        //     if (accessibilityEvent == null) {
        //         return;
        //     }

        //     final int eventType = accessibilityEvent.getEventType();
        //     AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();

        //     // Add comprehensive null checks
        //     if (parentNodeInfo == null) {
        //         return;
        //     }

        //     // Check if package name is valid first (before checking hierarchy)
        //     CharSequence packageNameSeq = parentNodeInfo.getPackageName();
        //     if (packageNameSeq == null) {
        //         Log.w("AccessibilityListener", "Package name is null, skipping event");
        //         parentNodeInfo.recycle();
        //         return;
        //     }

        //     // Check if the node is still valid and attached to a window
        //     AccessibilityWindowInfo windowInfo = null;
        //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        //         windowInfo = parentNodeInfo.getWindow();
        //     }

        //     // Skip events from detached views (likely in transition)
        //     if (windowInfo == null && parentNodeInfo.getParent() == null) {
        //         Log.w("AccessibilityListener", "Node has no parent or window, skipping event");
        //         parentNodeInfo.recycle();
        //         return;
        //     }

        //     List<String> nextTexts = new ArrayList<>();
        //     List<Integer> actions = new ArrayList<>();
        //     List<HashMap<String, Object>> subNodeActions = new ArrayList<>();
        //     HashSet<AccessibilityNodeInfo> traversedNodes = new HashSet<>();
        //     HashMap<String, Object> data = new HashMap<>();
        //     String nodeId = generateNodeId(parentNodeInfo);

        //     String packageName = packageNameSeq.toString();

        //     storeNode(nodeId, parentNodeInfo);


        //     Intent intent = new Intent(ACCESSIBILITY_INTENT);

        //     data.put("mapId", nodeId);
        //     data.put("packageName", packageName);
        //     data.put("eventType", eventType);
        //     data.put("actionType", accessibilityEvent.getAction());
        //     data.put("eventTime", accessibilityEvent.getEventTime());
        //     data.put("movementGranularity", accessibilityEvent.getMovementGranularity());
        //     Rect rect = new Rect();
        //     parentNodeInfo.getBoundsInScreen(rect);
        //     data.put("screenBounds", getBoundingPoints(rect));
        //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        //         data.put("contentChangeTypes", accessibilityEvent.getContentChangeTypes());
        //     }
        //     if (parentNodeInfo.getText() != null) {
        //         data.put("capturedText", parentNodeInfo.getText().toString());
        //     }
        //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        //         data.put("nodeId", parentNodeInfo.getViewIdResourceName());
        //     }
        //     getSubNodes(parentNodeInfo, subNodeActions, traversedNodes, 0);
        //     data.put("nodesText", nextTexts);
        //     actions.addAll(parentNodeInfo.getActionList().stream().map(AccessibilityNodeInfo.AccessibilityAction::getId).collect(Collectors.toList()));
        //     data.put("parentActions", actions);
        //     data.put("subNodesActions", subNodeActions);
        //     data.put("isClickable", parentNodeInfo.isClickable());
        //     data.put("isScrollable", parentNodeInfo.isScrollable());
        //     data.put("isFocusable", parentNodeInfo.isFocusable());
        //     data.put("isCheckable", parentNodeInfo.isCheckable());
        //     data.put("isLongClickable", parentNodeInfo.isLongClickable());
        //     data.put("isEditable", parentNodeInfo.isEditable());
        //     if (windowInfo != null) {
        //         data.put("isActive", windowInfo.isActive());
        //         data.put("isFocused", windowInfo.isFocused());
        //         data.put("windowType", windowInfo.getType());
        //         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        //             data.put("isPip", windowInfo.isInPictureInPictureMode());
        //         }
        //     }
        //     storeToSharedPrefs(data);
        //     intent.putExtra(SEND_BROADCAST, true);
        //     sendBroadcast(intent);
        // } catch (NullPointerException ex) {
        //     // Handle ViewParent null pointer exceptions specifically
        //     if (ex.getMessage() != null && ex.getMessage().contains("ViewParent")) {
        //         Log.w("EVENT", "ViewParent null pointer exception caught - Flutter view may be detaching: " + ex.getMessage());
        //     } else {
        //         Log.e("EVENT", "Null pointer exception in accessibility event: " + ex.getMessage());
        //     }
        // } catch (IllegalStateException ex) {
        //     // Handle cases where accessibility nodes become invalid
        //     Log.w("EVENT", "Accessibility node invalid state: " + ex.getMessage());
        // } catch (Exception ex) {
        //     Log.e("EVENT", "onAccessibilityEvent: " + ex.getMessage());
        // }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Null on START_STICKY restarts after a process kill (the service is
        // also "started", not only bound, once the first click command arrives
        // via startService).
        if (intent == null) {
            return START_STICKY;
        }
        boolean globalAction = intent.getBooleanExtra(INTENT_GLOBAL_ACTION, false);
        boolean systemActions = intent.getBooleanExtra(INTENT_SYSTEM_GLOBAL_ACTIONS, false);
        boolean click = intent.getBooleanExtra(INTENT_CLICK, false);
        boolean longPress = intent.getBooleanExtra(INTENT_LONG_PRESS, false);
        boolean scroll = intent.getBooleanExtra(INTENT_SCROLL, false);
        if (systemActions && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            List<Integer> actions = getSystemActions().stream().map(AccessibilityNodeInfo.AccessibilityAction::getId).collect(Collectors.toList());
            Intent broadcastIntent = new Intent(BROD_SYSTEM_GLOBAL_ACTIONS);
            broadcastIntent.putIntegerArrayListExtra("actions", new ArrayList<>(actions));
            sendBroadcast(broadcastIntent);
        }
        if (globalAction) {
            int actionId = intent.getIntExtra(INTENT_GLOBAL_ACTION_ID, 8);
            performGlobalAction(actionId);
        }
        if (click) {
            double x = intent.getDoubleExtra(INTENT_CLICK_X, 0);
            double y = intent.getDoubleExtra(INTENT_CLICK_Y, 0);
            click((float) x, (float) y);
        }
        if (longPress) {
            double x = intent.getDoubleExtra(INTENT_LONG_PRESS_X, 0);
            double y = intent.getDoubleExtra(INTENT_LONG_PRESS_Y, 0);
            longPress((float) x, (float) y);
        }
        if (scroll) {
            double x = intent.getDoubleExtra(INTENT_SCROLL_X, 0);
            double y = intent.getDoubleExtra(INTENT_SCROLL_Y, 0);
            double deltaX = intent.getDoubleExtra(INTENT_SCROLL_DELTA_X, 0);
            double deltaY = intent.getDoubleExtra(INTENT_SCROLL_DELTA_Y, 0);
            scroll((float) x, (float) y, (float) deltaX, (float) deltaY);
        }
        Log.d("CMD_STARTED", "onStartCommand: " + startId);
        return START_STICKY;
    }


    @RequiresApi(api = Build.VERSION_CODES.N)
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void getSubNodes(AccessibilityNodeInfo node, List<HashMap<String, Object>> arr, HashSet<AccessibilityNodeInfo> traversedNodes, int currentDepth) {
        // if (currentDepth >= maximumTreeDepth || node == null) {
        //     if (currentDepth >= maximumTreeDepth) {
        //         Log.d("TREE_DEPTH", "Maximum tree depth reached: " + currentDepth);
        //     }
        //     return;
        // }
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        //     if (traversedNodes.contains(node)) return;
        //     traversedNodes.add(node);
        //     String mapId = generateNodeId(node);
        //     AccessibilityWindowInfo windowInfo = null;
        //     HashMap<String, Object> nested = new HashMap<>();
        //     Rect rect = new Rect();
        //     node.getBoundsInScreen(rect);
        //     windowInfo = node.getWindow();
        //     nested.put("mapId", mapId);
        //     nested.put("nodeId", node.getViewIdResourceName());
        //     nested.put("capturedText", node.getText());
        //     nested.put("screenBounds", getBoundingPoints(rect));
        //     nested.put("isClickable", node.isClickable());
        //     nested.put("isScrollable", node.isScrollable());
        //     nested.put("isFocusable", node.isFocusable());
        //     nested.put("isCheckable", node.isCheckable());
        //     nested.put("isLongClickable", node.isLongClickable());
        //     nested.put("isEditable", node.isEditable());
        //     nested.put("parentActions", node.getActionList().stream().map(AccessibilityNodeInfo.AccessibilityAction::getId).collect(Collectors.toList()));
        //     if (windowInfo != null) {
        //         nested.put("isActive", node.getWindow().isActive());
        //         nested.put("isFocused", node.getWindow().isFocused());
        //         nested.put("windowType", node.getWindow().getType());
        //     }
        //     arr.add(nested);
        //     storeNode(mapId, node);
        //     for (int i = 0; i < node.getChildCount(); i++) {
        //         AccessibilityNodeInfo child = node.getChild(i);
        //         if (child == null)
        //             continue;
        //         getSubNodes(child, arr, traversedNodes, currentDepth + 1);
        //     }
        // }
    }

    private static HashMap<String, Integer> getBoundingPoints(Rect rect) {
        HashMap<String, Integer> frame = new HashMap<>();
        frame.put("left", rect.left);
        frame.put("right", rect.right);
        frame.put("top", rect.top);
        frame.put("bottom", rect.bottom);
        frame.put("width", rect.width());
        frame.put("height", rect.height());
        return frame;
    }


    /**
     * The bound service instance, or null when the service is not connected.
     * Distinct from the settings toggle: Settings.Secure flips before
     * onServiceConnected has run, and stays on if the service crashes.
     */
    public static AccessibilityListener getServiceInstance() {
        return serviceInstance;
    }

    public static boolean isServiceConnected() {
        return serviceInstance != null;
    }

    private void broadcastServiceConnection(boolean connected) {
        try {
            Intent intent = new Intent(BROD_SERVICE_CONNECTION);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_SERVICE_CONNECTED, connected);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.w("AccessibilityListener", "Failed to broadcast service connection state: " + e.getMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    @Override
    protected void onServiceConnected() {
        try {
            ownPackageName = getPackageName();
            SnapNodeStats.configure(this);
            serviceInstance = this;
            // Listeners learn about the (re)connect without waiting for the next tree event.
            scheduleNodeChangePush();
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            // Create texture view and disable accessibility on it first
            FlutterTextureView textureView = new FlutterTextureView(getApplicationContext());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                textureView.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }

            mOverlayView = new FlutterView(getApplicationContext(), textureView);

            // Disable accessibility on the FlutterView too
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                mOverlayView.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mOverlayView.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_NONE);
            }

            // Initialize Flutter engine group for multiple overlays
            engineGroup = new FlutterEngineGroup(getApplicationContext());

            // DON'T attach to engine here - only attach when overlay is shown
            // This prevents AccessibilityBridge NPE when view has no parent
            FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
            if (cachedEngine != null) {
                Log.d("AccessibilityListener", "Found cached FlutterEngine, will attach when overlay is shown");
                // Keep engine inactive until overlay is shown
                try {
                    cachedEngine.getLifecycleChannel().appIsInactive();
                } catch (Exception e) {
                    Log.w("AccessibilityListener", "Error setting engine inactive: " + e.getMessage());
                }
            } else {
                Log.w("AccessibilityListener", "Cached FlutterEngine not found - overlay functionality may be limited");
            }

            mOverlayView.setFitsSystemWindows(true);
            mOverlayView.setFocusable(true);
            mOverlayView.setFocusableInTouchMode(true);
            mOverlayView.setBackgroundColor(Color.TRANSPARENT);

            Log.d("AccessibilityListener", "AccessibilityService connected successfully");
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error in onServiceConnected: " + e.getMessage(), e);
            // Don't rethrow - allow the service to continue running
        }
        broadcastServiceConnection(true);
        // Notify the host app that the service (incl. WindowManager + engine
        // group) is up. The host uses this to start the process-wide USB/EAP
        // transport and to spawn the overlay engines - eye control recovers
        // from boot / process kills without the main activity ever launching.
        // (The former relaunch-the-app self-heal is gone: the overlays are
        // self-sufficient and the main app is just a settings UI.)
        new Handler(Looper.getMainLooper()).post(() -> safeNotifyServiceReady(this));
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    static public void showOverlay(int width, int height, int gravity, boolean clickableThrough) {
        if (!isOverlayShown && mWindowManager != null && mOverlayView != null) {
            try {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                lp.format = PixelFormat.TRANSLUCENT;
                lp.width = width;
                lp.height = height;
                if (!clickableThrough) {
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                } else {
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                }
                lp.gravity = gravity;
                
                // Add view to window FIRST
                mWindowManager.addView(mOverlayView, lp);
                isOverlayShown = true;
                
                // NOW attach to engine and resume - view has a valid parent
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
                        if (cachedEngine != null && mOverlayView != null && mOverlayView.getParent() != null) {
                            // Attach view to engine only now that it has a parent
                            if (mOverlayView.getAttachedFlutterEngine() != cachedEngine) {
                                mOverlayView.attachToFlutterEngine(cachedEngine);
                            }
                            cachedEngine.getLifecycleChannel().appIsResumed();
                            Log.d("AccessibilityListener", "Legacy overlay: view attached to engine and resumed");
                        }
                    } catch (Exception e) {
                        Log.w("AccessibilityListener", "Error attaching/resuming engine after show: " + e.getMessage());
                    }
                }, 50);
                
                Log.d("AccessibilityListener", "Overlay shown successfully");
            } catch (Exception e) {
                Log.e("AccessibilityListener", "Error showing overlay: " + e.getMessage(), e);
            }
        } else {
            Log.w("AccessibilityListener", "Cannot show overlay - service not properly initialized or overlay already shown");
        }
    }

    static public void removeOverlay() {
        if (isOverlayShown && mWindowManager != null && mOverlayView != null) {
            try {
                // Pause the engine before detaching to stop accessibility events
                try {
                    FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
                    if (cachedEngine != null) {
                        cachedEngine.getLifecycleChannel().appIsInactive();
                        cachedEngine.getLifecycleChannel().appIsPaused();
                    }
                } catch (Exception pauseE) {
                    Log.w("AccessibilityListener", "Error pausing engine: " + pauseE.getMessage());
                }
                
                // Detach from engine before removing view to prevent AccessibilityBridge NPE
                try {
                    mOverlayView.detachFromFlutterEngine();
                } catch (Exception detachE) {
                    Log.w("AccessibilityListener", "Error detaching overlay from engine: " + detachE.getMessage());
                }
                mWindowManager.removeView(mOverlayView);
                isOverlayShown = false;
                Log.d("AccessibilityListener", "Overlay removed successfully");
            } catch (Exception e) {
                Log.e("AccessibilityListener", "Error removing overlay: " + e.getMessage(), e);
                isOverlayShown = false; // Reset flag even if removal failed
            }
        }
    }
    
    /**
     * Detach the legacy overlay view from its FlutterEngine.
     * Called during hot restart to prevent AccessibilityBridge crashes.
     */
    static public void detachLegacyOverlayFromEngine() {
        // First, pause the cached engine to stop any accessibility events
        try {
            FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
            if (cachedEngine != null) {
                Log.d("AccessibilityListener", "Pausing cached engine before detach");
                cachedEngine.getLifecycleChannel().appIsInactive();
                cachedEngine.getLifecycleChannel().appIsPaused();
            }
        } catch (Exception e) {
            Log.w("AccessibilityListener", "Error pausing cached engine: " + e.getMessage());
        }
        
        // Then detach the view
        if (mOverlayView != null) {
            try {
                Log.d("AccessibilityListener", "Detaching legacy overlay from engine");
                mOverlayView.detachFromFlutterEngine();
            } catch (Exception e) {
                Log.w("AccessibilityListener", "Error detaching legacy overlay: " + e.getMessage());
            }
        }
    }
    
    /**
     * Re-attach the legacy overlay view to the current cached FlutterEngine.
     * Called after hot restart to restore functionality.
     */
    static public void reattachLegacyOverlayToEngine() {
        FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
        if (cachedEngine != null) {
            try {
                // ONLY attach if overlay is visible and has a parent
                // This prevents AccessibilityBridge NPE
                if (isOverlayShown && mOverlayView != null && mOverlayView.getParent() != null) {
                    Log.d("AccessibilityListener", "Re-attaching legacy overlay to new engine (visible)");
                    mOverlayView.attachToFlutterEngine(cachedEngine);
                    
                    // CRITICAL: Delay resuming until view hierarchy is stable
                    // Resume triggers semantics updates which crash if view has no parent
                    final FlutterEngine engineRef = cachedEngine;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            // Double-check parent is still valid before resuming
                            if (mOverlayView != null && mOverlayView.getParent() != null && isOverlayShown) {
                                engineRef.getLifecycleChannel().appIsResumed();
                                Log.d("AccessibilityListener", "Legacy overlay engine resumed after delay");
                            } else {
                                Log.w("AccessibilityListener", "Legacy overlay parent became null, keeping engine paused");
                                engineRef.getLifecycleChannel().appIsInactive();
                            }
                        } catch (Exception e) {
                            Log.w("AccessibilityListener", "Error resuming legacy overlay engine: " + e.getMessage());
                        }
                    }, 100);
                } else {
                    // Keep engine inactive - don't attach view until it's shown
                    cachedEngine.getLifecycleChannel().appIsInactive();
                    Log.d("AccessibilityListener", "Legacy overlay not visible, engine kept inactive (no view attached)");
                }
            } catch (Exception e) {
                Log.w("AccessibilityListener", "Error re-attaching legacy overlay: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
        removeAllOverlays();
        serviceInstance = null;
        clearSnapshotNodeRefs();
        scheduleNodeChangePush();
        // Null the remaining statics too: a stale WindowManager from a dead service
        // would make the next createOverlay attempt fail with a BadTokenException
        // instead of a clean "service not connected".
        mWindowManager = null;
        mOverlayView = null;
        engineGroup = null;
        isOverlayShown = false;
        SharedPreferences sharedPreferences = getSharedPreferences(SHARED_PREFS_TAG, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(ACCESSIBILITY_NODE).apply();
        broadcastServiceConnection(false);
    }

    @Override
    public void onInterrupt() {
    }


    private String generateNodeId(AccessibilityNodeInfo node) {
        return node.getWindowId() + "_" + node.getClassName() + "_" + node.getText() + "_" + node.getContentDescription(); //UUID.randomUUID().toString();
    }

    private void storeNode(String uuid, AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        nodeMap.put(uuid, node);
    }

    void storeToSharedPrefs(HashMap<String, Object> data) {
        SharedPreferences sharedPreferences = getSharedPreferences(SHARED_PREFS_TAG, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        Gson gson = new Gson();
        String json = gson.toJson(data);
        editor.putString(ACCESSIBILITY_NODE, json);
        editor.apply();
    }

    // ============================================================================
    // Multiple Overlay Management
    // ============================================================================

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    public static Integer createOverlay(Context context, int id, Map<String, Object> options, String entrypoint) {
        if (mWindowManager == null) {
            Log.e("AccessibilityListener", "WindowManager not initialized");
            return null;
        }
        
        // Check for duplicate IDs
        if (activeOverlays.containsKey(id)) {
            Log.e("AccessibilityListener", "Overlay with ID " + id + " already exists");
            return null;
        }

        try {
            AccessibilityOverlay overlay = new AccessibilityOverlay(context, mWindowManager, id);
            
            // Apply options
            if (options != null) {
                applyOverlayOptions(overlay, options);
            }
            
            // Create and attach Flutter engine if entrypoint is provided
            if (entrypoint != null && !entrypoint.isEmpty() && engineGroup != null) {
                FlutterEngine engine = createEngineForOverlay(context, entrypoint);
                if (engine != null) {
                    overlay.attachFlutterEngine(engine, entrypoint);
                }
            }
            
            activeOverlays.put(overlay.getOverlayId(), overlay);
            Log.d("AccessibilityListener", "Created overlay: " + overlay.getOverlayId() + " - activeOverlays.size() now: " + activeOverlays.size());
            return overlay.getOverlayId();
            
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error creating overlay: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Hook allowing the host app to register a narrow subset of Flutter plugins
     * on overlay engines instead of the full GeneratedPluginRegistrant set.
     * When set, {@link #createEngineForOverlay} disables automatic plugin
     * registration and invokes {@link OverlayEngineConfigurator#configure}
     * after each engine is spawned.
     */
    public interface OverlayEngineConfigurator {
        void configure(FlutterEngine engine, String entrypoint);
    }

    private static volatile OverlayEngineConfigurator overlayEngineConfigurator;

    public static void setOverlayEngineConfigurator(OverlayEngineConfigurator configurator) {
        overlayEngineConfigurator = configurator;
    }

    /**
     * Notified on the main thread once the service is connected and the overlay
     * spawn machinery (WindowManager + FlutterEngineGroup) is initialized. The
     * host app uses this to bring up process-wide infrastructure that must not
     * depend on any activity (USB/EAP transport, later overlay spawning).
     * Set it from Application.onCreate; if the service is already connected
     * when the listener is set, it fires immediately (posted to the main looper).
     */
    public interface ServiceReadyListener {
        void onServiceReady(Context serviceContext);
    }

    private static volatile ServiceReadyListener serviceReadyListener;

    public static void setServiceReadyListener(ServiceReadyListener listener) {
        serviceReadyListener = listener;
        AccessibilityListener service = serviceInstance;
        if (listener != null && service != null) {
            new Handler(Looper.getMainLooper()).post(() -> safeNotifyServiceReady(service));
        }
    }

    private static void safeNotifyServiceReady(AccessibilityListener service) {
        ServiceReadyListener listener = serviceReadyListener;
        if (listener == null || service == null) return;
        try {
            listener.onServiceReady(service.getApplicationContext());
        } catch (Exception e) {
            Log.e("AccessibilityListener", "ServiceReadyListener threw: " + e.getMessage(), e);
        }
    }

    public static FlutterEngine createEngineForOverlay(Context context, String entrypoint) {
        try {
            DartExecutor.DartEntrypoint dartEntrypoint = new DartExecutor.DartEntrypoint(
                FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                entrypoint
            );
            OverlayEngineConfigurator configurator = overlayEngineConfigurator;
            if (configurator != null) {
                FlutterEngineGroup.Options options = new FlutterEngineGroup.Options(context)
                    .setDartEntrypoint(dartEntrypoint)
                    .setAutomaticallyRegisterPlugins(false);
                FlutterEngine engine = engineGroup.createAndRunEngine(options);
                configurator.configure(engine, entrypoint);
                return engine;
            }
            return engineGroup.createAndRunEngine(context, dartEntrypoint);
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error creating Flutter engine: " + e.getMessage(), e);
            return null;
        }
    }

    private static void applyOverlayOptions(AccessibilityOverlay overlay, Map<String, Object> options) {
        if (options == null) return;
        
        try {
            if (options.containsKey("width") && options.containsKey("height")) {
                Integer width = safeParseInt(options.get("width"));
                Integer height = safeParseInt(options.get("height"));
                if (width != null && height != null) {
                    overlay.updateSize(width, height);
                }
            }
            
            if (options.containsKey("x") && options.containsKey("y")) {
                Integer x = safeParseInt(options.get("x"));
                Integer y = safeParseInt(options.get("y"));
                if (x != null && y != null) {
                    overlay.updatePosition(x, y);
                }
            }
            
            if (options.containsKey("flags")) {
                Integer flags = safeParseInt(options.get("flags"));
                if (flags != null) {
                    overlay.updateFlags(flags);
                    // Check if movable flag is set (bit 2 = movable)
                    overlay.setMovable((flags & 2) != 0);
                    // Check if resizable flag is set (bit 4 = resizable)
                    overlay.setResizable((flags & 4) != 0);
                }
            }
            
            if (options.containsKey("gravity")) {
                Integer gravity = safeParseInt(options.get("gravity"));
                if (gravity != null) {
                    overlay.updateGravity(gravity);
                }
            }
            
            // Configurable overlay behavior flags
            if (options.containsKey("touchable")) {
                Boolean touchable = safeParseBoolean(options.get("touchable"));
                if (touchable != null) {
                    overlay.setTouchable(touchable);
                }
            }
            
            if (options.containsKey("focusable")) {
                Boolean focusable = safeParseBoolean(options.get("focusable"));
                if (focusable != null) {
                    overlay.setFocusable(focusable);
                }
            }
            
            if (options.containsKey("expandOutsideLayout")) {
                Boolean expandOutside = safeParseBoolean(options.get("expandOutsideLayout"));
                if (expandOutside != null) {
                    overlay.setExpandOutsideLayout(expandOutside);
                }
            }
            
            if (options.containsKey("watchOutsideTouch")) {
                Boolean watchOutside = safeParseBoolean(options.get("watchOutsideTouch"));
                if (watchOutside != null) {
                    overlay.setWatchOutsideTouch(watchOutside);
                }
            }
            
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error applying overlay options: " + e.getMessage(), e);
        }
    }
    
    private static Integer safeParseInt(Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof Integer) {
            return (Integer) value;
        }
        
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                Log.w("AccessibilityListener", "Cannot parse string to integer: " + value);
                return null;
            }
        }
        
        if (value instanceof Double) {
            return ((Double) value).intValue();
        }
        
        if (value instanceof Float) {
            return ((Float) value).intValue();
        }
        
        if (value instanceof Long) {
            return ((Long) value).intValue();
        }
        
        Log.w("AccessibilityListener", "Cannot convert value to integer, unsupported type: " + value.getClass().getSimpleName());
        return null;
    }
    
    private static Boolean safeParseBoolean(Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        
        Log.w("AccessibilityListener", "Cannot convert value to boolean, unsupported type: " + value.getClass().getSimpleName());
        return null;
    }
    
    private static int convertGravity(int overlayGravity) {
        // Convert overlay gravity values to Android Gravity constants
        // These values come from OverlayGravity enum in Dart
        switch (overlayGravity) {
            case 48: return Gravity.TOP; // top
            case 80: return Gravity.BOTTOM; // bottom
            case 3: return Gravity.LEFT; // left
            case 5: return Gravity.RIGHT; // right
            case 51: return Gravity.TOP | Gravity.LEFT; // topLeft (48 | 3)
            case 53: return Gravity.TOP | Gravity.RIGHT; // topRight (48 | 5)
            case 83: return Gravity.BOTTOM | Gravity.LEFT; // bottomLeft (80 | 3)
            case 85: return Gravity.BOTTOM | Gravity.RIGHT; // bottomRight (80 | 5)
            default: return Gravity.TOP; // default to top
        }
    }
    

    public static boolean showOverlayById(int overlayId) {
        try {
            AccessibilityOverlay overlay = activeOverlays.get(overlayId);
            if (overlay != null) {
                boolean result = overlay.show();
                return result;
            }
            return false;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error showing overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean hideOverlayById(int overlayId) {
        try {
            AccessibilityOverlay overlay = activeOverlays.get(overlayId);
            if (overlay != null) {
                boolean result = overlay.hide();
                return result;
            }
            return false;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error hiding overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean removeOverlayById(int overlayId) {
        try {
            AccessibilityOverlay overlay = activeOverlays.remove(overlayId);
            if (overlay != null) {
                overlay.destroy();
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error removing overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean moveOverlayById(int overlayId, int x, int y) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            return overlay.updatePosition(x, y);
        }
        return false;
    }

    public static boolean resizeOverlayById(int overlayId, int width, int height) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            return overlay.updateSize(width, height);
        }
        return false;
    }

    public static boolean updateOverlayOptionsById(int overlayId, Map<String, Object> options) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            applyOverlayOptions(overlay, options);
            return true;
        }
        return false;
    }

    public static Map<String, Object> getOverlayInfo(int overlayId) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            return overlay.getInfo();
        }
        return null;
    }

    public static List<Map<String, Object>> getAllOverlaysInfo() {
        // Log.d("AccessibilityListener", "getAllOverlaysInfo called - activeOverlays.size(): " + activeOverlays.size());
        List<Map<String, Object>> overlaysInfo = new ArrayList<>();
        for (AccessibilityOverlay overlay : activeOverlays.values()) {
            Map<String, Object> info = overlay.getInfo();
            //Log.d("AccessibilityListener", "Overlay info: " + overlay.getOverlayId() + ", visible: " + overlay.isVisible());
            overlaysInfo.add(info);
        }
        // Log.d("AccessibilityListener", "Returning " + overlaysInfo.size() + " overlay infos");
        return overlaysInfo;
    }

    public static void removeAllOverlays() {
        for (AccessibilityOverlay overlay : activeOverlays.values()) {
            overlay.destroy();
        }
        activeOverlays.clear();
        Log.d("AccessibilityListener", "All overlays removed");
    }

    /**
     * Hide only the overlays hosted by the given engine messenger. Called from
     * the plugin's onDetachedFromEngine so a dying engine's FlutterView is
     * detached (AccessibilityBridge NPE protection) WITHOUT sweeping away the
     * other engines' overlays - the main engine's death must leave the menu
     * and pointer overlays running.
     */
    public static void hideOverlaysForMessenger(io.flutter.plugin.common.BinaryMessenger messenger) {
        for (AccessibilityOverlay overlay : activeOverlays.values()) {
            if (overlay.isHostedByMessenger(messenger)) {
                try {
                    hideOverlayById(overlay.getOverlayId());
                } catch (Exception e) {
                    Log.w("AccessibilityListener", "Error hiding overlay " + overlay.getOverlayId() + " during engine detach: " + e.getMessage());
                }
            }
        }
    }

    public static boolean refreshEngineById(int overlayId) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            Log.d("AccessibilityListener", "Refreshing engine for overlay: " + overlayId);
            return overlay.refreshEngine();
        }
        Log.w("AccessibilityListener", "Overlay not found for refresh: " + overlayId);
        return false;
    }

    public static boolean refreshAllEngines() {
        Log.d("AccessibilityListener", "Refreshing all overlay engines");
        boolean allSuccess = true;
        
        for (AccessibilityOverlay overlay : activeOverlays.values()) {
            boolean success = overlay.refreshEngine();
            if (!success) {
                allSuccess = false;
                Log.w("AccessibilityListener", "Failed to refresh engine for overlay: " + overlay.getOverlayId());
            }
        }
        
        Log.d("AccessibilityListener", "Refresh all engines completed. Success: " + allSuccess);
        return allSuccess;
    }

    /**
     * Creates an unblock click stroke for gesture unblocking
     */
    private GestureDescription.StrokeDescription createUnblockClickStroke(float posX, float posY) {
        Path path = new Path();
        path.moveTo(posX, posY);
        return new GestureDescription.StrokeDescription(path, 0, 1);
    }
    
    /**
     * Builds an unblock gesture using multiple quick strokes
     */
    private GestureDescription buildUnblockGesture() {
        return new GestureDescription.Builder()
            .addStroke(createUnblockClickStroke(1f, 1f))
            .addStroke(createUnblockClickStroke(1f, 3f))
            .addStroke(createUnblockClickStroke(2f, 2f))
            .build();
    }

    /**
     * Returns true when the gesture was accepted for dispatch (or the node-action
     * fallback succeeded), false when it was rejected outright. Completion is not
     * awaited - a cancelled gesture still triggers the node-action fallback
     * asynchronously via the GestureResultCallback.
     */
    public boolean click(float x, float y) {
        try {
            // Validate coordinates
            if (x < 0 || y < 0) {
                Log.w("AccessibilityListener", "Invalid click coordinates: x=" + x + ", y=" + y);
                return false;
            }

            // Check if service is connected
            if (serviceInstance == null) {
                Log.e("AccessibilityListener", "Service not connected - cannot perform click");
                return false;
            }

            Log.d("AccessibilityListener", "Performing direct click at (" + x + ", " + y + ")");

            // Create a simple, direct click gesture without unblock gestures
            Path clickPath = new Path();
            clickPath.moveTo(x, y);

            // Create a standard tap gesture (ViewConfiguration.getTapTimeout() is ~100ms)
            GestureDescription.StrokeDescription clickStroke = new GestureDescription.StrokeDescription(clickPath, 0, 100);
            GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
            clickBuilder.addStroke(clickStroke);

            // Dispatch the gesture with simple callback handling. A real touch is the most
            // faithful emulation of a finger tap and works for web content, soft keyboards and
            // normal UI. Only when the gesture is rejected (FLAG_SECURE / system surfaces that
            // refuse synthesized touch) do we fall back to a node-level ACTION_CLICK.
            boolean success = dispatchGesture(clickBuilder.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d("AccessibilityListener", "Click completed successfully at (" + x + ", " + y + ")");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.w("AccessibilityListener", "Click gesture was cancelled at (" + x + ", " + y + ") - falling back to node ACTION_CLICK");
                    performActionAtPoint((int) x, (int) y, AccessibilityNodeInfo.ACTION_CLICK);
                }
            }, null);

            if (!success) {
                Log.w("AccessibilityListener", "Failed to dispatch click gesture at (" + x + ", " + y + ") - falling back to node ACTION_CLICK");
                return performActionAtPoint((int) x, (int) y, AccessibilityNodeInfo.ACTION_CLICK);
            }
            return true;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error performing click at (" + x + ", " + y + ")", e);
            return false;
        }
    }

    /** Same result semantics as {@link #click(float, float)}. */
    public boolean longPress(float x, float y) {
        try {
            // Validate coordinates
            if (x < 0 || y < 0) {
                Log.w("AccessibilityListener", "Invalid long press coordinates: x=" + x + ", y=" + y);
                return false;
            }

            // Check if service is connected
            if (serviceInstance == null) {
                Log.e("AccessibilityListener", "Service not connected - cannot perform long press");
                return false;
            }

            Log.d("AccessibilityListener", "Performing long press at (" + x + ", " + y + ")");

            // Create a long press gesture
            Path longPressPath = new Path();
            longPressPath.moveTo(x, y);

            // Android long press threshold is typically 500ms (ViewConfiguration.getLongPressTimeout())
            GestureDescription.StrokeDescription longPressStroke = new GestureDescription.StrokeDescription(longPressPath, 0, 500);
            GestureDescription.Builder longPressBuilder = new GestureDescription.Builder();
            longPressBuilder.addStroke(longPressStroke);

            // Dispatch the gesture
            boolean success = dispatchGesture(longPressBuilder.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d("AccessibilityListener", "Long press completed successfully at (" + x + ", " + y + ")");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.w("AccessibilityListener", "Long press gesture was cancelled at (" + x + ", " + y + ") - falling back to node ACTION_LONG_CLICK");
                    performActionAtPoint((int) x, (int) y, AccessibilityNodeInfo.ACTION_LONG_CLICK);
                }
            }, null);

            if (!success) {
                Log.w("AccessibilityListener", "Failed to dispatch long press gesture at (" + x + ", " + y + ") - falling back to node ACTION_LONG_CLICK");
                return performActionAtPoint((int) x, (int) y, AccessibilityNodeInfo.ACTION_LONG_CLICK);
            }
            return true;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error performing long press at (" + x + ", " + y + ")", e);
            return false;
        }
    }

    /** Returns true when the scroll gesture was accepted for dispatch. */
    public boolean scroll(float x, float y, float deltaX, float deltaY) {
        try {
            // Validate coordinates
            if (x < 0 || y < 0) {
                Log.w("AccessibilityListener", "Invalid scroll coordinates: x=" + x + ", y=" + y);
                return false;
            }

            // Check if service is connected
            if (serviceInstance == null) {
                Log.e("AccessibilityListener", "Service not connected - cannot perform scroll");
                return false;
            }

            Log.d("AccessibilityListener", "Performing scroll at (" + x + ", " + y + ") with delta (" + deltaX + ", " + deltaY + ")");

            // Create scroll gesture path
            Path scrollPath = new Path();
            scrollPath.moveTo(x, y);
            scrollPath.lineTo(x + deltaX, y + deltaY);

            // Very short duration for rapid scroll events (50ms for responsiveness)
            GestureDescription.StrokeDescription scrollStroke = new GestureDescription.StrokeDescription(scrollPath, 0, 50);
            GestureDescription.Builder scrollBuilder = new GestureDescription.Builder();
            scrollBuilder.addStroke(scrollStroke);

            // Dispatch without callback for performance
            boolean success = dispatchGesture(scrollBuilder.build(), null, null);

            if (!success) {
                Log.w("AccessibilityListener", "Failed to dispatch scroll gesture at (" + x + ", " + y + ")");
            }
            return success;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error performing scroll at (" + x + ", " + y + ")", e);
            return false;
        }
    }

    /**
     * Completion callback for the two-point gesture APIs ({@link #swipe} / {@link #dragAndDrop}).
     * Reports whether the gesture completed, not merely whether it was accepted.
     */
    public interface GestureCompletion {
        void onResult(boolean completed);
    }

    /**
     * Extra hold time added on top of the system long press timeout before a drag
     * starts moving. The timeout itself follows the user's "Touch and hold delay"
     * setting, so it must never be replaced by a hardcoded value.
     */
    private static final long LONG_PRESS_MARGIN_MS = 600;

    /**
     * One continuous stroke from (x1, y1) to (x2, y2) over durationMs.
     * Unlike {@link #scroll} this reports completion asynchronously through cb.
     */
    public void swipe(float x1, float y1, float x2, float y2, long durationMs, GestureCompletion cb) {
        try {
            // Validate coordinates
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                Log.w("AccessibilityListener", "Invalid swipe coordinates: (" + x1 + ", " + y1 + ") -> (" + x2 + ", " + y2 + ")");
                cb.onResult(false);
                return;
            }

            // Check if service is connected
            if (serviceInstance == null) {
                Log.e("AccessibilityListener", "Service not connected - cannot perform swipe");
                cb.onResult(false);
                return;
            }

            Log.d("AccessibilityListener", "Performing swipe (" + x1 + ", " + y1 + ") -> (" + x2 + ", " + y2 + ") over " + durationMs + "ms");

            Path swipePath = new Path();
            swipePath.moveTo(x1, y1);
            swipePath.lineTo(x2, y2);

            GestureDescription.StrokeDescription swipeStroke = new GestureDescription.StrokeDescription(swipePath, 0, Math.max(1, durationMs));
            GestureDescription.Builder swipeBuilder = new GestureDescription.Builder();
            swipeBuilder.addStroke(swipeStroke);

            boolean dispatched = dispatchGesture(swipeBuilder.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d("AccessibilityListener", "Swipe completed successfully at (" + x2 + ", " + y2 + ")");
                    cb.onResult(true);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.w("AccessibilityListener", "Swipe gesture was cancelled at (" + x1 + ", " + y1 + ")");
                    cb.onResult(false);
                }
            }, null);

            if (!dispatched) {
                Log.w("AccessibilityListener", "Failed to dispatch swipe gesture at (" + x1 + ", " + y1 + ")");
                cb.onResult(false);
            }
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error performing swipe at (" + x1 + ", " + y1 + ")", e);
            cb.onResult(false);
        }
    }

    /**
     * Press and hold at (x1, y1) until the target app enters drag mode, then glide to
     * (x2, y2) and release. Implemented as a continued stroke: the hold stroke is
     * dispatched with willContinue, and its continuation carries the movement.
     *
     * holdMs <= 0 means "use the system long press timeout plus {@link #LONG_PRESS_MARGIN_MS}".
     * There is no node-level fallback - a drag has no AccessibilityNodeInfo equivalent.
     */
    public void dragAndDrop(float x1, float y1, float x2, float y2, long holdMs, long glideMs, GestureCompletion cb) {
        try {
            // Validate coordinates
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                Log.w("AccessibilityListener", "Invalid drag coordinates: (" + x1 + ", " + y1 + ") -> (" + x2 + ", " + y2 + ")");
                cb.onResult(false);
                return;
            }

            // Check if service is connected
            if (serviceInstance == null) {
                Log.e("AccessibilityListener", "Service not connected - cannot perform drag and drop");
                cb.onResult(false);
                return;
            }

            final long hold = holdMs > 0 ? holdMs : ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS;

            Log.d("AccessibilityListener", "Performing drag and drop (" + x1 + ", " + y1 + ") -> (" + x2 + ", " + y2 + ") hold " + hold + "ms glide " + glideMs + "ms");

            // Both paths are built from the same coordinates: the injector rejects a
            // continuation whose first point does not match where the previous stroke ended.
            Path holdPath = new Path();
            holdPath.moveTo(x1, y1);
            Path glidePath = new Path();
            glidePath.moveTo(x1, y1);
            glidePath.lineTo(x2, y2);

            final GestureDescription.StrokeDescription holdStroke =
                    new GestureDescription.StrokeDescription(holdPath, 0, Math.max(1, hold), true);
            // startTime is relative to the new gesture, and continueStroke must be called
            // on the original stroke object.
            final GestureDescription.StrokeDescription glideStroke =
                    holdStroke.continueStroke(glidePath, 0, Math.max(1, glideMs), false);

            GestureDescription.Builder holdBuilder = new GestureDescription.Builder();
            holdBuilder.addStroke(holdStroke);

            final Runnable dispatchGlide = () -> {
                GestureDescription.Builder glideBuilder = new GestureDescription.Builder();
                glideBuilder.addStroke(glideStroke);

                boolean glideDispatched = dispatchGesture(glideBuilder.build(), new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Log.d("AccessibilityListener", "Drag and drop completed successfully at (" + x2 + ", " + y2 + ")");
                        cb.onResult(true);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        Log.w("AccessibilityListener", "Drag glide was cancelled at (" + x2 + ", " + y2 + ")");
                        cb.onResult(false);
                    }
                }, null);

                if (!glideDispatched) {
                    Log.w("AccessibilityListener", "Failed to dispatch drag glide - releasing the pointer at (" + x1 + ", " + y1 + ")");
                    releaseDragPointer(holdStroke, x1, y1);
                    cb.onResult(false);
                }
            };

            final long holdStartedAt = SystemClock.uptimeMillis();
            boolean dispatched = dispatchGesture(holdBuilder.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    // A stationary stroke injects nothing but its ACTION_DOWN (MotionEventInjector emits
                    // ACTION_MOVE only for points that changed), and the injector reports completion right
                    // after the LAST injected event - so this fires immediately after the press, not after
                    // the stroke's nominal duration. The pointer stays down waiting for the continuation
                    // (there is no timeout for that), so the hold has to be timed here before the glide
                    // is dispatched. Dispatching it from this callback directly made the drag a swipe.
                    long remaining = Math.max(0, hold - (SystemClock.uptimeMillis() - holdStartedAt));
                    new Handler(Looper.getMainLooper()).postDelayed(dispatchGlide, remaining);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    // The pointer is already up, so a continuation would be rejected.
                    Log.w("AccessibilityListener", "Drag hold was cancelled at (" + x1 + ", " + y1 + ")");
                    cb.onResult(false);
                }
            }, null);

            if (!dispatched) {
                Log.w("AccessibilityListener", "Failed to dispatch drag hold gesture at (" + x1 + ", " + y1 + ")");
                cb.onResult(false);
            }
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error performing drag and drop at (" + x1 + ", " + y1 + ")", e);
            cb.onResult(false);
        }
    }

    /**
     * Terminates a drag whose glide could not be dispatched with a 1ms continuation at
     * the hold position, so the injected pointer never stays down.
     */
    private void releaseDragPointer(GestureDescription.StrokeDescription holdStroke, float x, float y) {
        try {
            Path releasePath = new Path();
            releasePath.moveTo(x, y);
            GestureDescription.Builder releaseBuilder = new GestureDescription.Builder();
            releaseBuilder.addStroke(holdStroke.continueStroke(releasePath, 0, 1, false));
            dispatchGesture(releaseBuilder.build(), null, null);
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error releasing drag pointer at (" + x + ", " + y + ")", e);
        }
    }

    // ============================================================================
    // Message Routing System
    // ============================================================================

    /**
     * Send a message to a specific target by index
     * Index 0 = Main App, Index N = Overlay with ID N
     */
    public static boolean sendMessage(int targetOverlayId, String method, Object arguments, int fromOverlayId) {
        if (targetOverlayId == 0) {
            // Send to main app - must still use broadcast Intent (String-based)
            String jsonMessage = arguments instanceof String ? (String) arguments : new Gson().toJson(arguments);
            return sendMessageToMainApp(method, jsonMessage);
        } else {
            // Send to overlay - pass arguments through directly (preserves typed data)
            return sendMessageToOverlay(targetOverlayId, method, arguments, fromOverlayId);
        }
    }

    public static boolean sendMessage(int targetOverlayId, String method, String jsonMessage) {
        return sendMessage(targetOverlayId, method, (Object) jsonMessage, -1);
    }

    /**
     * Reports whether a message actually reached an overlay's Dart method handler.
     * Distinct from sendMessage's boolean, which only says the Java-side channel
     * existed: a MethodChannel invoke before the Dart handler is registered is
     * silently dropped (the reply is notImplemented).
     */
    public interface MessageDeliveryCallback {
        void onResult(boolean delivered);
    }

    /**
     * Send a message to an overlay and report via {@code callback} whether the
     * overlay's Dart handler actually processed it. Callers that must not lose
     * the message (e.g. the host app's foreground-state seed for freshly
     * spawned overlay engines) retry on delivered=false. Overlay targets only.
     */
    public static void sendMessageForResult(int targetOverlayId, String method, Object arguments, MessageDeliveryCallback callback) {
        AccessibilityOverlay overlay = activeOverlays.get(targetOverlayId);
        if (overlay == null) {
            callback.onResult(false);
            return;
        }
        overlay.sendMessageForResult(method, arguments, -1, callback);
    }

    /**
     * Send message to main app (index 0)
     */
    private static boolean sendMessageToMainApp(String method, String jsonMessage) {
        try {
            Intent messageIntent = new Intent(MESSAGE_INTENT);
            messageIntent.putExtra("targetOverlayId", 0);
            messageIntent.putExtra("method", method);
            messageIntent.putExtra("arguments", jsonMessage);
            messageIntent.putExtra("fromOverlayId", getCurrentOverlayContext());
            
            // Get the service instance context
            if (mWindowManager != null) {
                // We have access to the service context
                AccessibilityListener service = getCurrentServiceInstance();
                if (service != null) {
                    service.sendBroadcast(messageIntent);
                    // Log.d("MessageRouter", "Message sent to main app via broadcast");
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e("MessageRouter", "Error sending message to main app: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send message to overlay with typed arguments (preserves Float64List etc.)
     */
    private static boolean sendMessageToOverlay(int overlayId, String method, Object arguments, int fromOverlayId) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay == null) {
            return false;
        }

        try {
            return overlay.sendMessage(method, arguments, fromOverlayId);
        } catch (Exception e) {
            Log.e("MessageRouter", "Error sending message to overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }
    
    
    /**
     * Get current overlay context (used for determining source index)
     */
    private static int getCurrentOverlayContext() {
        // This would need to be set by the overlay when sending messages
        // For now, return -1 to indicate unknown source
        return -1;
    }
    
    /**
     * Get current service instance (helper method)
     */
    private static AccessibilityListener getCurrentServiceInstance() {
        return serviceInstance;
    }

}
