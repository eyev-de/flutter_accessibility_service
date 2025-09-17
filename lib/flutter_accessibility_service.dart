/// Flutter Accessibility Service - Multiple Overlay System
///
/// This library provides support for creating and managing multiple
/// accessibility overlays on Android devices.
library flutter_accessibility_service;

import 'dart:async';
import 'dart:convert';
import 'dart:developer';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_accessibility_service/accessibility_event.dart';
import 'package:flutter_accessibility_service/constants.dart';
import 'package:flutter_accessibility_service/models/display_info.dart';
import 'package:flutter_accessibility_service/config/overlay_options.dart';

import 'config/overlay_config.dart';

export 'config/overlay_options.dart';
export 'flutter_accessibility_service.dart';
export 'accessibility_event.dart';
export 'constants.dart';
export 'models/display_info.dart';
export 'config/overlay_config.dart';

class FlutterAccessibilityService {
  FlutterAccessibilityService._();

  static const MethodChannel _methodChannel = MethodChannel('x-slayer/accessibility_channel');
  static const EventChannel _eventChannel = EventChannel('x-slayer/accessibility_event');
  static const EventChannel _messageEventChannel = EventChannel('x-slayer/accessibility_message');
  static const MethodChannel _overlayMessageChannel = MethodChannel('x-slayer/accessibility_message');
  static Stream<AccessibilityEvent>? _stream;
  static Stream<Map<String, dynamic>>? _messageStream;

  /// stream the incoming Accessibility events
  static Stream<AccessibilityEvent> get accessStream {
    if (Platform.isAndroid) {
      _stream ??= _eventChannel.receiveBroadcastStream().map<AccessibilityEvent>(
            (event) => AccessibilityEvent.fromMap(jsonDecode(event)),
          );
      return _stream!;
    }
    throw Exception("Accessibility API exclusively available on Android!");
  }

  /// stream for incoming messages from overlays and main app
  static Stream<Map<String, dynamic>> get messageStream {
    if (Platform.isAndroid) {
      _messageStream ??= _messageEventChannel.receiveBroadcastStream().map<Map<String, dynamic>>(
            (event) => Map<String, dynamic>.from(event),
          );
      return _messageStream!;
    }
    throw Exception("Message API exclusively available on Android!");
  }

  /// request accessibility permission
  /// it will open the accessibility settings page and return `true` once the permission granted.
  static Future<bool> requestAccessibilityPermission() async {
    try {
      return await _methodChannel.invokeMethod('requestAccessibilityPermission');
    } on PlatformException catch (error) {
      log("$error");
      return Future.value(false);
    }
  }

  /// check if accessibility permission is enabled
  static Future<bool> isAccessibilityPermissionEnabled() async {
    try {
      return await _methodChannel.invokeMethod('isAccessibilityPermissionEnabled');
    } on PlatformException catch (error) {
      log("$error");
      return false;
    }
  }

  /// An action that can be performed on an `AccessibilityNodeInfo` by nodeId
  /// pass the necessary arguments depends on each action to avoid any errors
  /// See more: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction
  static Future<bool> performAction(
    AccessibilityEvent event,
    NodeAction action, [
    dynamic arguments,
  ]) async {
    try {
      if (action == NodeAction.unknown) return false;
      return await _methodChannel.invokeMethod<bool?>(
            'performActionById',
            {
              "nodeId": event.mapId,
              "nodeAction": action.id,
              "extras": arguments,
            },
          ) ??
          false;
    } on PlatformException catch (error) {
      log("$error");
      return false;
    }
  }

  /// Returns a list of system actions available in the system right now.
  /// System actions that correspond to the `GlobalAction`
  static Future<List<GlobalAction>> getSystemActions() async {
    try {
      final _list = await _methodChannel.invokeMethod<List<dynamic>>('getSystemActions') ?? [];
      return _list
          .map(
            (e) => GlobalAction.values.firstWhere(
              (element) => element.id == e,
              orElse: () => GlobalAction.unknown,
            ),
          )
          .toList();
    } on PlatformException catch (error) {
      log("$error");
      return [];
    }
  }

  /// Performs a global action.
  /// Such an action can be performed at any moment regardless of the current application or user location in that application
  /// For example going back, going home, opening recents, etc.
  ///
  /// Note: The global action themselves give no information about the current availability of their corresponding actions.
  /// To determine if a global action is available, use `getSystemActions()`
  static Future<bool> performGlobalAction(GlobalAction action) async {
    try {
      if (action == GlobalAction.unknown) return false;
      return await _methodChannel.invokeMethod<bool?>(
            'performGlobalAction',
            {"action": action.id},
          ) ??
          false;
    } on PlatformException catch (error) {
      log("$error");
      return false;
    }
  }

