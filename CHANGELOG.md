## Unreleased

- Add `getNodeChangeState`: monotonic structural/content tree-change counters (own-package and text-only content changes ignored), pollable by every engine without consuming a shared flag
- Add `getInteractiveNodeSnapshot`: bounded interactive-node walk (off-window pruning, per-window caps, time budget) in a compact int/string transfer format with a signature-based "unchanged" answer
- Run node walks on a background-priority thread; in a debuggable host app, log fetch/main-thread stats under the `SnapNodes` tag (release builds collect no stats and log only one rate-limited warning, at most once per 60s, when snapshot walks are repeatedly truncated by the time budgets)
- Add `nodeChangeStream`: pushes the tree-change counters (coalesced to one message per ~16ms, no work without a listener) so a snap controller reacts to a change at once instead of on its next poll
- Add `refreshInteractiveNode`: re-reads one node of the last snapshot walk from its app on a dedicated thread and reports whether it is still there, visible, enabled and under the point (pre-click guard)
- `SnapNodes` summary (debug builds) also logs event delivery delay, content change types, pushes and node refresh results/durations
- `getInteractiveNodeSnapshot`: per-window time budget (`windowBudgetMs`, default 600ms; total `timeBudgetMs` now 1000ms), batched node prefetch (depth-first + uninterruptible by default; `prefetchStrategy` overrides it) and a per-window breakdown (`windowInts` -> `InteractiveNodesSnapshot.windowInfos`, truncated windows marked); `refreshInteractiveNode` keeps the earlier node refs of a truncated window; `SnapNodes` (debug builds) logs prefetch mode, ms per node, slow node fetches and truncated windows by reason
- `getInteractiveNodeSnapshot` web content (below an `android.webkit.WebView` node): reads Chromium's `chromeRole` / `clickableScore` / `offscreen` extras, prunes scrolled-off web nodes, treats interactive roles and control classes as actionable, counts the depth cap from the web root (40 levels); compact format now 9 ints per node (adds parentIndex, webRole, clickableScore; flag bit 32 = web content), decoded into the new `InteractiveNode.isWebContent`, `webRole`, `clickableScore`, `parentTargetId` and `windowId`; `SnapNodes` (debug builds) logs a `web:` line per changed snapshot

## 1.1.0

- Add `swipe` gesture injection (one stroke from A to B)
- Add `dragAndDrop` gesture injection (long press hold at A, glide to B, release)
- Add `longPressTimeoutMs` to query the system long press timeout

## 1.0.0

- Improve nodes fetching
- Add configuration model for the overlay window


## 0.3.0

Fix `BroadcastReceiver` registration issue

## 0.2.6

Fix `IllegalArgumentException` on detach

## 0.2.5

- Add namespace to the android build.gradle

## 0.2.4

- Fix for StackOverflow in `getSubNodes` method

## 0.2.3

- Add support for Android 14
- Perform actions with Accessibility Service
- Add Accessibility Overlay Support
- Support automation

## 0.2.2

- Get the node children and sub-children's text

## 0.2.1

- Fix the accessibility stream broadcast

## 0.0.2

- Add node bounds in screen coordinates
- Upgrade `requestAccessibilityPermission` to wait for the result

## 0.0.1

- Initial release
