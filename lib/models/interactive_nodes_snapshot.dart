import 'dart:typed_data';

import 'package:flutter_accessibility_service/accessibility_event.dart';
import 'package:flutter_accessibility_service/models/interactive_node.dart';

/// Compact transfer format of [FlutterAccessibilityService.getInteractiveNodeSnapshot].
///
/// The native walk (AccessibilityListener.getInteractiveNodeSnapshot) packs every actionable node into a flat
/// `int[]` with [interactiveNodeIntStride] ints per node - left, top, right, bottom, flags, windowType, parentIndex,
/// webRole, clickableScore - and a flat
/// `List<String?>` with [interactiveNodeStringStride] entries per node - viewId, className, text,
/// contentDescription (text and contentDescription truncated natively to [interactiveNodeMaxTextLength]
/// characters). Both are built on the plugin's background executor, so the platform-thread encode and the Dart
/// decode stay close to a memcpy instead of a map per node.
///
/// - flags: `interactiveNodeFlag*` bits ([interactiveNodeFlagWebContent] = inside web content).
/// - parentIndex: index (in this snapshot's node list) of the nearest ancestor that was itself appended as a node, -1
///   when none. The walk is a depth-first preorder, so it is always smaller than the node's own index.
/// - webRole: a `webRole*` id from Chromium's chromeRole extra (web content only, 0 none).
/// - clickableScore: Chromium's clickableScore extra (web content only, 0 when absent).
const int interactiveNodeIntStride = 9;
const int interactiveNodeStringStride = 4;

/// Prefetch strategies for [FlutterAccessibilityService.getInteractiveNodeSnapshot] (`AccessibilityNodeInfo`
/// FLAG_PREFETCH_* values). The walk is depth-first, so DEPTH_FIRST + UNINTERRUPTIBLE fits best: every cache miss
/// returns the requested node plus up to 50 descendants in visiting order, synchronously and cached, so most of the
/// following getChild calls never leave the process. The platform default (HYBRID, interruptible) delivers the
/// prefetched nodes asynchronously and drops them as soon as the next request starts - in a tight walk on a busy app
/// (a loading web page) nearly every node then costs its own binder round trip.
const int snapshotPrefetchDepthFirst = 0x08 | 0x20;

/// Keep the plain `getRoot()` / `getChild(int)` (platform default HYBRID, interruptible).
const int snapshotPrefetchPlatformDefault = -1;

/// Per-window metadata of the compact format: `windowInts` carries [snapshotWindowIntStride] ints per window - windowId,
/// windowType, nodeStart, nodeCount, truncated (0/1), left, top, right, bottom. Every window of getWindows() that was
/// considered is listed, also the ones the total time budget never reached (truncated, nodeCount 0).
const int snapshotWindowIntStride = 9;

/// One window of an [InteractiveNodesSnapshot]: its nodes are `nodes.sublist(nodeStart, nodeStart + nodeCount)`.
class SnapshotWindow {
  final int windowId;
  final int windowType;
  final int nodeStart;
  final int nodeCount;

  /// The walk of this window was cut short (visited/actionable cap, window or total time budget, missing root,
  /// exception): its node list is partial.
  final bool truncated;
  final int left;
  final int top;
  final int right;
  final int bottom;

  const SnapshotWindow({
    required this.windowId,
    required this.windowType,
    required this.nodeStart,
    required this.nodeCount,
    required this.truncated,
    required this.left,
    required this.top,
    required this.right,
    required this.bottom,
  });

  @override
  String toString() => 'SnapshotWindow($windowId, type: $windowType, nodes: $nodeStart+$nodeCount, truncated: $truncated)';
}

