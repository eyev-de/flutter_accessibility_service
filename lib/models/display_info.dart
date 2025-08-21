class DisplayInfo {
  final int displayId;
  final int width;
  final int height;
  final double density;
  final int densityDpi;
  final double refreshRate;
  final int rotation;
  final String name;
  final bool isValid;
  final DisplayMetrics metrics;

  const DisplayInfo({
    required this.displayId,
    required this.width,
    required this.height,
    required this.density,
    required this.densityDpi,
    required this.refreshRate,
    required this.rotation,
    required this.name,
    required this.isValid,
    required this.metrics,
  });

  factory DisplayInfo.fromMap(Map<String, dynamic> map) {
    return DisplayInfo(
      displayId: map['displayId'] ?? 0,
      width: map['width'] ?? 0,
      height: map['height'] ?? 0,
      density: (map['density'] ?? 0.0).toDouble(),
      densityDpi: map['densityDpi'] ?? 0,
      refreshRate: (map['refreshRate'] ?? 0.0).toDouble(),
      rotation: map['rotation'] ?? 0,
      name: map['name'] ?? '',
      isValid: map['isValid'] ?? false,
      metrics: DisplayMetrics.fromMap(Map<String, dynamic>.from(map['metrics'] ?? {})),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'displayId': displayId,
      'width': width,
      'height': height,
      'density': density,
      'densityDpi': densityDpi,
      'refreshRate': refreshRate,
      'rotation': rotation,
      'name': name,
      'isValid': isValid,
      'metrics': metrics.toMap(),
    };
  }

  String get orientationString {
    switch (rotation) {
      case 0:
        return 'Portrait';
      case 1:
        return 'Landscape';
      case 2:
        return 'Reverse Portrait';
      case 3:
        return 'Reverse Landscape';
      default:
        return 'Unknown';
    }
  }

  @override
  String toString() {
    return 'DisplayInfo(id: $displayId, size: ${width}x$height, density: $density, refreshRate: ${refreshRate}Hz, rotation: $orientationString)';
  }
}

class DisplayMetrics {
  final int widthPixels;
  final int heightPixels;
  final double density;
  final int densityDpi;
  final double scaledDensity;
  final double xdpi;
  final double ydpi;

  const DisplayMetrics({
    required this.widthPixels,
    required this.heightPixels,
    required this.density,
    required this.densityDpi,
    required this.scaledDensity,
    required this.xdpi,
    required this.ydpi,
  });

  factory DisplayMetrics.fromMap(Map<String, dynamic> map) {
    return DisplayMetrics(
      widthPixels: map['widthPixels'] ?? 0,
      heightPixels: map['heightPixels'] ?? 0,
      density: (map['density'] ?? 0.0).toDouble(),
      densityDpi: map['densityDpi'] ?? 0,
      scaledDensity: (map['scaledDensity'] ?? 0.0).toDouble(),
      xdpi: (map['xdpi'] ?? 0.0).toDouble(),
      ydpi: (map['ydpi'] ?? 0.0).toDouble(),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'widthPixels': widthPixels,
      'heightPixels': heightPixels,
      'density': density,
      'densityDpi': densityDpi,
      'scaledDensity': scaledDensity,
      'xdpi': xdpi,
      'ydpi': ydpi,
    };
  }

  String get densityCategory {
    if (densityDpi <= 120) return 'LDPI';
    if (densityDpi <= 160) return 'MDPI';
    if (densityDpi <= 240) return 'HDPI';
    if (densityDpi <= 320) return 'XHDPI';
    if (densityDpi <= 480) return 'XXHDPI';
    if (densityDpi <= 640) return 'XXXHDPI';
    return 'ULTRA_HIGH';
  }

  @override
  String toString() {
    return 'DisplayMetrics(${widthPixels}x$heightPixels, density: $density, dpi: $densityDpi, category: $densityCategory)';
  }
}