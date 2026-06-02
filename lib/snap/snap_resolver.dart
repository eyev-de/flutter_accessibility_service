import 'package:flutter_accessibility_service/models/interactive_node.dart';

/// The element a gaze point snapped onto, plus how far the point was from it.
class SnapResult {
  /// The chosen snap target.
  final InteractiveNode node;

  /// Distance in pixels from the gaze point to the node's bounds; `0` when the
  /// point is inside the node.
  final double distance;

  const SnapResult(this.node, this.distance);

  bool get isContained => distance == 0;
}

/// Pure, side-effect-free nearest-target picker for the gaze "snap to item"
/// feature. Mirrors the native `findDeepestActionableNode` preference for the
/// most specific element under the point.
class SnapResolver {
  /// Maximum distance (px) a gaze point may be from a node and still snap to it.
  /// Beyond this, an idle gaze in empty space snaps to nothing (no false dwell).
  final double maxSnapRadiusPx;

  const SnapResolver({this.maxSnapRadiusPx = 160});

  /// Returns the best snap target for the gaze point ([gx], [gy]) among [nodes],
  /// or `null` if nothing is close enough.
  ///
  /// 1. Containment first: among all nodes whose bounds contain the point, the
  ///    one with the **smallest area** wins (the most specific / deepest target —
  ///    e.g. a button inside a scrollable container).
  /// 2. Otherwise the node with the smallest edge distance, capped at
  ///    [maxSnapRadiusPx].
  SnapResult? resolve(double gx, double gy, List<InteractiveNode> nodes) {
    InteractiveNode? containing;
    for (final n in nodes) {
      if (n.contains(gx, gy)) {
        if (containing == null || n.area < containing.area) {
          containing = n;
        }
      }
    }
    if (containing != null) return SnapResult(containing, 0);

    InteractiveNode? nearest;
    double bestDistance = maxSnapRadiusPx;
    for (final n in nodes) {
      final d = n.distanceTo(gx, gy);
      if (d < bestDistance) {
        bestDistance = d;
        nearest = n;
      }
    }
    return nearest == null ? null : SnapResult(nearest, bestDistance);
  }
}