/// Decodes the `windowInts` of the compact format (see [snapshotWindowIntStride]).
List<SnapshotWindow> decodeSnapshotWindows(Int32List ints) {
  if (ints.length % snapshotWindowIntStride != 0) {
    throw FormatException('snapshot window ints length ${ints.length} is not a multiple of $snapshotWindowIntStride');
  }
  final count = ints.length ~/ snapshotWindowIntStride;
  return List<SnapshotWindow>.generate(count, (i) {
    final o = i * snapshotWindowIntStride;
    return SnapshotWindow(
      windowId: ints[o],
      windowType: ints[o + 1],
      nodeStart: ints[o + 2],
      nodeCount: ints[o + 3],
      truncated: ints[o + 4] != 0,
      left: ints[o + 5],
      top: ints[o + 6],
      right: ints[o + 7],
      bottom: ints[o + 8],
    );
  }, growable: false);
}

/// Chromium role ids of [InteractiveNode.webRole] (compact format int 8 of a node; mirrored natively). Mapped from the
/// `AccessibilityNodeInfo.chromeRole` extra (ax::mojom::Role names).
const int webRoleNone = 0;
const int webRoleLink = 1; // link
const int webRoleButton = 2; // button, popUpButton
const int webRoleCheckBox = 3; // checkBox, menuItemCheckBox
const int webRoleRadio = 4; // radioButton, menuItemRadio
const int webRoleSwitch = 5; // switch
const int webRoleToggleButton = 6; // toggleButton
const int webRoleMenuItem = 7; // menuItem
const int webRoleTab = 8; // tab
const int webRoleOption = 9; // listBoxOption, menuListOption
const int webRoleComboBox = 10; // comboBoxSelect, comboBoxMenuButton
const int webRoleTextField = 11; // textField, searchBox, spinButton, textFieldWithComboBox
const int webRoleSlider = 12; // slider
const int webRoleTreeItem = 13; // treeItem
const int webRoleDisclosure = 14; // disclosureTriangle
const int webRoleHeading = 15; // heading
const int webRoleImage = 16; // image
const int webRoleOther = 99; // any other role string

/// Roles that make a web node a target on their own (1..14).
bool isInteractiveWebRole(int role) => role >= webRoleLink && role <= webRoleDisclosure;

/// Native cap for the text and contentDescription strings of the compact format (characters).
const int interactiveNodeMaxTextLength = 64;

/// Bits of the flags int of the compact format.
const int interactiveNodeFlagClickable = 1;
const int interactiveNodeFlagLongClickable = 2;
const int interactiveNodeFlagScrollable = 4;
const int interactiveNodeFlagEditable = 8;
const int interactiveNodeFlagFocusable = 16;

/// The node lies inside web content (below a node with className android.webkit.WebView).
const int interactiveNodeFlagWebContent = 32;

/// Monotonic change counters of the accessibility service, read with
/// [FlutterAccessibilityService.getNodeChangeState]. Unlike the process-global consumeNodesDirty flag they are never
/// reset, so any number of callers (one per Flutter engine) can track them independently: a counter that differs from
/// the value a caller saw last means the tree changed since.
///
/// - [structural]: TYPE_WINDOW_STATE_CHANGED and TYPE_WINDOWS_CHANGED (a new screen, dialog, IME or window).
/// - [content]: TYPE_WINDOW_CONTENT_CHANGED (in-place changes: video progress, page loading, list scrolling).
class NodeChangeState {
  final int structural;
  final int content;

  /// False when the accessibility service is not connected (the counters are then meaningless).
  final bool connected;

  const NodeChangeState({required this.structural, required this.content, required this.connected});

  factory NodeChangeState.fromMap(Map<dynamic, dynamic> map) {
    return NodeChangeState(structural: _asInt(map['structural']), content: _asInt(map['content']), connected: map['connected'] == true);
  }

  @override
  String toString() => 'NodeChangeState(structural: $structural, content: $content, connected: $connected)';
}

/// Outcome of [FlutterAccessibilityService.refreshInteractiveNode].
enum NodeRefreshResult {
  /// Still there, visible to the user, enabled and containing the point: safe to click.
  ok,

