import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_accessibility_service/flutter_accessibility_service.dart';

/// Full-screen, non-touchable highlight overlay for the gaze "snap to item"
/// feature. It is a pure renderer: the [SnapController] (running in the main app
/// engine) pushes `onSnap` / `onDwellProgress` / `onClear` messages here and this
/// widget draws a rounded box around the snapped element plus a dwell-progress ring.
@pragma("vm:entry-point")
void snapHighlightOverlay() {
  runApp(
    const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: SnapHighlightWidget(),
    ),
  );
}

class SnapHighlightWidget extends StatefulWidget {
  const SnapHighlightWidget({Key? key}) : super(key: key);

  @override
  State<SnapHighlightWidget> createState() => _SnapHighlightWidgetState();
}

class _SnapHighlightWidgetState extends State<SnapHighlightWidget> {
  // Snap target bounds in raw device pixels (null when nothing is snapped).
  Rect? _boundsPx;
  double _progress = 0.0;

  @override
  void initState() {
    super.initState();
    FlutterAccessibilityService.setMethodHandler((call, fromId) async {
      switch (call.method) {
        case 'onSnap':
          final a = Map<dynamic, dynamic>.from(call.arguments as Map);
          setState(() {
            _boundsPx = Rect.fromLTRB(
              _toDouble(a['left']),
              _toDouble(a['top']),
              _toDouble(a['right']),
              _toDouble(a['bottom']),
            );
            _progress = 0.0;
          });
          return true;
        case 'onDwellProgress':
          setState(() => _progress = _toDouble(call.arguments));
          return true;
        case 'onClear':
          setState(() {
            _boundsPx = null;
            _progress = 0.0;
          });
          return true;
      }
      return null;
    });
  }

  static double _toDouble(dynamic v) => v is num ? v.toDouble() : 0.0;

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    // Native bounds are raw device pixels measured from the physical top-left.
    // Convert to Flutter logical px and undo the status-bar inset added by the
    // overlay view's setFitsSystemWindows(true).
    final double dpr = media.devicePixelRatio;
    final double topInset = media.padding.top;

    return Material(
      type: MaterialType.transparency,
      child: IgnorePointer(
        child: CustomPaint(
          size: Size.infinite,
          painter: _HighlightPainter(
            boundsPx: _boundsPx,
            progress: _progress,
            devicePixelRatio: dpr,
            topInsetLogical: topInset,
          ),
        ),
      ),
    );
  }
}

class _HighlightPainter extends CustomPainter {
  final Rect? boundsPx;
  final double progress;
  final double devicePixelRatio;
  final double topInsetLogical;

  _HighlightPainter({
    required this.boundsPx,
    required this.progress,
    required this.devicePixelRatio,
    required this.topInsetLogical,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final px = boundsPx;
    if (px == null) return;

    // Raw device px -> logical px, then shift up by the status-bar inset.
    final Rect rect = Rect.fromLTRB(
      px.left / devicePixelRatio,
      px.top / devicePixelRatio - topInsetLogical,
      px.right / devicePixelRatio,
      px.bottom / devicePixelRatio - topInsetLogical,
    );
    final rrect = RRect.fromRectAndRadius(rect, const Radius.circular(10));

    final fill = Paint()
      ..style = PaintingStyle.fill
      ..color = const Color(0x2600B0FF);
    final stroke = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..color = const Color(0xFF00B0FF);
    canvas.drawRRect(rrect, fill);
    canvas.drawRRect(rrect, stroke);

    // Dwell-progress ring centered on the target.
    if (progress > 0) {
      final center = rect.center;
      final radius = math.min(rect.width, rect.height) / 2 * 0.4 + 8;
      final track = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 4
        ..color = const Color(0x33FFFFFF);
      final arc = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 4
        ..strokeCap = StrokeCap.round
        ..color = const Color(0xFF00E676);
      canvas.drawCircle(center, radius, track);
      canvas.drawArc(
        Rect.fromCircle(center: center, radius: radius),
        -math.pi / 2,
        2 * math.pi * progress.clamp(0.0, 1.0),
        false,
        arc,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _HighlightPainter old) =>
      old.boundsPx != boundsPx ||
      old.progress != progress ||
      old.devicePixelRatio != devicePixelRatio ||
      old.topInsetLogical != topInsetLogical;
}
