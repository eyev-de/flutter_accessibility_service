import 'dart:math' as math;

import 'package:flutter_accessibility_service/accessibility_event.dart';

/// A single actionable on-screen element returned by
/// [FlutterAccessibilityService.getInteractiveNodes], used by the gaze-driven
/// "snap to item" feature. Bounds are in raw device pixels (screen coordinates).
class InteractiveNode {
  /// Stable-ish identifier (`viewId|className` + bounds) used to detect when the
  /// gaze stays on the same target across polls so a dwell can accumulate.
  final String id;

  /// Bounds in raw screen pixels.
  final ScreenBounds bounds;

  final bool isClickable;
  final bool isLongClickable;
  final bool isScrollable;
  final bool isEditable;
  final bool isFocusable;

  final String? text;
  final String? contentDescription;
  final String? viewId;
  final String? className;

  /// Android [AccessibilityWindowInfo] window type, or -1 when unknown.
  final int windowType;

  /// Android snapshot walk only (getInteractiveNodeSnapshot): the node lies inside web content (below a node with
  /// className android.webkit.WebView - Chrome, Samsung Internet, in-app WebViews).
  final bool isWebContent;

  /// Chromium's role of a web node as a `webRole*` id (see interactive_nodes_snapshot.dart), 0 when none.
  final int webRole;

  /// Chromium's `AccessibilityNodeInfo.clickableScore` extra: 300 default action on a control or link, 200 click
  /// listener on a non-control (card / wrapper), 100 only an ancestor is clickable; 0 when absent.
  final int clickableScore;

  /// Id of the nearest ancestor that is itself a node of the same snapshot, or null (no ancestor in the list, or a
  /// source without tree information).
  final String? parentTargetId;

  /// Android AccessibilityWindowInfo id of the node's window, or -1 when unknown.
  final int windowId;

  const InteractiveNode({
    required this.id,
    required this.bounds,
    this.isClickable = false,
    this.isLongClickable = false,
    this.isScrollable = false,
    this.isEditable = false,
    this.isFocusable = false,
    this.text,
    this.contentDescription,
    this.viewId,
    this.className,
    this.windowType = -1,
    this.isWebContent = false,
    this.webRole = 0,
    this.clickableScore = 0,
    this.parentTargetId,
    this.windowId = -1,
  });

  factory InteractiveNode.fromMap(Map<dynamic, dynamic> map) {
    return InteractiveNode(
      id: map['id']?.toString() ?? '',
      bounds: ScreenBounds.fromMap(Map<dynamic, dynamic>.from(map['bounds'] as Map)),
      isClickable: map['isClickable'] == true,
      isLongClickable: map['isLongClickable'] == true,
      isScrollable: map['isScrollable'] == true,
      isEditable: map['isEditable'] == true,
      isFocusable: map['isFocusable'] == true,
      text: map['text']?.toString(),
      contentDescription: map['contentDescription']?.toString(),
      viewId: map['viewId']?.toString(),
      className: map['className']?.toString(),
      windowType: map['windowType'] is int ? map['windowType'] as int : -1,
    );
  }

  double get left => (bounds.left ?? 0).toDouble();
  double get top => (bounds.top ?? 0).toDouble();
  double get right => (bounds.right ?? 0).toDouble();
  double get bottom => (bounds.bottom ?? 0).toDouble();

  double get centerX => (left + right) / 2.0;
  double get centerY => (top + bottom) / 2.0;

  double get area => ((bounds.width ?? 0) * (bounds.height ?? 0)).toDouble();

  /// Whether the point (x, y) is inside this node's bounds.
  bool contains(double x, double y) => x >= left && x <= right && y >= top && y <= bottom;

  /// Euclidean distance from (x, y) to the nearest edge of the bounds; 0 if inside.
  double distanceTo(double x, double y) {
    final double dx = x < left ? left - x : (x > right ? x - right : 0);
    final double dy = y < top ? top - y : (y > bottom ? y - bottom : 0);
    if (dx == 0 && dy == 0) return 0;
    return math.sqrt(dx * dx + dy * dy);
  }

  @override
  String toString() => 'InteractiveNode($id, bounds: $bounds)';
}