  /// The id is not among the actionable nodes of the last snapshot walk (vanished in that walk, never seen, or the
  /// legacy fetch path that keeps no node references).
  missing,

  /// `refresh()` failed: the view is gone from its window.
  gone,

  /// Still attached but not visible to the user (alpha 0, not VISIBLE, scrolled out) or disabled.
  hidden,

  /// Visible, but its bounds no longer contain the point (it moved or shrank).
  moved,

  /// The call failed (service not connected, channel error, older native side).
  error;

  bool get isOk => this == ok;

  static NodeRefreshResult fromName(String? name) {
    for (final value in values) {
      if (value.name == name) return value;
    }
    return error;
  }
}

/// Result of one native walk in the compact format, see [FlutterAccessibilityService.getInteractiveNodeSnapshot].
class InteractiveNodesSnapshot {
  /// True when the walk produced exactly the content of the `lastSignature` the caller passed: [nodes] is then empty
  /// and the caller keeps its previous list. Nothing but this snapshot's metadata crossed the channel.
  final bool unchanged;

  /// Hash over the node geometry and identity of this walk (bounds, flags, windowType, viewId, className - not text
  /// or contentDescription). Pass it back as `lastSignature`.
  final int signature;

  /// The actionable nodes, in walk order (empty when [unchanged]).
  final List<InteractiveNode> nodes;

  /// [NodeChangeState] counters read right BEFORE the walk started: an event that arrives during the walk bumps a
  /// counter past these, so the caller sees it as a change on its next check.
  final int structuralGeneration;
  final int contentGeneration;

  /// Native walk duration (ms, executor thread, excludes the channel transfer).
  final int walkMs;

  /// Windows walked, nodes visited, actionable nodes found (before the cap), subtrees pruned as off-window.
  final int windows;
  final int visited;
  final int actionable;
  final int pruned;

  /// Nodes at the depth cap whose children were not walked.
  final int depthCapped;

  /// True when a per-window cap or the time budget cut the walk short. Never [unchanged].
  final bool truncated;

  /// Per-window breakdown (empty when [unchanged] or from an older native side without `windowInts`).
  final List<SnapshotWindow> windowInfos;

  const InteractiveNodesSnapshot({
    required this.unchanged,
    required this.signature,
    required this.nodes,
    this.structuralGeneration = 0,
    this.contentGeneration = 0,
    this.walkMs = 0,
    this.windows = 0,
    this.visited = 0,
    this.actionable = 0,
    this.pruned = 0,
    this.depthCapped = 0,
    this.truncated = false,
    this.windowInfos = const <SnapshotWindow>[],
  });

  /// An empty, changed snapshot (service not connected, channel error).
  static const InteractiveNodesSnapshot empty = InteractiveNodesSnapshot(unchanged: false, signature: 0, nodes: <InteractiveNode>[]);

  factory InteractiveNodesSnapshot.fromMap(Map<dynamic, dynamic> map) {
    final unchanged = map['unchanged'] == true;
    // Windows first: the node decode takes each node's windowId from their ranges.
    final rawWindowInts = map['windowInts'];
    final List<SnapshotWindow> windowInfos;
    if (unchanged || rawWindowInts == null) {
      windowInfos = const <SnapshotWindow>[];
    } else {
      windowInfos = decodeSnapshotWindows(rawWindowInts is Int32List ? rawWindowInts : Int32List.fromList((rawWindowInts as List<dynamic>).cast<int>()));
    }
    final rawInts = map['ints'];
    final rawStrings = map['strings'];
    final List<InteractiveNode> nodes;
    if (unchanged || rawInts == null || rawStrings == null) {
      nodes = const <InteractiveNode>[];
    } else {
      final Int32List ints = rawInts is Int32List ? rawInts : Int32List.fromList((rawInts as List<dynamic>).cast<int>());
      nodes = decodeCompactInteractiveNodes(ints, rawStrings as List<dynamic>, windows: windowInfos);
    }
    return InteractiveNodesSnapshot(
      unchanged: unchanged,
      signature: _asInt(map['signature']),
      nodes: nodes,
      structuralGeneration: _asInt(map['structuralGeneration']),
      contentGeneration: _asInt(map['contentGeneration']),
      walkMs: _asInt(map['walkMs']),
      windows: _asInt(map['windows']),
      visited: _asInt(map['visited']),
      actionable: _asInt(map['actionable']),
      pruned: _asInt(map['pruned']),
      depthCapped: _asInt(map['depthCapped']),
      truncated: map['truncated'] == true,
      windowInfos: windowInfos,
    );
  }
}

