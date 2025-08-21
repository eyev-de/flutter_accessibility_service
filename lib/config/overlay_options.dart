import 'package:flutter_accessibility_service/config/overlay_gravity.dart';

/// Configuration options for accessibility overlays
class OverlayOptions {
  final int? width;
  final int? height;
  final int? x;
  final int? y;
  final OverlayGravity gravity;
  final String? title;
  final bool startHidden;
  final Map<String, dynamic>? extras;

  const OverlayOptions({
    this.width,
    this.height,
    this.x,
    this.y,
    this.gravity = OverlayGravity.top,
    this.title,
    this.startHidden = false,
    this.extras,
  });

  /// Copy with new values
  OverlayOptions copyWith({
    int? width,
    int? height,
    int? x,
    int? y,
    OverlayGravity? gravity,
    String? title,
    bool? startHidden,
    Map<String, dynamic>? extras,
  }) {
    return OverlayOptions(
      width: width ?? this.width,
      height: height ?? this.height,
      x: x ?? this.x,
      y: y ?? this.y,
      gravity: gravity ?? this.gravity,
      title: title ?? this.title,
      startHidden: startHidden ?? this.startHidden,
      extras: extras ?? this.extras,
    );
  }

  /// Convert to JSON for method channel
  Map<String, dynamic> toJson() {
    return {
      'width': width,
      'height': height,
      'x': x,
      'y': y,
      'gravity': gravity.name,
      'title': title,
      'startHidden': startHidden,
      'extras': extras,
    };
  }

  /// Create from JSON
  factory OverlayOptions.fromJson(Map<String, dynamic> json) {
    return OverlayOptions(
      width: (json['width'] as int?),
      height: (json['height'] as int?),
      x: (json['x'] as int?),
      y: (json['y'] as int?),
      gravity: OverlayGravity.values.byName(json['gravity']?.toString() ?? 'top'),
      title: json['title']?.toString(),
      startHidden: json['startHidden'] as bool? ?? false,
      extras: json['extras']?.cast<String, dynamic>(),
    );
  }

  @override
  String toString() {
    return 'OverlayOptions(size: ${width}x$height, position: ($x, $y), gravity: $gravity)';
  }
}

/// Information about an active overlay
class OverlayInfo {
  final int id;
  final OverlayOptions options;
  final bool isVisible;
  final DateTime createdAt;
  final DateTime? lastUpdated;

  const OverlayInfo({
    required this.id,
    required this.options,
    required this.isVisible,
    required this.createdAt,
    this.lastUpdated,
  });

  factory OverlayInfo.fromJson(Map<String, dynamic> json) {
    return OverlayInfo(
      id: json['id'],
      options: OverlayOptions.fromJson(Map<String, dynamic>.from(json['options'] ?? {})),
      isVisible: json['isVisible'] ?? false,
      createdAt: DateTime.fromMillisecondsSinceEpoch(json['createdAt']),
      lastUpdated: json['lastUpdated'] != null ? DateTime.fromMillisecondsSinceEpoch(json['lastUpdated']) : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'options': options.toJson(),
      'isVisible': isVisible,
      'createdAt': createdAt.millisecondsSinceEpoch,
      'lastUpdated': lastUpdated?.millisecondsSinceEpoch,
    };
  }

  @override
  String toString() {
    return 'OverlayInfo(id: $id, visible: $isVisible)';
  }
}
