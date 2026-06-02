import 'dart:async';

import 'package:flutter_accessibility_service/flutter_accessibility_service.dart';

/// Drives the gaze "snap to item" behaviour.
///
/// Feed it gaze samples via [onGaze]. It keeps a throttled cache of the on-screen
/// interactive nodes (refetched on a TTL or when the foreground window changes),
/// resolves the nearest snap target with [SnapResolver], pushes highlight updates
/// to a renderer overlay, and — once the gaze dwells on the same target for
/// [dwellMs] — performs a click via [FlutterAccessibilityService.performClickAtPoint].
///
/// All coordinates are in raw device pixels (the same space as
/// `AccessibilityNodeInfo.getBoundsInScreen`), so the gaze stream must be too.
class SnapController {
  /// Overlay id of the highlight renderer (see `snap_overlay.dart`). Highlight,
  /// dwell-progress and clear messages are delivered to it via
  /// [FlutterAccessibilityService.invokeMethod]. Use `null` to run snapping
  /// (and auto-click) without any visual overlay.
  final int? highlightOverlayId;

  /// Milliseconds the gaze must rest on a target before it auto-clicks.
  final int dwellMs;

  /// Minimum interval between node-list refetches.
  final int pollIntervalMs;

  /// How far (px) a gaze point may be from a node and still snap to it.
  final double snapRadiusPx;

  /// Global debounce (ms) preventing rapid repeat fires.
  final int cooldownMs;

  /// While dwelling, the gaze may drift this far (px) outside the target without
  /// resetting the dwell — absorbs natural eye micro-saccades.
  final double jitterTolerancePx;

  final SnapResolver _resolver;

  List<InteractiveNode> _nodes = const [];
  int _lastFetchMs = 0;
  bool _fetching = false;

  String? _currentTargetId;
  InteractiveNode? _currentNode;
  int _dwellStartMs = 0;

  String? _lastFiredTargetId;
  int _cooldownUntilMs = 0;

  bool _disposed = false;

  SnapController({
    this.highlightOverlayId,
    this.dwellMs = 800,
    this.pollIntervalMs = 250,
    this.snapRadiusPx = 160,
    this.cooldownMs = 600,
    double? jitterTolerancePx,
  })  : jitterTolerancePx = jitterTolerancePx ?? snapRadiusPx,
        _resolver = SnapResolver(maxSnapRadiusPx: snapRadiusPx);

  int get _now => DateTime.now().millisecondsSinceEpoch;

  /// The node the gaze is currently snapped to, if any.
  InteractiveNode? get currentTarget => _currentNode;

  /// Process a single gaze sample at ([gx], [gy]) in raw screen pixels.
  Future<void> onGaze(double gx, double gy) async {
    if (_disposed) return;
    await _maybeRefreshNodes();

    final SnapResult? result = _resolver.resolve(gx, gy, _nodes);
    final int now = _now;

    if (result == null) {
      await _clearTarget();
      return;
    }

    // Treat the target as unchanged if the resolver picked the same id, OR if the
    // gaze merely drifted within the jitter tolerance of the current node.
    final bool sameTarget = result.node.id == _currentTargetId ||
        (_currentNode != null && _currentNode!.distanceTo(gx, gy) <= jitterTolerancePx);

    if (!sameTarget) {
      _currentTargetId = result.node.id;
      _currentNode = result.node;
      _dwellStartMs = now;
      await _sendSnap(result.node);
      return;
    }

    // Same target: accumulate dwell. (Keep the freshest geometry for this id.)
    if (result.node.id == _currentTargetId) _currentNode = result.node;

    final int elapsed = now - _dwellStartMs;
    await _sendDwellProgress((elapsed / dwellMs).clamp(0.0, 1.0).toDouble());

    final bool canFire = elapsed >= dwellMs &&
        _currentTargetId != _lastFiredTargetId &&
        now >= _cooldownUntilMs;
    if (canFire) {
      final node = _currentNode!;
      _lastFiredTargetId = _currentTargetId;
      _cooldownUntilMs = now + cooldownMs;
      await _sendDwellProgress(1.0);
      await FlutterAccessibilityService.performClickAtPoint(node.centerX, node.centerY);
    }
  }

  Future<void> _maybeRefreshNodes() async {
    if (_fetching) return;
    final int now = _now;
    final bool ttlExpired = now - _lastFetchMs >= pollIntervalMs;
    if (!ttlExpired) return;

    _fetching = true;
    try {
      // Refetch on the TTL tick, and also force a refetch if the window changed
      // since last poll (cheap native flag) for sub-frame latency after transitions.
      final bool dirty = await FlutterAccessibilityService.consumeNodesDirty();
      if (ttlExpired || dirty) {
        _nodes = await FlutterAccessibilityService.getInteractiveNodes();
        _lastFetchMs = _now;
      }
    } finally {
      _fetching = false;
    }
  }

  Future<void> _clearTarget() async {
    if (_currentTargetId == null) return;
    _currentTargetId = null;
    _currentNode = null;
    _dwellStartMs = 0;
    await _send('onClear', null);
  }

  Future<void> _sendSnap(InteractiveNode node) {
    return _send('onSnap', {
      'left': node.left,
      'top': node.top,
      'right': node.right,
      'bottom': node.bottom,
    });
  }

  Future<void> _sendDwellProgress(double progress) => _send('onDwellProgress', progress);

  Future<void> _send(String method, dynamic arguments) async {
    final id = highlightOverlayId;
    if (id == null) return;
    try {
      await FlutterAccessibilityService.invokeMethod(id, method, arguments);
    } catch (_) {
      // Overlay engine may briefly be unattached (e.g. just after show()); ignore.
    }
  }

  /// Reset all dwell/snap state. Call when toggling snap mode off.
  void reset() {
    _currentTargetId = null;
    _currentNode = null;
    _dwellStartMs = 0;
    _lastFiredTargetId = null;
    _cooldownUntilMs = 0;
  }

  void dispose() {
    _disposed = true;
    reset();
  }
}