/// Decodes the compact format (see [interactiveNodeIntStride]) into [InteractiveNode]s. Node ids are built exactly as
/// the legacy map format builds them natively: `(viewId ?? className ?? 'node')@left,top,right,bottom`.
///
/// [InteractiveNode.parentTargetId] is the id of node[parentIndex] (null unless 0 <= parentIndex < own index);
/// [InteractiveNode.windowId] comes from the node ranges of [windows] (-1 for a node no window lists).
///
/// Throws [FormatException] when the two arrays do not describe the same number of nodes.
List<InteractiveNode> decodeCompactInteractiveNodes(Int32List ints, List<dynamic> strings, {List<SnapshotWindow> windows = const <SnapshotWindow>[]}) {
  if (ints.length % interactiveNodeIntStride != 0) {
    throw FormatException('compact node ints length ${ints.length} is not a multiple of $interactiveNodeIntStride');
  }
  final count = ints.length ~/ interactiveNodeIntStride;
  if (strings.length != count * interactiveNodeStringStride) {
    throw FormatException('compact node strings length ${strings.length} does not match $count nodes');
  }
  final windowIds = Int32List(count)..fillRange(0, count, -1);
  for (final w in windows) {
    final start = w.nodeStart < 0 ? 0 : w.nodeStart;
    final end = w.nodeStart + w.nodeCount > count ? count : w.nodeStart + w.nodeCount;
    for (var i = start; i < end; i++) {
      windowIds[i] = w.windowId;
    }
  }
  final out = List<InteractiveNode>.filled(count, _placeholder, growable: false);
  for (var i = 0; i < count; i++) {
    final o = i * interactiveNodeIntStride;
    final left = ints[o];
    final top = ints[o + 1];
    final right = ints[o + 2];
    final bottom = ints[o + 3];
    final flags = ints[o + 4];
    final windowType = ints[o + 5];
    final parentIndex = ints[o + 6];
    final s = i * interactiveNodeStringStride;
    final viewId = strings[s] as String?;
    final className = strings[s + 1] as String?;
    out[i] = InteractiveNode(
      id: '${viewId ?? className ?? 'node'}@$left,$top,$right,$bottom',
      bounds: ScreenBounds(left: left, top: top, right: right, bottom: bottom, width: right - left, height: bottom - top),
      isClickable: flags & interactiveNodeFlagClickable != 0,
      isLongClickable: flags & interactiveNodeFlagLongClickable != 0,
      isScrollable: flags & interactiveNodeFlagScrollable != 0,
      isEditable: flags & interactiveNodeFlagEditable != 0,
      isFocusable: flags & interactiveNodeFlagFocusable != 0,
      viewId: viewId,
      className: className,
      text: strings[s + 2] as String?,
      contentDescription: strings[s + 3] as String?,
      windowType: windowType,
      isWebContent: flags & interactiveNodeFlagWebContent != 0,
      webRole: ints[o + 7],
      clickableScore: ints[o + 8],
      parentTargetId: parentIndex >= 0 && parentIndex < i ? out[parentIndex].id : null,
      windowId: windowIds[i],
    );
  }
  return out;
}

final InteractiveNode _placeholder = InteractiveNode(id: '', bounds: ScreenBounds());

int _asInt(Object? value) => value is int ? value : (value is num ? value.toInt() : 0);
