package slayer.accessibility.service.flutter_accessibility_service;

import static slayer.accessibility.service.flutter_accessibility_service.Constants.*;
import static slayer.accessibility.service.flutter_accessibility_service.FlutterAccessibilityServicePlugin.CACHED_TAG;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.RequiresApi;


import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.FlutterInjector;


public class AccessibilityListener extends AccessibilityService {
    private static WindowManager mWindowManager;
    private static FlutterView mOverlayView;
    static private boolean isOverlayShown = false;
    private static AccessibilityListener serviceInstance;
    
    // Multiple overlay management
    private static final HashMap<Integer, AccessibilityOverlay> activeOverlays = new HashMap<>();
    private static FlutterEngineGroup engineGroup;
    private static final int CACHE_SIZE = 4 * 1024 * 1024; // 4Mib
    private static final int maxDepth = 20;
    private static LruCache<String, AccessibilityNodeInfo> nodeMap =
            new LruCache<>(CACHE_SIZE);
    private static final int DEFAULT_MAX_TREE_DEPTH = 15;
    private int maximumTreeDepth = DEFAULT_MAX_TREE_DEPTH;
    
    // Message routing system
    private static final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<String>> messageQueues = new ConcurrentHashMap<>();
    private static final String MESSAGE_INTENT = "ACCESSIBILITY_MESSAGE";
    private static volatile boolean isMainAppListening = false;
    
