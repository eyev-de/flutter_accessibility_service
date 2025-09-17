import 'dart:async';
import 'dart:convert';
import 'dart:developer';

import 'package:flutter/material.dart';
import 'package:flutter_accessibility_service/config/overlay_gravity.dart';
import 'package:flutter_accessibility_service/flutter_accessibility_service.dart';

import 'package:collection/collection.dart';

@pragma("vm:entry-point")
void accessibilityOverlay() {
  runApp(
    const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: OverlayWidget(),
    ),
  );
}

class OverlayWidget extends StatefulWidget {
  const OverlayWidget({Key? key}) : super(key: key);

  @override
  State<OverlayWidget> createState() => _OverlayWidgetState();
}

class _OverlayWidgetState extends State<OverlayWidget> {
  List<String> receivedMessages = [];
  int messageCount = 0;

  @override
  void initState() {
    super.initState();
    setupOverlayMessageListener();
  }

  void setupOverlayMessageListener() {
    // Set up method handler similar to desktop multi-window plugin
    FlutterAccessibilityService.setMethodHandler((call, fromIndex) async {
      print('Received message: $call.method');
      if (call.method == 'onGaze') {
        final message = call.arguments as String;
        setState(() {
          receivedMessages.insert(0, 'From $fromIndex: $message');
          if (receivedMessages.length > 5) {
            receivedMessages.removeLast();
          }
        });
        return true;
      } else if (call.method == 'onPositioning') {
        final message = call.arguments as String;
        setState(() {
          receivedMessages.insert(0, 'From $fromIndex: $message');
          if (receivedMessages.length > 5) {
            receivedMessages.removeLast();
          }
        });
        return true;
      }
      return null;
    });
  }

  Future<void> sendMessageToMainApp() async {
    try {
      messageCount++;
      final message = {
        'x': '12312',
        'y': '12312',
      };

      await FlutterAccessibilityService.invokeMethod(0, 'onGaze', jsonEncode(message));
    } catch (e) {
      log('Error sending message from overlay: $e');
    }
  }

