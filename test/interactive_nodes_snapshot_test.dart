import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter_accessibility_service/flutter_accessibility_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('decodeCompactInteractiveNodes', () {
    test('builds ids, bounds and flags like the legacy native map format', () {
      final ints = Int32List.fromList(<int>[
        10, 20, 110, 70, interactiveNodeFlagClickable | interactiveNodeFlagFocusable, 1, -1, 0, 0, //
        0, 0, 50, 50, interactiveNodeFlagScrollable | interactiveNodeFlagEditable | interactiveNodeFlagLongClickable, 2, -1, 0, 0, //
        5, 5, 6, 6, 0, -1, -1, 0, 0, //
      ]);
      final strings = <dynamic>[
        'com.app:id/play', 'android.widget.Button', 'Play', 'Play video', //
        null, 'android.widget.EditText', null, null, //
        null, null, null, 'desc', //
      ];
      final nodes = decodeCompactInteractiveNodes(ints, strings);
      expect(nodes, hasLength(3));

      expect(nodes[0].id, 'com.app:id/play@10,20,110,70');
      expect(nodes[0].bounds.width, 100);
      expect(nodes[0].bounds.height, 50);
      expect(nodes[0].isClickable, isTrue);
      expect(nodes[0].isFocusable, isTrue);
      expect(nodes[0].isLongClickable, isFalse);
      expect(nodes[0].isScrollable, isFalse);
      expect(nodes[0].isEditable, isFalse);
      expect(nodes[0].text, 'Play');
      expect(nodes[0].contentDescription, 'Play video');
      expect(nodes[0].windowType, 1);

      expect(nodes[1].id, 'android.widget.EditText@0,0,50,50');
      expect(nodes[1].isClickable, isFalse);
      expect(nodes[1].isLongClickable, isTrue);
      expect(nodes[1].isScrollable, isTrue);
      expect(nodes[1].isEditable, isTrue);
      expect(nodes[1].viewId, isNull);
      expect(nodes[1].windowType, 2);

      expect(nodes[2].id, 'node@5,5,6,6');
      expect(nodes[2].contentDescription, 'desc');
      expect(nodes[2].windowType, -1);
      for (final node in nodes) {
        expect(node.isWebContent, isFalse);
        expect(node.webRole, webRoleNone);
        expect(node.clickableScore, 0);
        expect(node.parentTargetId, isNull);
        expect(node.windowId, -1);
      }
    });

    test('stride is 9 ints per node', () {
      expect(interactiveNodeIntStride, 9);
      expect(interactiveNodeFlagWebContent, 32);
    });

    test('decodes the web flag, role and clickable score', () {
      final ints = Int32List.fromList(<int>[
        0, 0, 400, 300, interactiveNodeFlagClickable | interactiveNodeFlagWebContent, 1, -1, webRoleOther, 200, //
        10, 10, 200, 60, interactiveNodeFlagClickable | interactiveNodeFlagWebContent, 1, 0, webRoleLink, 300, //
        10, 70, 200, 120, interactiveNodeFlagFocusable | interactiveNodeFlagWebContent, 1, 0, webRoleButton, 0, //
      ]);
      final strings = <dynamic>[
        null, 'android.view.View', null, null, //
        null, 'android.view.View', 'Read more', null, //
        null, 'android.widget.Button', 'Buy', null, //
      ];
      final nodes = decodeCompactInteractiveNodes(ints, strings);
      expect(nodes[0].isWebContent, isTrue);
      expect(nodes[0].webRole, webRoleOther);
      expect(nodes[0].clickableScore, 200);
      expect(nodes[1].webRole, webRoleLink);
      expect(nodes[1].clickableScore, 300);
      expect(nodes[1].isClickable, isTrue);
      expect(nodes[2].webRole, webRoleButton);
      expect(nodes[2].isClickable, isFalse);
      expect(nodes[2].isFocusable, isTrue);
      expect(nodes[2].clickableScore, 0);
    });

    test('parentTargetId is the id of node[parentIndex], only for an earlier index', () {
      final ints = Int32List.fromList(<int>[
        0, 0, 100, 100, interactiveNodeFlagClickable, 1, -1, 0, 0, //
        10, 10, 50, 50, interactiveNodeFlagClickable, 1, 0, 0, 0, //
        20, 20, 30, 30, interactiveNodeFlagClickable, 1, 1, 0, 0, //
        60, 60, 70, 70, interactiveNodeFlagClickable, 1, 0, 0, 0, //
        1, 1, 2, 2, interactiveNodeFlagClickable, 1, 4, 0, 0, // own index: ignored
        3, 3, 4, 4, interactiveNodeFlagClickable, 1, 9, 0, 0, // later / out of range: ignored
      ]);
      final strings = <dynamic>[
        'a:id/card', null, null, null, //
        'a:id/inner', null, null, null, //
        'a:id/deep', null, null, null, //
        null, 'android.widget.Button', null, null, //
        null, null, null, null, //
        null, null, null, null, //
      ];
      final nodes = decodeCompactInteractiveNodes(ints, strings);
      expect(nodes[0].parentTargetId, isNull);
      expect(nodes[1].parentTargetId, 'a:id/card@0,0,100,100');
      expect(nodes[2].parentTargetId, 'a:id/inner@10,10,50,50');
      expect(nodes[3].parentTargetId, 'a:id/card@0,0,100,100');
      expect(nodes[4].parentTargetId, isNull);
      expect(nodes[5].parentTargetId, isNull);
    });

    test('windowId comes from the window node ranges', () {
      final ints = Int32List.fromList(<int>[
        0, 0, 10, 10, interactiveNodeFlagClickable, 1, -1, 0, 0, //
        0, 0, 20, 20, interactiveNodeFlagClickable, 1, -1, 0, 0, //
        0, 0, 30, 30, interactiveNodeFlagClickable, 2, -1, 0, 0, //
        0, 0, 40, 40, interactiveNodeFlagClickable, 2, -1, 0, 0, //
      ]);
      final strings = List<dynamic>.filled(16, null);
      final windows = decodeSnapshotWindows(Int32List.fromList(<int>[
        12, 1, 0, 2, 0, 0, 0, 1080, 2400, //
        5, 4, 2, 0, 1, 0, 0, 0, 0, // truncated, no nodes
        7, 2, 2, 1, 0, 0, 0, 1080, 300, //
      ]));
      final nodes = decodeCompactInteractiveNodes(ints, strings, windows: windows);
      expect(nodes.map((n) => n.windowId).toList(), <int>[12, 12, 7, -1]);
    });

    test('throws FormatException on an ints length that is not a multiple of the stride', () {
      expect(() => decodeCompactInteractiveNodes(Int32List(10), List<dynamic>.filled(4, null)), throwsFormatException);
    });

    test('throws FormatException when ints and strings describe different node counts', () {
      expect(() => decodeCompactInteractiveNodes(Int32List(18), List<dynamic>.filled(4, null)), throwsFormatException);
    });
  });

  group('isInteractiveWebRole', () {
    test('ids 1..14 are interactive, heading / image / other / none are not', () {
      for (var role = webRoleLink; role <= webRoleDisclosure; role++) {
        expect(isInteractiveWebRole(role), isTrue, reason: 'role $role');
      }
      expect(isInteractiveWebRole(webRoleNone), isFalse);
      expect(isInteractiveWebRole(webRoleHeading), isFalse);
      expect(isInteractiveWebRole(webRoleImage), isFalse);
      expect(isInteractiveWebRole(webRoleOther), isFalse);
      expect(isInteractiveWebRole(-1), isFalse);
    });
  });

  group('decodeSnapshotWindows', () {
    test('decodes 9 ints per window', () {
      final windows = decodeSnapshotWindows(Int32List.fromList(<int>[
        12, 1, 0, 3, 0, 0, 0, 1080, 2400, //
        7, 2, 3, 0, 1, -5, 10, 200, 300, //
      ]));
      expect(windows, hasLength(2));
      expect(windows[0].windowId, 12);
      expect(windows[0].windowType, 1);
      expect(windows[0].nodeStart, 0);
      expect(windows[0].nodeCount, 3);
      expect(windows[0].truncated, isFalse);
      expect(windows[0].right, 1080);
      expect(windows[0].bottom, 2400);
      expect(windows[1].windowId, 7);
      expect(windows[1].nodeStart, 3);
      expect(windows[1].nodeCount, 0);
      expect(windows[1].truncated, isTrue);
      expect(windows[1].left, -5);
      expect(windows[1].top, 10);
      expect(windows[1].right, 200);
      expect(windows[1].bottom, 300);
    });

    test('empty ints decode to no windows', () {
      expect(decodeSnapshotWindows(Int32List(0)), isEmpty);
    });

    test('throws FormatException on a length that is not a multiple of the stride', () {
      expect(snapshotWindowIntStride, 9);
      expect(() => decodeSnapshotWindows(Int32List(10)), throwsFormatException);
    });
  });

  group('InteractiveNodesSnapshot.fromMap', () {
    test('decodes windowInts into windowInfos', () {
      final snapshot = InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
        'unchanged': false,
        'signature': 5,
        'ints': Int32List.fromList(<int>[1, 2, 3, 4, interactiveNodeFlagClickable, 1, -1, 0, 0]),
        'strings': <dynamic>['v', 'c', null, null],
        'windowInts': Int32List.fromList(<int>[3, 1, 0, 1, 1, 0, 0, 100, 100, 4, 2, 1, 0, 1, 0, 0, 0, 0]),
        'truncated': true,
      });
      expect(snapshot.nodes.single.windowId, 3);
      expect(snapshot.windowInfos, hasLength(2));
      expect(snapshot.windowInfos[0].windowId, 3);
      expect(snapshot.windowInfos[0].nodeCount, 1);
      expect(snapshot.windowInfos[0].truncated, isTrue);
      expect(snapshot.windowInfos[1].windowId, 4);
      expect(snapshot.windowInfos[1].nodeStart, 1);
      expect(snapshot.windowInfos[1].nodeCount, 0);
    });

    test('accepts windowInts as a plain int list', () {
      final snapshot = InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
        'unchanged': false,
        'signature': 5,
        'ints': Int32List(0),
        'strings': <dynamic>[],
        'windowInts': <dynamic>[9, -1, 0, 0, 0, 0, 0, 10, 10],
      });
      expect(snapshot.windowInfos.single.windowId, 9);
      expect(snapshot.windowInfos.single.windowType, -1);
    });

    test('without windowInts (older native side) windowInfos is empty', () {
      final snapshot = InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
        'unchanged': false,
        'signature': 5,
        'ints': Int32List.fromList(<int>[1, 2, 3, 4, interactiveNodeFlagClickable, 1, -1, 0, 0]),
        'strings': <dynamic>['v', 'c', null, null],
      });
      expect(snapshot.nodes, hasLength(1));
      expect(snapshot.nodes.single.windowId, -1);
      expect(snapshot.windowInfos, isEmpty);
    });

    test('unchanged ignores windowInts', () {
      final snapshot = InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
        'unchanged': true,
        'signature': 5,
        'windowInts': Int32List.fromList(<int>[3, 1, 0, 1, 1, 0, 0, 100, 100]),
      });
      expect(snapshot.windowInfos, isEmpty);
      expect(snapshot.nodes, isEmpty);
    });

    test('bad windowInts length throws FormatException', () {
      expect(
        () => InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
          'unchanged': false,
          'signature': 1,
          'ints': Int32List(0),
          'strings': <dynamic>[],
          'windowInts': Int32List(8),
        }),
        throwsFormatException,
      );
    });

    test('decodes a changed snapshot with metadata', () {
      final snapshot = InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
        'unchanged': false,
        'signature': -1234567890123,
        'ints': Int32List.fromList(<int>[1, 2, 3, 4, interactiveNodeFlagClickable, 1, -1, 0, 0]),
        'strings': <dynamic>['v', 'c', 't', 'd'],
        'structuralGeneration': 7,
        'contentGeneration': 42,
        'walkMs': 12,
        'windows': 3,
        'visited': 500,
        'actionable': 1,
        'pruned': 9,
        'depthCapped': 2,
        'truncated': true,
      });
      expect(snapshot.unchanged, isFalse);
      expect(snapshot.signature, -1234567890123);
      expect(snapshot.nodes.single.id, 'v@1,2,3,4');
      expect(snapshot.structuralGeneration, 7);
      expect(snapshot.contentGeneration, 42);
      expect(snapshot.walkMs, 12);
      expect(snapshot.windows, 3);
      expect(snapshot.visited, 500);
      expect(snapshot.actionable, 1);
      expect(snapshot.pruned, 9);
      expect(snapshot.depthCapped, 2);
      expect(snapshot.truncated, isTrue);
    });

    test('unchanged snapshot carries no nodes', () {
      final snapshot = InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{'unchanged': true, 'signature': 99, 'structuralGeneration': 1, 'contentGeneration': 2});
      expect(snapshot.unchanged, isTrue);
      expect(snapshot.signature, 99);
      expect(snapshot.nodes, isEmpty);
      expect(snapshot.contentGeneration, 2);
    });

    test('accepts a plain int list instead of an Int32List', () {
      final snapshot = InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
        'unchanged': false,
        'signature': 1,
        'ints': <dynamic>[0, 0, 10, 10, 0, 1, -1, 0, 0],
        'strings': <dynamic>[null, null, null, null],
      });
      expect(snapshot.nodes.single.id, 'node@0,0,10,10');
    });

    test('length mismatch throws FormatException', () {
      expect(
        () => InteractiveNodesSnapshot.fromMap(<dynamic, dynamic>{
          'unchanged': false,
          'signature': 1,
          'ints': Int32List(9),
          'strings': <dynamic>[null],
        }),
        throwsFormatException,
      );
    });
  });

  group('channel round trip', () {
    const channel = MethodChannel('x-slayer/accessibility_channel');
    final calls = <MethodCall>[];

    setUp(() {
      calls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (MethodCall call) async {
        calls.add(call);
        switch (call.method) {
          case 'getNodeChangeState':
            return <String, dynamic>{'structural': 3, 'content': 1 << 40, 'connected': true};
          case 'refreshInteractiveNode':
            final args = call.arguments as Map<dynamic, dynamic>;
            return args['id'] as String;
          case 'getInteractiveNodeSnapshot':
            final args = call.arguments as Map<dynamic, dynamic>;
            if (args['lastSignature'] == 555) {
              return <String, dynamic>{'unchanged': true, 'signature': 555, 'structuralGeneration': 3, 'contentGeneration': 4};
            }
            return <String, dynamic>{
              'unchanged': false,
              'signature': 555,
              'ints': Int32List.fromList(<int>[1, 1, 9, 9, interactiveNodeFlagClickable, 1, -1, 0, 0]),
              'strings': <dynamic>['id', 'cls', null, null],
              'structuralGeneration': 3,
              'contentGeneration': 4,
            };
        }
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    test('getNodeChangeState decodes the counters', () async {
      final state = await FlutterAccessibilityService.getNodeChangeState();
      expect(state, isNotNull);
      expect(state!.structural, 3);
      expect(state.content, 1 << 40);
      expect(state.connected, isTrue);
    });

    test('getNodeChangeState returns null without a native handler', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
      expect(await FlutterAccessibilityService.getNodeChangeState(), isNull);
    });

    test('getInteractiveNodeSnapshot sends the arguments and decodes nodes, then unchanged', () async {
      final first = await FlutterAccessibilityService.getInteractiveNodeSnapshot();
      expect(calls.last.arguments, <String, dynamic>{
        'lastSignature': null,
        'maxVisitedPerWindow': 2000,
        'maxActionablePerWindow': 400,
        'timeBudgetMs': 1000,
        'windowBudgetMs': 600,
        'prefetchStrategy': snapshotPrefetchDepthFirst,
      });
      expect(snapshotPrefetchDepthFirst, 40);
      expect(snapshotPrefetchPlatformDefault, -1);
      expect(first.unchanged, isFalse);
      expect(first.nodes.single.id, 'id@1,1,9,9');
      expect(first.nodes.single.isClickable, isTrue);

      final second = await FlutterAccessibilityService.getInteractiveNodeSnapshot(lastSignature: first.signature, timeBudgetMs: 100);
      expect((calls.last.arguments as Map<dynamic, dynamic>)['lastSignature'], 555);
      expect((calls.last.arguments as Map<dynamic, dynamic>)['timeBudgetMs'], 100);
      expect(second.unchanged, isTrue);
      expect(second.nodes, isEmpty);
    });

    test('getInteractiveNodeSnapshot passes windowBudgetMs and prefetchStrategy through', () async {
      await FlutterAccessibilityService.getInteractiveNodeSnapshot(windowBudgetMs: 250, prefetchStrategy: snapshotPrefetchPlatformDefault);
      final args = calls.last.arguments as Map<dynamic, dynamic>;
      expect(args['windowBudgetMs'], 250);
      expect(args['prefetchStrategy'], -1);
      expect(args['timeBudgetMs'], 1000);
    });
      test('refreshInteractiveNode sends id and point and maps every answer', () async {
      for (final result in NodeRefreshResult.values) {
        final answer = await FlutterAccessibilityService.refreshInteractiveNode(id: result.name, x: 12.5, y: 30);
        expect(calls.last.method, 'refreshInteractiveNode');
        expect(calls.last.arguments, <String, dynamic>{'id': result.name, 'x': 12.5, 'y': 30.0});
        expect(answer, result);
      }
      expect(await FlutterAccessibilityService.refreshInteractiveNode(id: 'bogus', x: 0, y: 0), NodeRefreshResult.error);
    });

    test('refreshInteractiveNode returns error without a native handler', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
      expect(await FlutterAccessibilityService.refreshInteractiveNode(id: 'a@1,1,2,2', x: 1, y: 1), NodeRefreshResult.error);
    });

    test('refreshInteractiveNode returns error on a PlatformException', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (MethodCall call) async {
        throw PlatformException(code: 'boom');
      });
      expect(await FlutterAccessibilityService.refreshInteractiveNode(id: 'a@1,1,2,2', x: 1, y: 1), NodeRefreshResult.error);
    });
  });

  group('NodeRefreshResult.fromName', () {
    test('maps every name, unknown and null to error', () {
      expect(NodeRefreshResult.fromName('ok'), NodeRefreshResult.ok);
      expect(NodeRefreshResult.fromName('missing'), NodeRefreshResult.missing);
      expect(NodeRefreshResult.fromName('gone'), NodeRefreshResult.gone);
      expect(NodeRefreshResult.fromName('hidden'), NodeRefreshResult.hidden);
      expect(NodeRefreshResult.fromName('moved'), NodeRefreshResult.moved);
      expect(NodeRefreshResult.fromName('error'), NodeRefreshResult.error);
      expect(NodeRefreshResult.fromName('OK'), NodeRefreshResult.error);
      expect(NodeRefreshResult.fromName('whatever'), NodeRefreshResult.error);
      expect(NodeRefreshResult.fromName(null), NodeRefreshResult.error);
      expect(NodeRefreshResult.ok.isOk, isTrue);
      expect(NodeRefreshResult.moved.isOk, isFalse);
    });
  });

  group('nodeChangeStream', () {
    const eventChannel = EventChannel('x-slayer/accessibility_node_changes');

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockStreamHandler(eventChannel, null);
    });

    test('decodes pushed counter maps and skips non-map events', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockStreamHandler(
        eventChannel,
        MockStreamHandler.inline(
          onListen: (Object? arguments, MockStreamHandlerEventSink events) {
            events.success(<String, dynamic>{'structural': 1, 'content': 2, 'connected': true});
            events.success('not a map');
            events.success(<String, dynamic>{'structural': 1, 'content': 1 << 40, 'connected': false});
            events.endOfStream();
          },
        ),
      );
      final states = await FlutterAccessibilityService.nodeChangeStream.toList();
      expect(states, hasLength(2));
      expect(states[0].structural, 1);
      expect(states[0].content, 2);
      expect(states[0].connected, isTrue);
      expect(states[1].content, 1 << 40);
      expect(states[1].connected, isFalse);
    });
  });
}