    public static AccessibilityNodeInfo getNodeInfo(String id) {
        return nodeMap.get(id);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        try {
            final int eventType = accessibilityEvent.getEventType();
            AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
            AccessibilityWindowInfo windowInfo = null;
            List<String> nextTexts = new ArrayList<>();
            List<Integer> actions = new ArrayList<>();
            List<HashMap<String, Object>> subNodeActions = new ArrayList<>();
            HashSet<AccessibilityNodeInfo> traversedNodes = new HashSet<>();
            HashMap<String, Object> data = new HashMap<>();
            if (parentNodeInfo == null) {
                return;
            }
            String nodeId = generateNodeId(parentNodeInfo);
            String packageName = parentNodeInfo.getPackageName().toString();
            storeNode(nodeId, parentNodeInfo);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                windowInfo = parentNodeInfo.getWindow();
            }


            Intent intent = new Intent(ACCESSIBILITY_INTENT);

            data.put("mapId", nodeId);
            data.put("packageName", packageName);
            data.put("eventType", eventType);
            data.put("actionType", accessibilityEvent.getAction());
            data.put("eventTime", accessibilityEvent.getEventTime());
            data.put("movementGranularity", accessibilityEvent.getMovementGranularity());
            Rect rect = new Rect();
            parentNodeInfo.getBoundsInScreen(rect);
            data.put("screenBounds", getBoundingPoints(rect));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                data.put("contentChangeTypes", accessibilityEvent.getContentChangeTypes());
            }
            if (parentNodeInfo.getText() != null) {
                data.put("capturedText", parentNodeInfo.getText().toString());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                data.put("nodeId", parentNodeInfo.getViewIdResourceName());
            }
            getSubNodes(parentNodeInfo, subNodeActions, traversedNodes, 0);
            data.put("nodesText", nextTexts);
            actions.addAll(parentNodeInfo.getActionList().stream().map(AccessibilityNodeInfo.AccessibilityAction::getId).collect(Collectors.toList()));
            data.put("parentActions", actions);
            data.put("subNodesActions", subNodeActions);
            data.put("isClickable", parentNodeInfo.isClickable());
            data.put("isScrollable", parentNodeInfo.isScrollable());
            data.put("isFocusable", parentNodeInfo.isFocusable());
            data.put("isCheckable", parentNodeInfo.isCheckable());
            data.put("isLongClickable", parentNodeInfo.isLongClickable());
            data.put("isEditable", parentNodeInfo.isEditable());
            if (windowInfo != null) {
                data.put("isActive", windowInfo.isActive());
                data.put("isFocused", windowInfo.isFocused());
                data.put("windowType", windowInfo.getType());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    data.put("isPip", windowInfo.isInPictureInPictureMode());
                }
            }
            storeToSharedPrefs(data);
            intent.putExtra(SEND_BROADCAST, true);
            sendBroadcast(intent);
        } catch (Exception ex) {
            Log.e("EVENT", "onAccessibilityEvent: " + ex.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean globalAction = intent.getBooleanExtra(INTENT_GLOBAL_ACTION, false);
        boolean systemActions = intent.getBooleanExtra(INTENT_SYSTEM_GLOBAL_ACTIONS, false);
        if (systemActions && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            List<Integer> actions = getSystemActions().stream().map(AccessibilityNodeInfo.AccessibilityAction::getId).collect(Collectors.toList());
            Intent broadcastIntent = new Intent(BROD_SYSTEM_GLOBAL_ACTIONS);
            broadcastIntent.putIntegerArrayListExtra("actions", new ArrayList<>(actions));
            sendBroadcast(broadcastIntent);
        }
        if (globalAction) {
            int actionId = intent.getIntExtra(INTENT_GLOBAL_ACTION_ID, 8);
            performGlobalAction(actionId);
        }
        Log.d("CMD_STARTED", "onStartCommand: " + startId);
        return START_STICKY;
    }


    @RequiresApi(api = Build.VERSION_CODES.N)
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void getSubNodes(AccessibilityNodeInfo node, List<HashMap<String, Object>> arr, HashSet<AccessibilityNodeInfo> traversedNodes, int currentDepth) {
        if (currentDepth >= maximumTreeDepth || node == null) {
            if (currentDepth >= maximumTreeDepth) {
                Log.d("TREE_DEPTH", "Maximum tree depth reached: " + currentDepth);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (traversedNodes.contains(node)) return;
            traversedNodes.add(node);
            String mapId = generateNodeId(node);
            AccessibilityWindowInfo windowInfo = null;
            HashMap<String, Object> nested = new HashMap<>();
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            windowInfo = node.getWindow();
            nested.put("mapId", mapId);
            nested.put("nodeId", node.getViewIdResourceName());
            nested.put("capturedText", node.getText());
            nested.put("screenBounds", getBoundingPoints(rect));
            nested.put("isClickable", node.isClickable());
            nested.put("isScrollable", node.isScrollable());
            nested.put("isFocusable", node.isFocusable());
            nested.put("isCheckable", node.isCheckable());
            nested.put("isLongClickable", node.isLongClickable());
            nested.put("isEditable", node.isEditable());
            nested.put("parentActions", node.getActionList().stream().map(AccessibilityNodeInfo.AccessibilityAction::getId).collect(Collectors.toList()));
            if (windowInfo != null) {
                nested.put("isActive", node.getWindow().isActive());
                nested.put("isFocused", node.getWindow().isFocused());
                nested.put("windowType", node.getWindow().getType());
            }
            arr.add(nested);
            storeNode(mapId, node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null)
                    continue;
                getSubNodes(child, arr, traversedNodes, currentDepth + 1);
            }
        }
    }

    private HashMap<String, Integer> getBoundingPoints(Rect rect) {
        HashMap<String, Integer> frame = new HashMap<>();
        frame.put("left", rect.left);
        frame.put("right", rect.right);
        frame.put("top", rect.top);
        frame.put("bottom", rect.bottom);
        frame.put("width", rect.width());
        frame.put("height", rect.height());
        return frame;
    }


    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    @Override
    protected void onServiceConnected() {
        try {
            serviceInstance = this;
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            mOverlayView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
            
            // Initialize Flutter engine group for multiple overlays
            engineGroup = new FlutterEngineGroup(getApplicationContext());
            
            // Check if cached FlutterEngine exists before attaching
            FlutterEngine cachedEngine = FlutterEngineCache.getInstance().get(CACHED_TAG);
            if (cachedEngine != null) {
                Log.d("AccessibilityListener", "Found cached FlutterEngine, attaching to overlay view");
                mOverlayView.attachToFlutterEngine(cachedEngine);
            } else {
                Log.w("AccessibilityListener", "Cached FlutterEngine not found - overlay functionality may be limited");
                // Continue without attaching to engine - this prevents the crash
                // The overlay can still be created but won't have Flutter content
            }
            
            mOverlayView.setFitsSystemWindows(true);
            mOverlayView.setFocusable(true);
            mOverlayView.setFocusableInTouchMode(true);
            mOverlayView.setBackgroundColor(Color.TRANSPARENT);
            
            Log.d("AccessibilityListener", "AccessibilityService connected successfully");
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error in onServiceConnected: " + e.getMessage(), e);
            // Don't rethrow - allow the service to continue running
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    static public void showOverlay(int width, int height, int gravity, boolean clickableThrough) {
        if (!isOverlayShown && mWindowManager != null && mOverlayView != null) {
            try {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                lp.format = PixelFormat.TRANSLUCENT;
                lp.width = width;
                lp.height = height;
                if (!clickableThrough) {
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                } else {
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                }
                lp.gravity = gravity;
                mWindowManager.addView(mOverlayView, lp);
                isOverlayShown = true;
                Log.d("AccessibilityListener", "Overlay shown successfully");
            } catch (Exception e) {
                Log.e("AccessibilityListener", "Error showing overlay: " + e.getMessage(), e);
            }
        } else {
            Log.w("AccessibilityListener", "Cannot show overlay - service not properly initialized or overlay already shown");
        }
    }

    static public void removeOverlay() {
        if (isOverlayShown && mWindowManager != null && mOverlayView != null) {
            try {
                mWindowManager.removeView(mOverlayView);
                isOverlayShown = false;
                Log.d("AccessibilityListener", "Overlay removed successfully");
            } catch (Exception e) {
                Log.e("AccessibilityListener", "Error removing overlay: " + e.getMessage(), e);
                isOverlayShown = false; // Reset flag even if removal failed
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
        removeAllOverlays();
        clearAllMessageQueues();
        serviceInstance = null;
        SharedPreferences sharedPreferences = getSharedPreferences(SHARED_PREFS_TAG, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(ACCESSIBILITY_NODE).apply();
    }

    @Override
    public void onInterrupt() {
    }


    private String generateNodeId(AccessibilityNodeInfo node) {
        return node.getWindowId() + "_" + node.getClassName() + "_" + node.getText() + "_" + node.getContentDescription(); //UUID.randomUUID().toString();
    }

    private void storeNode(String uuid, AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        nodeMap.put(uuid, node);
    }

    void storeToSharedPrefs(HashMap<String, Object> data) {
        SharedPreferences sharedPreferences = getSharedPreferences(SHARED_PREFS_TAG, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        Gson gson = new Gson();
        String json = gson.toJson(data);
        editor.putString(ACCESSIBILITY_NODE, json);
        editor.apply();
    }

    // ============================================================================
    // Multiple Overlay Management
    // ============================================================================

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    public static Integer createOverlay(Context context, int id, Map<String, Object> options, String entrypoint) {
        if (mWindowManager == null) {
            Log.e("AccessibilityListener", "WindowManager not initialized");
            return null;
        }
        
        // Check for duplicate IDs
        if (activeOverlays.containsKey(id)) {
            Log.e("AccessibilityListener", "Overlay with ID " + id + " already exists");
            return null;
        }

        try {
            AccessibilityOverlay overlay = new AccessibilityOverlay(context, mWindowManager, id);
            
            // Apply options
            if (options != null) {
                applyOverlayOptions(overlay, options);
            }
            
            // Create and attach Flutter engine if entrypoint is provided
            if (entrypoint != null && !entrypoint.isEmpty() && engineGroup != null) {
                FlutterEngine engine = createEngineForOverlay(context, entrypoint);
                if (engine != null) {
                    overlay.attachFlutterEngine(engine, entrypoint);
                }
            }
            
            activeOverlays.put(overlay.getOverlayId(), overlay);
            Log.d("AccessibilityListener", "Created overlay: " + overlay.getOverlayId() + " - activeOverlays.size() now: " + activeOverlays.size());
            return overlay.getOverlayId();
            
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error creating overlay: " + e.getMessage(), e);
            return null;
        }
    }

    public static FlutterEngine createEngineForOverlay(Context context, String entrypoint) {
        try {
            DartExecutor.DartEntrypoint dartEntrypoint = new DartExecutor.DartEntrypoint(
                FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                entrypoint
            );
            return engineGroup.createAndRunEngine(context, dartEntrypoint);
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error creating Flutter engine: " + e.getMessage(), e);
            return null;
        }
    }

    private static void applyOverlayOptions(AccessibilityOverlay overlay, Map<String, Object> options) {
        if (options == null) return;
        
        try {
            if (options.containsKey("width") && options.containsKey("height")) {
                Integer width = safeParseInt(options.get("width"));
                Integer height = safeParseInt(options.get("height"));
                if (width != null && height != null) {
                    overlay.updateSize(width, height);
                }
            }
            
            if (options.containsKey("x") && options.containsKey("y")) {
                Integer x = safeParseInt(options.get("x"));
                Integer y = safeParseInt(options.get("y"));
                if (x != null && y != null) {
                    overlay.updatePosition(x, y);
                }
            }
            
            if (options.containsKey("flags")) {
                Integer flags = safeParseInt(options.get("flags"));
                if (flags != null) {
                    overlay.updateFlags(flags);
                    // Check if movable flag is set (bit 2 = movable)
                    overlay.setMovable((flags & 2) != 0);
                    // Check if resizable flag is set (bit 4 = resizable)
                    overlay.setResizable((flags & 4) != 0);
                }
            }
            
            if (options.containsKey("gravity")) {
                Integer gravity = safeParseInt(options.get("gravity"));
                if (gravity != null) {
                    overlay.updateGravity(convertGravity(gravity));
                }
            }
            
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error applying overlay options: " + e.getMessage(), e);
        }
    }
    
    private static Integer safeParseInt(Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof Integer) {
            return (Integer) value;
        }
        
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                Log.w("AccessibilityListener", "Cannot parse string to integer: " + value);
                return null;
            }
        }
        
        if (value instanceof Double) {
            return ((Double) value).intValue();
        }
        
        if (value instanceof Float) {
            return ((Float) value).intValue();
        }
        
        if (value instanceof Long) {
            return ((Long) value).intValue();
        }
        
        Log.w("AccessibilityListener", "Cannot convert value to integer, unsupported type: " + value.getClass().getSimpleName());
        return null;
    }
    
    private static int convertGravity(int overlayGravity) {
        // Convert our overlay gravity constants to Android Gravity constants
        switch (overlayGravity) {
            case 1: return Gravity.TOP | Gravity.START; // topLeft
            case 2: return Gravity.TOP | Gravity.CENTER_HORIZONTAL; // topCenter
            case 3: return Gravity.TOP | Gravity.END; // topRight
            case 4: return Gravity.CENTER_VERTICAL | Gravity.START; // centerLeft
            case 5: return Gravity.CENTER; // center
            case 6: return Gravity.CENTER_VERTICAL | Gravity.END; // centerRight
            case 7: return Gravity.BOTTOM | Gravity.START; // bottomLeft
            case 8: return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; // bottomCenter
            case 9: return Gravity.BOTTOM | Gravity.END; // bottomRight
            case 10: return Gravity.TOP | Gravity.START; // custom - use top left as default
            default: return Gravity.CENTER;
        }
    }

    public static boolean showOverlayById(int overlayId) {
        try {
            AccessibilityOverlay overlay = activeOverlays.get(overlayId);
            if (overlay != null) {
                boolean result = overlay.show();
                return result;
            }
            return false;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error showing overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean hideOverlayById(int overlayId) {
        try {
            AccessibilityOverlay overlay = activeOverlays.get(overlayId);
            if (overlay != null) {
                boolean result = overlay.hide();
                return result;
            }
            return false;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error hiding overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean removeOverlayById(int overlayId) {
        try {
            AccessibilityOverlay overlay = activeOverlays.remove(overlayId);
            if (overlay != null) {
                overlay.destroy();
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e("AccessibilityListener", "Error removing overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean moveOverlayById(int overlayId, int x, int y) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            return overlay.updatePosition(x, y);
        }
        return false;
    }

    public static boolean resizeOverlayById(int overlayId, int width, int height) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            return overlay.updateSize(width, height);
        }
        return false;
    }

    public static boolean updateOverlayOptionsById(int overlayId, Map<String, Object> options) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            applyOverlayOptions(overlay, options);
            return true;
        }
        return false;
    }

    public static Map<String, Object> getOverlayInfo(int overlayId) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            return overlay.getInfo();
        }
        return null;
    }

    public static List<Map<String, Object>> getAllOverlaysInfo() {
        Log.d("AccessibilityListener", "getAllOverlaysInfo called - activeOverlays.size(): " + activeOverlays.size());
        List<Map<String, Object>> overlaysInfo = new ArrayList<>();
        for (AccessibilityOverlay overlay : activeOverlays.values()) {
            Map<String, Object> info = overlay.getInfo();
            Log.d("AccessibilityListener", "Overlay info: " + overlay.getOverlayId() + ", visible: " + overlay.isVisible());
            overlaysInfo.add(info);
        }
        Log.d("AccessibilityListener", "Returning " + overlaysInfo.size() + " overlay infos");
        return overlaysInfo;
    }

    public static void removeAllOverlays() {
        for (AccessibilityOverlay overlay : activeOverlays.values()) {
            overlay.destroy();
        }
        activeOverlays.clear();
        Log.d("AccessibilityListener", "All overlays removed");
    }

    public static boolean refreshEngineById(int overlayId) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay != null) {
            Log.d("AccessibilityListener", "Refreshing engine for overlay: " + overlayId);
            return overlay.refreshEngine();
        }
        Log.w("AccessibilityListener", "Overlay not found for refresh: " + overlayId);
        return false;
    }

    public static boolean refreshAllEngines() {
        Log.d("AccessibilityListener", "Refreshing all overlay engines");
        boolean allSuccess = true;
        
        for (AccessibilityOverlay overlay : activeOverlays.values()) {
            boolean success = overlay.refreshEngine();
            if (!success) {
                allSuccess = false;
                Log.w("AccessibilityListener", "Failed to refresh engine for overlay: " + overlay.getOverlayId());
            }
        }
        
        Log.d("AccessibilityListener", "Refresh all engines completed. Success: " + allSuccess);
        return allSuccess;
    }

    // ============================================================================
    // Message Routing System
    // ============================================================================

    /**
     * Send a message to a specific target by index
     * Index 0 = Main App, Index N = Overlay with ID N
     */
    public static boolean sendMessage(int targetIndex, String jsonMessage) {
        Log.d("MessageRouter", "Sending message to index " + targetIndex + ": " + jsonMessage);
        
        if (targetIndex == 0) {
            // Send to main app
            return sendMessageToMainApp(jsonMessage);
        } else {
            // Send to overlay
            return sendMessageToOverlay(targetIndex, jsonMessage);
        }
    }
    
    /**
     * Send message to main app (index 0)
     */
    private static boolean sendMessageToMainApp(String jsonMessage) {
        try {
            Intent messageIntent = new Intent(MESSAGE_INTENT);
            messageIntent.putExtra("message", jsonMessage);
            messageIntent.putExtra("targetIndex", 0);
            messageIntent.putExtra("sourceIndex", getCurrentOverlayContext());
            
            // Get the service instance context
            if (mWindowManager != null) {
                // We have access to the service context
                AccessibilityListener service = getCurrentServiceInstance();
                if (service != null) {
                    service.sendBroadcast(messageIntent);
                    Log.d("MessageRouter", "Message sent to main app via broadcast");
                    return true;
                }
            }
            
            // Fallback: queue the message if main app is not listening
            getOrCreateMessageQueue(0).offer(jsonMessage);
            Log.d("MessageRouter", "Message queued for main app");
            return true;
            
        } catch (Exception e) {
            Log.e("MessageRouter", "Error sending message to main app: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send message to overlay
     */
    private static boolean sendMessageToOverlay(int overlayId, String jsonMessage) {
        return sendMessageToOverlay(overlayId, jsonMessage, -1); // Default unknown source
    }
    
    /**
     * Send message to overlay with source index
     */
    private static boolean sendMessageToOverlay(int overlayId, String jsonMessage, int fromIndex) {
        AccessibilityOverlay overlay = activeOverlays.get(overlayId);
        if (overlay == null) {
            Log.w("MessageRouter", "Overlay " + overlayId + " not found, queuing message");
            getOrCreateMessageQueue(overlayId).offer(jsonMessage);
            return false;
        }
        
        try {
            // Try to deliver directly to overlay's Flutter engine
            if (deliverMessageToOverlayEngine(overlay, jsonMessage, fromIndex)) {
                Log.d("MessageRouter", "Message delivered directly to overlay " + overlayId);
                return true;
            } else {
                // Queue the message for later delivery
                getOrCreateMessageQueue(overlayId).offer(jsonMessage);
                Log.d("MessageRouter", "Message queued for overlay " + overlayId);
                return true;
            }
        } catch (Exception e) {
            Log.e("MessageRouter", "Error sending message to overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Deliver message directly to overlay's Flutter engine with source index
     */
    private static boolean deliverMessageToOverlayEngine(AccessibilityOverlay overlay, String jsonMessage, int fromIndex) {
        return overlay.sendMessage(jsonMessage, fromIndex);
    }
    
    /**
     * Get or create message queue for a target index
     */
    private static ConcurrentLinkedQueue<String> getOrCreateMessageQueue(int targetIndex) {
        return messageQueues.computeIfAbsent(targetIndex, k -> new ConcurrentLinkedQueue<>());
    }
    
    /**
     * Get pending messages for a target index
     */
    public static List<String> getPendingMessages(int targetIndex) {
        ConcurrentLinkedQueue<String> queue = messageQueues.get(targetIndex);
        if (queue == null || queue.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<String> messages = new ArrayList<>();
        String message;
        while ((message = queue.poll()) != null) {
            messages.add(message);
        }
        
        Log.d("MessageRouter", "Retrieved " + messages.size() + " pending messages for index " + targetIndex);
        return messages;
    }
    
    /**
     * Register main app as message listener
     */
    public static void registerMainAppListener() {
        isMainAppListening = true;
        Log.d("MessageRouter", "Main app registered as message listener");
    }
    
    /**
     * Unregister main app as message listener
     */
    public static void unregisterMainAppListener() {
        isMainAppListening = false;
        Log.d("MessageRouter", "Main app unregistered as message listener");
    }
    
    /**
     * Register overlay as message listener
     */
    public static void registerOverlayListener(int overlayId) {
        // Ensure message queue exists
        getOrCreateMessageQueue(overlayId);
        Log.d("MessageRouter", "Overlay " + overlayId + " registered as message listener");
    }
    
    /**
     * Get current overlay context (used for determining source index)
     */
    private static int getCurrentOverlayContext() {
        // This would need to be set by the overlay when sending messages
        // For now, return -1 to indicate unknown source
        return -1;
    }
    
    /**
     * Get current service instance (helper method)
     */
    private static AccessibilityListener getCurrentServiceInstance() {
        return serviceInstance;
    }
    
    /**
     * Clear all message queues
     */
    public static void clearAllMessageQueues() {
        messageQueues.clear();
        Log.d("MessageRouter", "All message queues cleared");
    }

}