  Future<void> sendMessageToOtherOverlay() async {
    try {
      messageCount++;
      final message = {
        'x': '12312',
        'y': '12312',
      };

      // Try to send to overlay with ID 2 (assuming this is overlay 1)
      await FlutterAccessibilityService.invokeMethod(2, 'onGaze', jsonEncode(message));
    } catch (e) {
      log('Error sending message to other overlay: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.blue.withValues(alpha: 0.8),
      child: Container(
        padding: const EdgeInsets.all(8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Overlay',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
            ),
            if (receivedMessages.isNotEmpty) ...[
              const SizedBox(height: 4),
              ...receivedMessages.take(5).map((msg) => Text(
                    msg.length > 30 ? '${msg.substring(0, 30)}...' : msg,
                    style: const TextStyle(color: Colors.white70, fontSize: 10),
                  )),
            ],
            const SizedBox(height: 8),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ElevatedButton(
                  onPressed: sendMessageToMainApp,
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.all(4),
                    minimumSize: const Size(50, 30),
                  ),
                  child: const Text('Reply', style: TextStyle(fontSize: 9)),
                ),
                const SizedBox(width: 4),
                ElevatedButton(
                  onPressed: sendMessageToOtherOverlay,
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.all(4),
                    minimumSize: const Size(50, 30),
                  ),
                  child: const Text('→Overlay', style: TextStyle(fontSize: 8)),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  StreamSubscription<AccessibilityEvent>? _subscription;
  StreamSubscription<Map<String, dynamic>>? _messageSubscription;
  List<AccessibilityEvent?> events = [];
  List<String> receivedMessages = [];
  DateTime eventDateTime = DateTime.now();
  bool foundSearchField = false;
  bool setText = false;
  bool clickFirstSearch = false;

  // Multiple overlay management
  List<OverlayInfo> activeOverlays = [];
  int? selectedOverlayToRemove;
  int nextOverlayId = 1; // Counter for unique overlay IDs

  @override
  void initState() {
    super.initState();
    checkAccessibilityPermissions();
    refreshOverlayList();
    setupMessageListener();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _messageSubscription?.cancel();
    super.dispose();
  }

  Future<void> checkAccessibilityPermissions() async {
    try {
      final bool hasPermission = await FlutterAccessibilityService.isAccessibilityPermissionEnabled();
      if (!hasPermission) {
        log('Accessibility permission not granted - requesting permission');
        // Optionally auto-request or show a persistent notification
        // await FlutterAccessibilityService.requestAccessibilityPermission();
      } else {
        log('Accessibility permission is active');
      }
    } catch (e) {
      log('Error checking accessibility permissions: $e');
    }
  }

  Future<void> setupMessageListener() async {
    try {
      // Listen for incoming messages
      _messageSubscription = FlutterAccessibilityService.messageStream.listen((messageData) {
        log('Message received: $messageData');
        setState(() {
          receivedMessages.insert(0, '$messageData');
          // Keep only last 10 messages
          if (receivedMessages.length > 10) {
            receivedMessages.removeLast();
          }
        });
      });

      log('Message listener set up for main app');
    } catch (e) {
      log('Error setting up message listener: $e');
    }
  }

  Future<void> sendTestMessageToOverlay(int overlayId) async {
    if (activeOverlays.isEmpty) {
      log('No overlays to send message to');
      return;
    }

    try {
      final message = {
        'x': '12312',
        'y': '12312',
      };

      final success = await FlutterAccessibilityService.invokeMethod(overlayId, 'onGaze', message);
      log('Message sent to overlay $overlayId: $success');
    } catch (e) {
      log('Error sending message: $e');
    }
  }

  Future<void> broadcastTestMessage() async {
    if (activeOverlays.isEmpty) {
      log('No overlays to broadcast to');
      return;
    }

    try {
      final message = {
        'left': {
          'x': '12312',
          'y': '12312',
        },
        'right': {
          'x': '12312',
          'y': '12312',
        },
      };

      // Send to all active overlays
      for (final overlay in activeOverlays) {
        final success = await FlutterAccessibilityService.invokeMethod(overlay.id, 'onPositioning', message);
        log('Broadcast message sent to overlay ${overlay.id}: $success');
      }
    } catch (e) {
      log('Error broadcasting message: $e');
    }
  }

  Future<void> refreshOverlayList() async {
    try {
      log('Calling FlutterAccessibilityService.getAllOverlays()...');
      final overlays = await FlutterAccessibilityService.getAllOverlays();
      log('Got ${overlays.length} overlays from native code');
      for (var overlay in overlays) {
        log('Overlay: id=${overlay.id}, visible=${overlay.isVisible}');
      }
      setState(() {
        activeOverlays = overlays;
      });
    } catch (e) {
      log('Error refreshing overlay list: $e');
    }
  }

  Future<void> moveRandomOverlay() async {
    log('moveRandomOverlay called - activeOverlays.length: ${activeOverlays.length}');

    if (activeOverlays.isEmpty) {
      log('No overlays available to move - refreshing overlay list first...');
      await refreshOverlayList();
      log('After refresh - activeOverlays.length: ${activeOverlays.length}');

      if (activeOverlays.isEmpty) {
        log('Still no overlays available after refresh');
        return;
      }
    }

    try {
      // Find a visible overlay to move
      final visibleOverlays = activeOverlays.where((o) => o.isVisible).toList();
      log('Found ${visibleOverlays.length} visible overlays out of ${activeOverlays.length} total');

      if (visibleOverlays.isEmpty) {
        log('No visible overlays to move');
        // Log all overlay states for debugging
        for (var overlay in activeOverlays) {
          log('Overlay ${overlay.id}: visible=${overlay.isVisible}');
        }
        return;
      }

      final overlay = visibleOverlays.first;
      final newX = (DateTime.now().millisecond % 300) + 50;
      final newY = (DateTime.now().millisecond % 400) + 100;

      log('Attempting to move overlay ${overlay.id}... to ($newX, $newY)');
      final success = await FlutterAccessibilityService.moveOverlay(overlay.id, newX, newY);

      if (success) {
        log('Successfully moved overlay to ($newX, $newY)');
        await refreshOverlayList();
      } else {
        log('Failed to move overlay');
      }
    } catch (e) {
      log('Error moving overlay: $e');
    }
  }

  Future<void> createOverlay() async {
    try {
      // Use incrementing integer IDs to avoid duplicates
      final displayMetrics = await FlutterAccessibilityService.getDisplayMetrics();
      int width = displayMetrics != null ? (displayMetrics.widthPixels * 0.3).round() : 300;
      int height = displayMetrics != null ? (displayMetrics.heightPixels * 0.3).round() : 200;
      int x = 0;
      int y = displayMetrics != null ? (displayMetrics.heightPixels).round() - height : 200;
      if (nextOverlayId == 2) {
        x = displayMetrics != null ? (displayMetrics.widthPixels * 0.3).round() + 10 : 300;
      }
      final overlayId = await FlutterAccessibilityService.createOverlay(
        nextOverlayId,
        options: OverlayOptions(
          width: width,
          height: height,
          x: x,
          y: y,
          gravity: OverlayGravity.top,
        ),
        entrypoint: 'accessibilityOverlay',
      );

      if (overlayId != null) {
        nextOverlayId++; // Increment for next overlay
        await FlutterAccessibilityService.showOverlay(overlayId);
        await refreshOverlayList();
        log('Created and shown overlay: $overlayId');
      } else {
        log('Failed to create overlay - ID $nextOverlayId may already exist');
      }
    } catch (e) {
      log('Error creating overlay: $e');
    }
  }

  Future<void> removeSelectedOverlay() async {
    if (selectedOverlayToRemove == null) return;

    try {
      final success = await FlutterAccessibilityService.removeOverlay(selectedOverlayToRemove!);
      if (success) {
        setState(() {
          selectedOverlayToRemove = null;
        });
        await refreshOverlayList();
        log('Removed overlay: $selectedOverlayToRemove');
      } else {
        log('Failed to remove overlay: $selectedOverlayToRemove');
      }
    } catch (e) {
      log('Error removing overlay: $e');
    }
  }

  Future<void> refreshSelectedOverlay() async {
    if (selectedOverlayToRemove == null) return;

    try {
      log('Refreshing engine for overlay ${selectedOverlayToRemove!}...');
      final success = await FlutterAccessibilityService.refreshOverlayEngine(selectedOverlayToRemove!);
      if (success) {
        await refreshOverlayList();
        log('Successfully refreshed overlay engine');
      } else {
        log('Failed to refresh overlay engine');
      }
    } catch (e) {
      log('Error refreshing overlay engine: $e');
    }
  }

  Future<void> refreshAllOverlays() async {
    if (activeOverlays.isEmpty) {
      log('No overlays to refresh');
      return;
    }

    try {
      log('Refreshing all overlay engines...');
      final success = await FlutterAccessibilityService.refreshAllOverlayEngines();
      if (success) {
        await refreshOverlayList();
        log('Successfully refreshed all overlay engines');
      } else {
        log('Failed to refresh some overlay engines');
      }
    } catch (e) {
      log('Error refreshing all overlay engines: $e');
    }
  }

  Future<void> removeAllTestOverlays() async {
    try {
      await FlutterAccessibilityService.removeAllOverlays();
      selectedOverlayToRemove = null;
      await refreshOverlayList();
      log('Removed all overlays');
    } catch (e) {
      log('Error removing overlays: $e');
    }
  }

  void handleAccessibilityStream() {
    foundSearchField = false;
    setText = false;
    if (_subscription?.isPaused ?? false) {
      _subscription?.resume();
      return;
    }
    _subscription = FlutterAccessibilityService.accessStream.listen((event) async {
      setState(() {
        events.add(event);
      });
      // automateScroll(event);
      // log("$event");
      // automateWikipedia(event);
      handleOverlay(event);
    });
  }

  void handleOverlay(AccessibilityEvent event) async {
    // if (event.packageName!.contains('youtube')) {
    //   log('$event');
    // }
    // if (event.packageName!.contains('youtube') || ((event.nodeId != null && event.nodeId!.contains('com.google.android.youtube'))) && event.isFocused!) {
    //   eventDateTime = event.eventTime!;
    //   await FlutterAccessibilityService.showOverlayWindow(
    //     const OverlayConfig().copyWith(
    //       height: 800,
    //       width: 800,
    //       gravity: OverlayGravity.bottomRight,
    //       clickableThrough: false,
    //     ),
    //   );
    // } else if (eventDateTime.difference(event.eventTime!).inSeconds.abs() > 2 ||
    //     (event.eventType == AccessibilityEventType.typeWindowStateChanged && !event.isFocused!)) {
    //   await FlutterAccessibilityService.hideOverlayWindow();
    // }
  }

  void automateWikipedia(AccessibilityEvent event) async {
    if (!event.packageName!.contains('wikipedia')) return;
    log('$event');
    final searchIt = [...event.subNodes!, event].firstWhereOrNull(
      (element) => element.text == 'Search Wikipedia' && element.isClickable!,
    );
    log("Searchable Field: $searchIt");
    if (searchIt != null) {
      await doAction(searchIt, NodeAction.actionClick);
      final editField = [...event.subNodes!, event].firstWhereOrNull(
        (element) => element.text == 'Search Wikipedia' && element.isEditable!,
      );
      if (editField != null) {
        await doAction(editField, NodeAction.actionSetText, "Lionel Messi");
      }
      final item = [...event.subNodes!, event].firstWhereOrNull(
        (element) => element.text == 'Messi–Ronaldo rivalry',
      );
      if (item != null) {
        await doAction(item, NodeAction.actionSelect);
      }
    }
  }

  Future<bool> doAction(
    AccessibilityEvent node,
    NodeAction action, [
    dynamic argument,
  ]) async {
    return await FlutterAccessibilityService.performAction(
      node,
      action,
      argument,
    );
  }

  void automateScroll(AccessibilityEvent node) async {
    if (!node.packageName!.contains('youtube')) return;
    log("$node");
    if (node.isFocused!) {
      final scrollableNode = findScrollableNode(node);
      log('$scrollableNode', name: 'SCROLLABLE- XX');
      if (scrollableNode != null) {
        await FlutterAccessibilityService.performAction(
          node,
          NodeAction.actionScrollForward,
        );
      }
    }
  }

  AccessibilityEvent? findScrollableNode(AccessibilityEvent rootNode) {
    if (rootNode.isScrollable! && rootNode.actions!.contains(NodeAction.actionScrollForward)) {
      return rootNode;
    }
    if (rootNode.subNodes!.isEmpty) return null;
    for (int i = 0; i < rootNode.subNodes!.length; i++) {
      final childNode = rootNode.subNodes![i];
      final scrollableChild = findScrollableNode(childNode);
      if (scrollableChild != null) {
        return scrollableChild;
      }
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Center(
          child: Column(
            children: [
              // Row 1: Basic accessibility functions
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    TextButton(
                      onPressed: () async {
                        await FlutterAccessibilityService.requestAccessibilityPermission();
                      },
                      child: const Text("Request Permission"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () async {
                        final bool res = await FlutterAccessibilityService.isAccessibilityPermissionEnabled();
                        log("Is enabled: $res");
                      },
                      child: const Text("Check Permission"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: handleAccessibilityStream,
                      child: const Text("Start Stream"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () {
                        _subscription?.cancel();
                      },
                      child: const Text("Stop Stream"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () async {
                        await FlutterAccessibilityService.performGlobalAction(
                          GlobalAction.globalActionTakeScreenshot,
                        );
                      },
                      child: const Text("Take ScreenShot"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () {
                        setState(() {
                          events.clear();
                        });
                      },
                      child: const Text("Clear Events"),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              // Row 2: Display info and overlay management
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    TextButton(
                      onPressed: () async {
                        final displayInfo = await FlutterAccessibilityService.getDisplayInfo();
                        log('Display Info: $displayInfo');
                      },
                      child: const Text("Display Info"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () async {
                        final displays = await FlutterAccessibilityService.getAllDisplays();
                        log('All Displays: $displays');
                      },
                      child: const Text("All Displays"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () async {
                        final metrics = await FlutterAccessibilityService.getDisplayMetrics();
                        log('Display Metrics: $metrics');
                      },
                      child: const Text("Display Metrics"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: createOverlay,
                      child: const Text("Create Overlay"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: moveRandomOverlay,
                      child: const Text("Move Overlay"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: removeAllTestOverlays,
                      child: const Text("Remove All"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: refreshOverlayList,
                      child: const Text("Refresh List"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: refreshAllOverlays,
                      child: const Text("Refresh All Engines"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () async {
                        if (activeOverlays.isNotEmpty) {
                          await sendTestMessageToOverlay(activeOverlays.first.id);
                        }
                      },
                      child: const Text("Send Test Message"),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: broadcastTestMessage,
                      child: const Text("Broadcast Message"),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              // Overlay management dropdown section
              if (activeOverlays.isNotEmpty)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Column(
                    children: [
                      Row(
                        children: [
                          const Text('Select Overlay: '),
                          const SizedBox(width: 8),
                          Expanded(
                            child: DropdownButton<int>(
                              value: selectedOverlayToRemove,
                              hint: const Text('Select overlay for actions'),
                              isExpanded: true,
                              items: activeOverlays.map((overlay) {
                                return DropdownMenuItem<int>(
                                  value: overlay.id,
                                  child: Text(
                                    '${overlay.id} (${overlay.isVisible ? 'Visible' : 'Hidden'})',
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                );
                              }).toList(),
                              onChanged: (int? value) {
                                setState(() {
                                  selectedOverlayToRemove = value;
                                });
                              },
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Expanded(
                            child: ElevatedButton(
                              onPressed: selectedOverlayToRemove != null ? refreshSelectedOverlay : null,
                              child: const Text('Refresh Engine'),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: ElevatedButton(
                              onPressed: selectedOverlayToRemove != null ? removeSelectedOverlay : null,
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.red.shade100,
                              ),
                              child: const Text('Remove Overlay'),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              Expanded(
                child: Column(
                  children: [
                    // Active overlays section
                    if (activeOverlays.isNotEmpty) ...[
                      Container(
                        padding: const EdgeInsets.all(8.0),
                        color: Colors.blue.withValues(alpha: 0.1),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Active Overlays (${activeOverlays.length})',
                              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                    fontWeight: FontWeight.bold,
                                  ),
                            ),
                            const SizedBox(height: 8),
                            ...activeOverlays
                                .map((overlay) => Padding(
                                      padding: const EdgeInsets.symmetric(vertical: 2),
                                      child: Row(
                                        children: [
                                          Expanded(
                                            child: Text(
                                              '${overlay.id}...',
                                              style: const TextStyle(fontSize: 12),
                                            ),
                                          ),
                                          Text(
                                            overlay.isVisible ? 'Visible' : 'Hidden',
                                            style: TextStyle(
                                              fontSize: 10,
                                              color: overlay.isVisible ? Colors.green : Colors.red,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ))
                                .toList(),
                          ],
                        ),
                      ),
                      const SizedBox(height: 8),
                    ],
                    // Messages section
                    if (receivedMessages.isNotEmpty) ...[
                      Container(
                        padding: const EdgeInsets.all(8.0),
                        color: Colors.green.withValues(alpha: 0.1),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Received Messages (${receivedMessages.length})',
                              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                    fontWeight: FontWeight.bold,
                                  ),
                            ),
                            const SizedBox(height: 8),
                            ...receivedMessages
                                .take(5) // Show only first 5 messages
                                .map((message) => Padding(
                                      padding: const EdgeInsets.symmetric(vertical: 2),
                                      child: Text(
                                        message,
                                        style: const TextStyle(fontSize: 12),
                                      ),
                                    ))
                                .toList(),
                          ],
                        ),
                      ),
                      const SizedBox(height: 8),
                    ],
                    // Events section
                    Expanded(
                      child: ListView.builder(
                        shrinkWrap: true,
                        itemCount: events.length,
                        itemBuilder: (_, index) => ListTile(
                          title: Text(events[index]!.packageName!),
                          subtitle: Text(
                            (events[index]!.subNodes ?? []).map((e) => e.actions).expand((element) => element!).contains(NodeAction.actionClick)
                                ? 'Have Action to click'
                                : '',
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              )
            ],
          ),
        ),
      ),
    );
  }
}
