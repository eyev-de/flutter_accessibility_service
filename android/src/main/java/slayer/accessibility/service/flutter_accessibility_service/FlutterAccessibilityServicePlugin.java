package slayer.accessibility.service.flutter_accessibility_service;

import static slayer.accessibility.service.flutter_accessibility_service.Constants.*;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

/**
 * FlutterAccessibilityServicePlugin
 */
public class FlutterAccessibilityServicePlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.ActivityResultListener, EventChannel.StreamHandler {


    private static final String CHANNEL_TAG = "x-slayer/accessibility_channel";
    private static final String EVENT_TAG = "x-slayer/accessibility_event";
    private static final String MESSAGE_EVENT_TAG = "x-slayer/accessibility_message";
    public static final String CACHED_TAG = "cashedAccessibilityEngine";


    private MethodChannel channel;
    private AccessibilityReceiver accessibilityReceiver;
    private EventChannel eventChannel;
    private EventChannel messageEventChannel;
    private MessageReceiver messageReceiver;
    private Context context;
    private Activity mActivity;
    private boolean supportOverlay = false;
    private boolean isReceiverRegistered = false;
    private boolean isMessageReceiverRegistered = false;
    private Result pendingResult;
    final int REQUEST_CODE_FOR_ACCESSIBILITY = 167;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        context = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), CHANNEL_TAG);
        channel.setMethodCallHandler(this);
        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), EVENT_TAG);
        eventChannel.setStreamHandler(this);
        messageEventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), MESSAGE_EVENT_TAG);
        messageEventChannel.setStreamHandler(new MessageStreamHandler());
    }

    private final BroadcastReceiver actionsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            List<Integer> actions = intent.getIntegerArrayListExtra("actions");
            pendingResult.success(actions);
        }
    };


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("isAccessibilityPermissionEnabled")) {
            result.success(Utils.isAccessibilitySettingsOn(context));
        } else if (call.method.equals("requestAccessibilityPermission")) {
            pendingResult = result;  // Only set pendingResult for async operations
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            mActivity.startActivityForResult(intent, REQUEST_CODE_FOR_ACCESSIBILITY);
        } else if (call.method.equals("getSystemActions")) {
            if (Utils.isAccessibilitySettingsOn(context)) {
                IntentFilter filter = new IntentFilter(BROD_SYSTEM_GLOBAL_ACTIONS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    context.registerReceiver(actionsReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(actionsReceiver, filter);
                }
                isReceiverRegistered = true;
                Intent intent = new Intent(context, AccessibilityListener.class);
                intent.putExtra(INTENT_SYSTEM_GLOBAL_ACTIONS, true);
                context.startService(intent);
            } else {
                result.error("SDK_INT_ERROR", "Invalid SDK_INT", null);
            }
        } else if (call.method.equals("performGlobalAction")) {
            Integer actionId = call.argument("action");
            if (Utils.isAccessibilitySettingsOn(context)) {
                final Intent i = new Intent(context, AccessibilityListener.class);
                i.putExtra(INTENT_GLOBAL_ACTION, true);
                i.putExtra(INTENT_GLOBAL_ACTION_ID, actionId);
                context.startService(i);
                result.success(true);
            } else {
                result.success(false);
            }
        } else if (call.method.equals("performActionById")) {
            String nodeId = call.argument("nodeId");
            Integer action = (Integer) call.argument("nodeAction");
            Object extras = call.argument("extras");
            Bundle arguments = Utils.bundleIdentifier(action, extras);
            AccessibilityNodeInfo nodeInfo = AccessibilityListener.getNodeInfo(nodeId);
            if (nodeInfo != null) {
                if (arguments == null) {
                    nodeInfo.performAction(action);
                } else {
                    nodeInfo.performAction(action, arguments);
                }
                result.success(true);
            } else {
                result.success(false);
            }
        } else if (call.method.equals("showOverlayWindow")) {
            if (!supportOverlay) {
                result.error("ERR:OVERLAY", "Add the overlay entry point to be able of using it", null);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Boolean clickableThrough = call.argument("clickableThrough");
                Integer width = call.argument("width");
                Integer height = call.argument("height");
                Integer gravity = call.argument("gravity");
                AccessibilityListener.showOverlay(width, height, gravity, clickableThrough);
                result.success(true);
            } else {
                result.success(false);
            }
        } else if (call.method.equals("hideOverlayWindow")) {
            AccessibilityListener.removeOverlay();
            result.success(true);
        } else if (call.method.equals("getDisplayInfo")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                result.success(Utils.getDisplayInformation(context));
            } else {
                result.error("API_LEVEL_ERROR", "Display information requires API level 17 or higher", null);
            }
        } else if (call.method.equals("getAllDisplays")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                result.success(Utils.getAllDisplays(context));
            } else {
                result.error("API_LEVEL_ERROR", "Multiple display support requires API level 17 or higher", null);
            }
        } else if (call.method.equals("getDisplayMetrics")) {
            result.success(Utils.getCurrentDisplayMetrics(context));
        } else if (call.method.equals("createOverlay")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Integer id = call.argument("id");
                @SuppressWarnings("unchecked")
                Map<String, Object> options = (Map<String, Object>) call.argument("options");
                String entrypoint = call.argument("entrypoint");
                
                if (id != null) {
                    Integer overlayId = AccessibilityListener.createOverlay(context, id, options, entrypoint);
                    result.success(overlayId);
                } else {
                    result.error("INVALID_ARGS", "Overlay ID is required", null);
                }
            } else {
                result.error("API_LEVEL_ERROR", "Multiple overlays require API level 22 or higher", null);
            }
        } else if (call.method.equals("showOverlay")) {
            Integer overlayId = call.argument("overlayId");
            if (overlayId != null) {
                boolean success = AccessibilityListener.showOverlayById(overlayId);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Overlay ID is required", null);
            }
        } else if (call.method.equals("hideOverlay")) {
            Integer overlayId = call.argument("overlayId");
            if (overlayId != null) {
                boolean success = AccessibilityListener.hideOverlayById(overlayId);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Overlay ID is required", null);
            }
        } else if (call.method.equals("removeOverlay")) {
            Integer overlayId = call.argument("overlayId");
            if (overlayId != null) {
                boolean success = AccessibilityListener.removeOverlayById(overlayId);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Overlay ID is required", null);
            }
        } else if (call.method.equals("moveOverlay")) {
            Integer overlayId = call.argument("overlayId");
            Integer x = call.argument("x");
            Integer y = call.argument("y");
            if (overlayId != null && x != null && y != null) {
                boolean success = AccessibilityListener.moveOverlayById(overlayId, x, y);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Overlay ID, x, and y are required", null);
            }
        } else if (call.method.equals("resizeOverlay")) {
            Integer overlayId = call.argument("overlayId");
            Integer width = call.argument("width");
            Integer height = call.argument("height");
            if (overlayId != null && width != null && height != null) {
                boolean success = AccessibilityListener.resizeOverlayById(overlayId, width, height);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Overlay ID, width, and height are required", null);
            }
        } else if (call.method.equals("updateOverlayOptions")) {
            Integer overlayId = call.argument("overlayId");
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map<String, Object>) call.argument("options");
            if (overlayId != null && options != null) {
                boolean success = AccessibilityListener.updateOverlayOptionsById(overlayId, options);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Overlay ID and options are required", null);
            }
        } else if (call.method.equals("getOverlayInfo")) {
            Integer overlayId = call.argument("overlayId");
            if (overlayId != null) {
                Map<String, Object> info = AccessibilityListener.getOverlayInfo(overlayId);
                result.success(info);
            } else {
                result.error("INVALID_ARGS", "Overlay ID is required", null);
            }
        } else if (call.method.equals("getAllOverlays")) {
            List<Map<String, Object>> overlays = AccessibilityListener.getAllOverlaysInfo();
            result.success(overlays);
        } else if (call.method.equals("removeAllOverlays")) {
            AccessibilityListener.removeAllOverlays();
            result.success(true);
        } else if (call.method.equals("refreshOverlayEngine")) {
            Integer overlayId = call.argument("overlayId");
            if (overlayId != null) {
                boolean success = AccessibilityListener.refreshEngineById(overlayId);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Overlay ID is required", null);
            }
        } else if (call.method.equals("refreshAllOverlayEngines")) {
            boolean success = AccessibilityListener.refreshAllEngines();
            result.success(success);
        } else if (call.method.equals("invokeMethod")) {
            Integer targetOverlayId = call.argument("targetOverlayId");
            String method = call.argument("method");
            String arguments = call.argument("arguments");
            if (targetOverlayId != null && arguments != null) {
                boolean success = AccessibilityListener.sendMessage(targetOverlayId, method, arguments);
                result.success(success);
            } else {
                result.error("INVALID_ARGS", "Target overlay ID and arguments are required", null);
            }
        } else if (call.method.equals("click")) {
            Double x = call.argument("x");
            Double y = call.argument("y");
            if (Utils.isAccessibilitySettingsOn(context)) {
                final Intent i = new Intent(context, AccessibilityListener.class);
                i.putExtra(INTENT_CLICK, true);  // Fixed: use INTENT_CLICK instead of INTENT_GLOBAL_ACTION
                i.putExtra(INTENT_CLICK_X, x);
                i.putExtra(INTENT_CLICK_Y, y);
                context.startService(i);
                result.success(true);
            } else {
                result.success(false);
            }
        } else if (call.method.equals("longPress")) {
            Double x = call.argument("x");
            Double y = call.argument("y");
            if (Utils.isAccessibilitySettingsOn(context)) {
                final Intent i = new Intent(context, AccessibilityListener.class);
                i.putExtra(INTENT_LONG_PRESS, true);
                i.putExtra(INTENT_LONG_PRESS_X, x);
                i.putExtra(INTENT_LONG_PRESS_Y, y);
                context.startService(i);
                result.success(true);
            } else {
                result.success(false);
            }
        } else if (call.method.equals("scroll")) {
            Double x = call.argument("x");
            Double y = call.argument("y");
            Double deltaX = call.argument("deltaX");
            Double deltaY = call.argument("deltaY");
            if (Utils.isAccessibilitySettingsOn(context)) {
                final Intent i = new Intent(context, AccessibilityListener.class);
                i.putExtra(INTENT_SCROLL, true);
                i.putExtra(INTENT_SCROLL_X, x);
                i.putExtra(INTENT_SCROLL_Y, y);
                i.putExtra(INTENT_SCROLL_DELTA_X, deltaX);
                i.putExtra(INTENT_SCROLL_DELTA_Y, deltaY);
                context.startService(i);
                result.success(true);
            } else {
                result.success(false);
            }
        } else if (call.method.equals("bringMainAppToForeground")) {
            try {
                // Use FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP to reuse existing
                // activity instance without recreating its surface/view hierarchy
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                        if (launchIntent != null) {
                            // CLEAR_TOP + SINGLE_TOP: delivers intent to existing instance
                            // This avoids the surface recreation that causes AccessibilityBridge NPE
                            launchIntent.setFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | 
                                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            );
                            
                            // Add extra to indicate this is a "bring to front" action
                            // The activity can check this and avoid heavy UI rebuilds
                            launchIntent.putExtra("BRING_TO_FRONT", true);
                            
                            context.startActivity(launchIntent);
                        }
                    } catch (Exception e) {
                        Log.e("AccessibilityPlugin", "Failed to start activity: " + e.getMessage());
                    }
                }, 100); // Wait for overlays to fully hide
                
                result.success(true);
            } catch (Exception e) {
                Log.e("AccessibilityPlugin", "Failed to bring main app to foreground: " + e.getMessage());
                result.success(false);
            }
        } else if (call.method.equals("launchApp")) {
            String packageName = call.argument("packageName");
            if (packageName != null && !packageName.isEmpty()) {
                try {
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        result.success(true);
                    } else {
                        result.success(false);
                    }
                } catch (Exception e) {
                    Log.e("AccessibilityPlugin", "Failed to launch app: " + e.getMessage());
                    result.success(false);
                }
            } else {
                result.error("INVALID_ARGS", "Package name is required", null);
            }
        } else {
            result.notImplemented();
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.d("AccessibilityPlugin", "onDetachedFromEngine: cleaning up overlays and engines");
        
        // CRITICAL: Hide and detach all overlays before the engine is destroyed
        // This prevents AccessibilityBridge NPE during hot restart
        try {
            // Hide all active overlays - this will detach them from their engines
            for (Map<String, Object> info : AccessibilityListener.getAllOverlaysInfo()) {
                Integer overlayId = (Integer) info.get("id");
                if (overlayId != null) {
                    try {
                        AccessibilityListener.hideOverlayById(overlayId);
                    } catch (Exception e) {
                        Log.w("AccessibilityPlugin", "Error hiding overlay " + overlayId + " during detach: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.w("AccessibilityPlugin", "Error cleaning up overlays during detach: " + e.getMessage());
        }
        
        // Detach the legacy overlay view from its engine
        try {
            AccessibilityListener.detachLegacyOverlayFromEngine();
        } catch (Exception e) {
            Log.w("AccessibilityPlugin", "Error detaching legacy overlay: " + e.getMessage());
        }
        
        // Clean up the cached engine to prevent stale references
        try {
            FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
            if (cachedEngine != null) {
                // Remove from cache first
                FlutterEngineCache.getInstance().remove(CACHED_TAG);
                Log.d("AccessibilityPlugin", "Removed cached engine from cache");
            }
        } catch (Exception e) {
            Log.w("AccessibilityPlugin", "Error cleaning up cached engine: " + e.getMessage());
        }
        
        channel.setMethodCallHandler(null);
        eventChannel.setStreamHandler(null);
        messageEventChannel.setStreamHandler(null);
        if (isReceiverRegistered) {
            context.unregisterReceiver(actionsReceiver);
            isReceiverRegistered = false;
        }
        if (isMessageReceiverRegistered) {
            context.unregisterReceiver(messageReceiver);
            isMessageReceiverRegistered = false;
        }
    }

    @SuppressLint({"WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (Utils.isAccessibilitySettingsOn(context)) {
            /// Set up receiver
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACCESSIBILITY_INTENT);

            accessibilityReceiver = new AccessibilityReceiver(events);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(accessibilityReceiver, intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(accessibilityReceiver, intentFilter);
            }

            /// Set up listener intent
            Intent listenerIntent = new Intent(context, AccessibilityListener.class);
            context.startService(listenerIntent);
            Log.i("AccessibilityPlugin", "Started the accessibility tracking service.");
        }
    }

    @Override
    public void onCancel(Object arguments) {
        context.unregisterReceiver(accessibilityReceiver);
        accessibilityReceiver = null;
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_FOR_ACCESSIBILITY) {
            if (pendingResult != null) {
                try {
                    if (resultCode == Activity.RESULT_OK) {
                        pendingResult.success(true);
                    } else if (resultCode == Activity.RESULT_CANCELED) {
                        pendingResult.success(Utils.isAccessibilitySettingsOn(context));
                    } else {
                        pendingResult.success(false);
                    }
                } catch (Exception e) {
                    Log.e("AccessibilityPlugin", "Error sending result: " + e.getMessage());
                } finally {
                    pendingResult = null;  // Clear to prevent multiple replies
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.mActivity = binding.getActivity();
        binding.addActivityResultListener(this);
        try {
            Log.d("AccessibilityPlugin", "onAttachedToActivity: Setting up engine");
            
            // Check if there's already a cached engine (from previous session)
            // and clean it up properly to avoid stale references
            FlutterEngine existingEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
            if (existingEngine != null) {
                Log.d("AccessibilityPlugin", "Found existing cached engine, cleaning up");
                try {
                    // Detach legacy overlay first
                    AccessibilityListener.detachLegacyOverlayFromEngine();
                    // Remove the old engine from cache
                    FlutterEngineCache.getInstance().remove(CACHED_TAG);
                    // Destroy the old engine to release resources
                    existingEngine.destroy();
                } catch (Exception cleanupE) {
                    Log.w("AccessibilityPlugin", "Error cleaning up old engine: " + cleanupE.getMessage());
                }
            }
            
            // Create new engine group and engine
            FlutterEngineGroup enn = new FlutterEngineGroup(context);
            DartExecutor.DartEntrypoint dEntry = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "accessibilityOverlay");
            FlutterEngine engine = enn.createAndRunEngine(context, dEntry);
            FlutterEngineCache.getInstance().put(CACHED_TAG, engine);
            supportOverlay = true;
            Log.d("AccessibilityPlugin", "New engine created and cached successfully");
            
            // Re-attach the legacy overlay to the new engine
            // Use a small delay to ensure the engine is fully initialized
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    AccessibilityListener.reattachLegacyOverlayToEngine();
                } catch (Exception e) {
                    Log.w("AccessibilityPlugin", "Error re-attaching legacy overlay: " + e.getMessage());
                }
            }, 100);
            
        } catch (Exception exception) {
            supportOverlay = false;
            Log.e("AccessibilityPlugin", "onAttachedToActivity error: " + exception.getMessage());
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        this.mActivity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        this.mActivity = null;
    }

    // ============================================================================
    // Message Stream Handler
    // ============================================================================

    private class MessageStreamHandler implements EventChannel.StreamHandler {
        @Override
        public void onListen(Object arguments, EventChannel.EventSink events) {
            if (Utils.isAccessibilitySettingsOn(context)) {
                // Set up message receiver
                IntentFilter messageFilter = new IntentFilter();
                messageFilter.addAction("ACCESSIBILITY_MESSAGE");

                messageReceiver = new MessageReceiver(events);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    context.registerReceiver(messageReceiver, messageFilter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(messageReceiver, messageFilter);
                }
                isMessageReceiverRegistered = true;
                
                Log.i("MessageStreamHandler", "Message stream handler initialized");
            }
        }

        @Override
        public void onCancel(Object arguments) {
            if (isMessageReceiverRegistered && messageReceiver != null) {
                context.unregisterReceiver(messageReceiver);
                messageReceiver = null;
                isMessageReceiverRegistered = false;
            }
        }
    }

    // ============================================================================
    // Message Receiver
    // ============================================================================

    private static class MessageReceiver extends BroadcastReceiver {
        private final EventChannel.EventSink events;

        MessageReceiver(EventChannel.EventSink events) {
            this.events = events;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Integer targetOverlayId = intent.getIntExtra("targetOverlayId", -1);
                String method = intent.getStringExtra("method");
                String arguments = intent.getStringExtra("arguments");
                Integer fromOverlayId = intent.getIntExtra("fromOverlayId", -1);

                if (arguments != null) {
                    Map<String, Object> messageData = new HashMap<>();
                    messageData.put("targetOverlayId", targetOverlayId);
                    messageData.put("method", method);
                    messageData.put("arguments", arguments);
                    messageData.put("fromOverlayId", fromOverlayId);

                    events.success(messageData);
                }
            } catch (Exception e) {
                Log.e("MessageReceiver", "Error processing message: " + e.getMessage(), e);
                events.error("MESSAGE_ERROR", "Error processing message", e.getMessage());
            }
        }
    }
}