  /// Get information about the current primary display
  static Future<DisplayInfo?> getDisplayInfo() async {
    try {
      final result = await _methodChannel.invokeMethod('getDisplayInfo');
      if (result != null) {
        return DisplayInfo.fromMap(Map<String, dynamic>.from(result));
      }
      return null;
    } on PlatformException catch (error) {
      log("$error");
      return null;
    }
  }

  /// Get information about all available displays
  static Future<List<DisplayInfo>> getAllDisplays() async {
    try {
      final List<dynamic>? result = await _methodChannel.invokeMethod<List<dynamic>>('getAllDisplays');
      if (result != null) {
        return result.map((displayMap) => DisplayInfo.fromMap(Map<String, dynamic>.from(displayMap))).toList();
      }
      return [];
    } on PlatformException catch (error) {
      log("$error");
      return [];
    }
  }

  /// Get detailed display metrics for the primary display
  static Future<DisplayMetrics?> getDisplayMetrics() async {
    try {
      final result = await _methodChannel.invokeMethod('getDisplayMetrics');
      if (result != null) {
        return DisplayMetrics.fromMap(Map<String, dynamic>.from(result));
      }
      return null;
    } on PlatformException catch (error) {
      log("$error");
      return null;
    }
  }

  // ============================================================================
  // Overlay Message Handler (similar to desktop multi-window plugin)
  // ============================================================================

  /// Set method handler for receiving messages in overlays
  /// This allows overlays to receive messages with source index information
  static void setMethodHandler(Future<dynamic> Function(MethodCall call, int fromOverlayId)? handler) {
    if (handler == null) {
      _overlayMessageChannel.setMethodCallHandler(null);
      return;
    }
    _overlayMessageChannel.setMethodCallHandler((call) async {
      try {
        if (call.method == 'receiveMessage') {
          final data = Map<String, dynamic>.from(call.arguments as Map);
          final fromOverlayId = data['fromOverlayId'] as int? ?? -1;
          final message = data['arguments'];
          final method = data['method'];
          final result = await handler(MethodCall(method, message), fromOverlayId);
          return result;
        }
      } catch (e) {
        print('Error in method handler: $e');
        return null;
      }
      return null;
    });
  }

  // ============================================================================
  // Message Communication System
  // ============================================================================

  /// Send a message to a target by index
  /// Index 0 = Main App, Index N = Overlay with ID N
  ///
  /// Parameters:
  /// - [targetIndex]: 0 for main app, overlay ID for overlays
  /// - [message]: JSON serializable Map containing the message data
  ///
  /// Returns true if the message was successfully sent/queued
  static Future<dynamic> invokeMethod(int targetOverlayId, String method, [dynamic arguments]) async {
    try {
      return await _methodChannel.invokeMethod(
        'invokeMethod',
        {
          'targetOverlayId': targetOverlayId,
          'method': method,
          'arguments': jsonEncode(arguments),
        },
      );
    } on PlatformException catch (error) {
      log("Error sending message: $error");
      return false;
    }
  }

  // ============================================================================
  // Multiple Overlay Management
  // ============================================================================

  /// Create a new accessibility overlay with unique integer ID
  ///
  /// Parameters:
  /// - [id]: Unique integer identifier for the overlay
  /// - [options]: Configuration options for the overlay
  /// - [entrypoint]: Flutter entrypoint function name
  ///
  /// Returns the overlay ID as an integer if successful, null if failed (e.g., duplicate ID)
  /// Note: Each overlay must have a unique integer ID. Creating an overlay with an
  /// existing ID will fail and return null.
  static Future<int?> createOverlay(
    int id, {
    required OverlayOptions options,
    required String entrypoint,
  }) async {
    try {
      final int? overlayId = await _methodChannel.invokeMethod<int>(
        'createOverlay',
        {
          'id': id,
          'options': options.toJson(),
          'entrypoint': entrypoint,
        },
      );

      if (overlayId != null) {
        log('Created overlay: $overlayId');
      }

      return overlayId;
    } on PlatformException catch (error) {
      log("Error creating overlay: $error");
      return null;
    }
  }

  /// Show an existing overlay by ID
  static Future<bool> showOverlay(int overlayId) async {
    try {
      return await _methodChannel.invokeMethod<bool>(
            'showOverlay',
            {'overlayId': overlayId},
          ) ??
          false;
    } on PlatformException catch (error) {
      log("Error showing overlay: $error");
      return false;
    }
  }

  /// Hide an existing overlay by ID
  static Future<bool> hideOverlay(int overlayId) async {
    try {
      return await _methodChannel.invokeMethod<bool>(
            'hideOverlay',
            {'overlayId': overlayId},
          ) ??
          false;
    } on PlatformException catch (error) {
      log("Error hiding overlay: $error");
      return false;
    }
  }

  /// Remove an overlay by ID
  static Future<bool> removeOverlay(int overlayId) async {
    try {
      return await _methodChannel.invokeMethod<bool>(
            'removeOverlay',
            {'overlayId': overlayId},
          ) ??
          false;
    } on PlatformException catch (error) {
      log("Error removing overlay: $error");
      return false;
    }
  }

  /// Update overlay position
  static Future<bool> moveOverlay(int overlayId, int x, int y) async {
    try {
      return await _methodChannel.invokeMethod<bool>(
            'moveOverlay',
            {
              'overlayId': overlayId,
              'x': x,
              'y': y,
            },
          ) ??
          false;
    } on PlatformException catch (error) {
      log("Error moving overlay: $error");
      return false;
    }
  }

  /// Update overlay size
  static Future<bool> resizeOverlay(int overlayId, int width, int height) async {
    try {
      return await _methodChannel.invokeMethod<bool>(
            'resizeOverlay',
            {
              'overlayId': overlayId,
              'width': width,
              'height': height,
            },
          ) ??
          false;
    } on PlatformException catch (error) {
      log("Error resizing overlay: $error");
      return false;
    }
  }

  /// Update overlay configuration
  static Future<bool> updateOverlayOptions(int overlayId, OverlayOptions options) async {
    try {
      return await _methodChannel.invokeMethod<bool>(
            'updateOverlayOptions',
            {
              'overlayId': overlayId,
              'options': options.toJson(),
            },
          ) ??
          false;
    } on PlatformException catch (error) {
      log("Error updating overlay options: $error");
      return false;
    }
  }

  /// Get information about an overlay
  static Future<OverlayInfo?> getOverlayInfo(int overlayId) async {
    try {
      final result = await _methodChannel.invokeMethod('getOverlayInfo', {'overlayId': overlayId});
      if (result != null) {
        return OverlayInfo.fromJson(Map<String, dynamic>.from(result));
      }
      return null;
    } on PlatformException catch (error) {
      log("Error getting overlay info: $error");
      return null;
    }
  }

  /// Get all active overlays
  static Future<List<OverlayInfo>> getAllOverlays() async {
    try {
      final List<dynamic>? result = await _methodChannel.invokeMethod<List<dynamic>>('getAllOverlays');
      if (result != null) {
        return result.map((overlayData) => OverlayInfo.fromJson(Map<String, dynamic>.from(overlayData))).toList();
      }
      return [];
    } on PlatformException catch (error) {
      log("Error getting all overlays: $error");
      return [];
    }
  }

  /// Get all active overlay IDs
  /// Returns a list of integers representing the IDs of all currently active overlays
  static Future<List<int>> getAllOverlayIds() async {
    try {
      final overlays = await getAllOverlays();
      return overlays.map((overlay) => overlay.id).toList();
    } on PlatformException catch (error) {
      log("Error getting overlay IDs: $error");
      return [];
    }
  }

  /// Refresh a specific overlay's Flutter engine
  /// This recreates the engine with the same entrypoint, useful for development hot reload
  static Future<bool> refreshOverlayEngine(int overlayId) async {
    try {
      return await _methodChannel.invokeMethod<bool>(
            'refreshOverlayEngine',
            {'overlayId': overlayId},
          ) ??
          false;
    } on PlatformException catch (error) {
      log("Error refreshing overlay engine: $error");
      return false;
    }
  }

  /// Refresh all active overlay Flutter engines
  /// This recreates all engines with their respective entrypoints, useful for development
  static Future<bool> refreshAllOverlayEngines() async {
    try {
      return await _methodChannel.invokeMethod<bool>('refreshAllOverlayEngines') ?? false;
    } on PlatformException catch (error) {
      log("Error refreshing all overlay engines: $error");
      return false;
    }
  }

  /// Remove all overlays
  static Future<bool> removeAllOverlays() async {
    try {
      return await _methodChannel.invokeMethod<bool>('removeAllOverlays') ?? false;
    } on PlatformException catch (error) {
      log("Error removing all overlays: $error");
      return false;
    }
  }
}
