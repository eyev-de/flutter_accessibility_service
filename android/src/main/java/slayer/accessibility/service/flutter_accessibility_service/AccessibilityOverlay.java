package slayer.accessibility.service.flutter_accessibility_service;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AccessibilityOverlay {
    private static final String TAG = "AccessibilityOverlay";
    
    private final int overlayId;
    private final Context context;
    private final WindowManager windowManager;
    
    private FlutterView flutterView;
    private FlutterEngine flutterEngine;
    private MethodChannel messageChannel;
    private WindowManager.LayoutParams layoutParams;
    private String entrypoint; // Store the entrypoint for engine refresh
    private boolean isVisible = false;
    private boolean isMovable = false;
    private boolean isResizable = false;
    
    // Touch handling for movement
    private float initialTouchX, initialTouchY;
    private int initialX, initialY;
    private boolean isDragging = false;
    
    private final long createdAt;
    private long lastUpdated;

    private final Handler main = new Handler(Looper.getMainLooper());

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    
    public AccessibilityOverlay(Context context, WindowManager windowManager, int overlayId) {
        this.context = context;
        this.windowManager = windowManager;
        this.overlayId = overlayId;
        this.createdAt = System.currentTimeMillis();
        this.lastUpdated = createdAt;
        
        initializeView();
        setupLayoutParams();
    }
    
    private void initializeView() {
        try {
            flutterView = new FlutterView(context, new FlutterTextureView(context));
            flutterView.setFitsSystemWindows(true);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);

            // CRITICAL: Disable accessibility for overlay views to prevent ViewParent issues
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                flutterView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                flutterView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
            }

            Log.d(TAG, "FlutterView initialized for overlay: " + overlayId);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing FlutterView: " + e.getMessage(), e);
        }
    }
    
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    private void setupLayoutParams() {
        layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        
        // Default size and position
        layoutParams.width = 300;
        layoutParams.height = 200;
        layoutParams.x = 0;
        layoutParams.y = 0;
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        
        // Default flags - non-touchable, non-focusable, expandable outside layout
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    }
    
    public void attachFlutterEngine(FlutterEngine engine) {
        attachFlutterEngine(engine, null);
    }
    
    public void attachFlutterEngine(FlutterEngine engine, String entrypoint) {
        synchronized (this) {
            this.flutterEngine = engine;
            this.entrypoint = entrypoint;
            runOnMain(() -> {
                try {
                    if (flutterView != null && this.flutterEngine != null) {
                        flutterView.attachToFlutterEngine(this.flutterEngine);
                        setupMessageChannel();
                        // Tell Flutter we’re visible/resumed (see #2)
                        this.flutterEngine.getLifecycleChannel().appIsResumed();
                        Log.d(TAG, "Engine attached …");
                    }
                } catch (Exception e) { 
                    Log.e(TAG, "Error attaching Flutter engine to overlay: " + e.getMessage(), e); 
                }
            });
        }
    }
    
    public void detachFlutterEngine() {
        // Synchronize to prevent race conditions with method calls
        synchronized (this) {
            try {
                // Clean up message channel first
                if (messageChannel != null) {
                    try {
                        messageChannel.setMethodCallHandler(null);
                    } catch (Exception channelE) {
                        Log.w(TAG, "Error cleaning up message channel: " + channelE.getMessage());
                    }
                    messageChannel = null;
                }

                // Detach Flutter view from engine
                if (flutterView != null && flutterEngine != null) {
                    try {
                        flutterView.detachFromFlutterEngine();
                        Log.d(TAG, "FlutterEngine detached from overlay: " + overlayId);
                    } catch (Exception detachE) {
                        Log.w(TAG, "Error detaching Flutter engine: " + detachE.getMessage());
                    }
                }

                this.flutterEngine = null;
            } catch (Exception e) {
                Log.e(TAG, "Error during Flutter engine detachment: " + e.getMessage(), e);
            }
        }
    }
    
    public boolean show() {
        if (isVisible || windowManager == null || flutterView == null) {
            return false;
        }
        
        try {
            // Clean up any existing view state before showing
            if (flutterView.getParent() != null) {
                Log.w(TAG, "FlutterView already has parent, cleaning up before showing overlay: " + overlayId);
                try {
                    windowManager.removeView(flutterView);
                } catch (Exception cleanupE) {
                    Log.w(TAG, "Error during cleanup before show: " + cleanupE.getMessage());
                }
            }
            
            setupTouchHandling();
            windowManager.addView(flutterView, layoutParams);
            isVisible = true;
            lastUpdated = System.currentTimeMillis();
            
            // Ensure accessibility state is properly updated
            try {
                if (flutterView != null && flutterView.getContext() != null && flutterView.getParent() != null) {
                    flutterView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                }
            } catch (Exception accessibilityE) {
                Log.w(TAG, "Error updating accessibility state on show: " + accessibilityE.getMessage());
            }
            
            Log.d(TAG, "Overlay shown: " + overlayId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error showing overlay: " + e.getMessage(), e);
            isVisible = false; // Reset state on failure
            return false;
        }
    }
    
    public boolean hide() {
        if (!isVisible || windowManager == null || flutterView == null) {
            return false;
        }
        
        try {
            // Clear accessibility state before hiding
            // try {
            //     if (flutterView != null && flutterView.getContext() != null && flutterView.getParent() != null) {
            //         // Notify accessibility service that window is being hidden
            //         flutterView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            //     }
            // } catch (Exception accessibilityE) {
            //     Log.w(TAG, "Error clearing accessibility state on hide: " + accessibilityE.getMessage());
            // }
            
            // Check if view is actually attached before trying to remove
            if (flutterView.getParent() != null) {
                windowManager.removeView(flutterView);
            } else {
                Log.w(TAG, "FlutterView has no parent, skipping removeView for overlay: " + overlayId);
            }
            
            isVisible = false;
            lastUpdated = System.currentTimeMillis();
            Log.d(TAG, "Overlay hidden: " + overlayId);
            return true;
        } catch (IllegalArgumentException e) {
            // This can happen if view was not attached to window manager
            Log.w(TAG, "View not attached to window manager during hide: " + e.getMessage());
            isVisible = false;
            return true; // Consider this successful since view is effectively hidden
        } catch (Exception e) {
            Log.e(TAG, "Error hiding overlay: " + e.getMessage(), e);
            isVisible = false; // Reset flag even if removal failed
            return false;
        }
    }
    
    public boolean updatePosition(int x, int y) {
        try {
            // Use absolute positioning for manual moves
            layoutParams.gravity = Gravity.TOP | Gravity.START;
            layoutParams.x = x;
            layoutParams.y = y;
            
            if (isVisible && windowManager != null && flutterView != null) {
                // Check if view is still attached before updating
                if (flutterView.getParent() != null) {
                    try {
                        windowManager.updateViewLayout(flutterView, layoutParams);
                        // Log.d(TAG, "WindowManager.updateViewLayout called for overlay: " + overlayId);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "View not attached during position update, re-adding: " + e.getMessage());
                        // Try to re-add the view if it became detached
                        try {
                            windowManager.addView(flutterView, layoutParams);
                        } catch (Exception reAddE) {
                            Log.e(TAG, "Failed to re-add view during position update: " + reAddE.getMessage());
                            return false;
                        }
                    }
                } else {
                    Log.w(TAG, "FlutterView has no parent during position update, overlay may have been removed");
                    isVisible = false; // Update internal state
                    return false;
                }
            } else {
                Log.w(TAG, "Cannot update position - overlay not visible or missing components. " +
                     "isVisible: " + isVisible + ", windowManager: " + (windowManager != null) + 
                     ", flutterView: " + (flutterView != null));
            }
            
            lastUpdated = System.currentTimeMillis();
            // Log.d(TAG, "Overlay position updated: " + overlayId + " -> (" + x + ", " + y + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error updating overlay position: " + e.getMessage(), e);
            return false;
        }
    }
    
    public boolean updateSize(int width, int height) {
        try {
            layoutParams.width = width;
            layoutParams.height = height;
            
            if (isVisible && windowManager != null && flutterView != null) {
                // Check if view is still attached before updating
                if (flutterView.getParent() != null) {
                    try {
                        windowManager.updateViewLayout(flutterView, layoutParams);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "View not attached during size update: " + e.getMessage());
                        isVisible = false; // Update internal state
                        return false;
                    }
                } else {
                    Log.w(TAG, "FlutterView has no parent during size update");
                    isVisible = false;
                    return false;
                }
            }
            
            lastUpdated = System.currentTimeMillis();
            Log.d(TAG, "Overlay size updated: " + overlayId + " -> " + width + "x" + height);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error updating overlay size: " + e.getMessage(), e);
            return false;
        }
    }
    
    public boolean updateFlags(int flags) {
        try {
            layoutParams.flags = flags;
            
            if (isVisible && windowManager != null && flutterView != null) {
                windowManager.updateViewLayout(flutterView, layoutParams);
            }
            
            lastUpdated = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error updating overlay flags: " + e.getMessage(), e);
            return false;
        }
    }
    
    public boolean updateGravity(int gravity) {
        try {
            layoutParams.gravity = gravity;
            
            if (isVisible && windowManager != null && flutterView != null) {
                windowManager.updateViewLayout(flutterView, layoutParams);
            }
            
            lastUpdated = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error updating overlay gravity: " + e.getMessage(), e);
            return false;
        }
    }
    
    private void setupTouchHandling() {
        if (flutterView == null) return;
        
        flutterView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isMovable) {
                    return false; // Let Flutter handle the touch
                }
                
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        isDragging = false;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - initialTouchX;
                        float deltaY = event.getRawY() - initialTouchY;
                        
                        // Only start dragging after significant movement
                        if (!isDragging && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                            isDragging = true;
                        }
                        
                        if (isDragging) {
                            int newX = initialX + (int) deltaX;
                            int newY = initialY + (int) deltaY;
                            updatePosition(newX, newY);
                            return true;
                        }
                        break;
                        
                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            // This was a tap, let Flutter handle it
                            return false;
                        }
                        isDragging = false;
                        return true;
                }
                
                return false;
            }
        });
    }
    
    public void destroy() {
        try {
            if (isVisible) {
                hide();
            }
            
            // Ensure view is completely removed
            try {
                if (flutterView != null && flutterView.getParent() != null && windowManager != null) {
                    windowManager.removeView(flutterView);
                }
            } catch (Exception viewRemovalE) {
                Log.w(TAG, "Error ensuring view removal during destroy: " + viewRemovalE.getMessage());
            }
            
            // Clean up Flutter engine attachment
            detachFlutterEngine();
            
            // Clear view reference
            if (flutterView != null) {
                flutterView = null;
            }
            
            Log.d(TAG, "Overlay destroyed: " + overlayId);
        } catch (Exception e) {
            Log.e(TAG, "Error destroying overlay: " + e.getMessage(), e);
        }
    }
    
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("id", overlayId);
        info.put("type", 1); // Default type value for Dart compatibility
        info.put("isVisible", isVisible);
        info.put("createdAt", createdAt);
        info.put("lastUpdated", lastUpdated);
        
        Map<String, Object> options = new HashMap<>();
        if (layoutParams != null) {
            options.put("width", layoutParams.width);
            options.put("height", layoutParams.height);
            options.put("x", layoutParams.x);
            options.put("y", layoutParams.y);
            options.put("gravity", layoutParams.gravity);
            options.put("flags", layoutParams.flags);
        }
        
        info.put("options", options);
        return info;
    }
    
    // Getters
    public int getOverlayId() { return overlayId; }
    public boolean isVisible() { return isVisible; }
    public boolean isMovable() { return isMovable; }
    public boolean isResizable() { return isResizable; }
    public FlutterView getFlutterView() { return flutterView; }
    public FlutterEngine getFlutterEngine() { return flutterEngine; }
    public WindowManager.LayoutParams getLayoutParams() { return layoutParams; }
    
    // Setters
    public void setMovable(boolean movable) { 
        this.isMovable = movable;
        if (flutterView != null) {
            setupTouchHandling(); // Refresh touch handling
        }
    }
    
    public void setResizable(boolean resizable) { this.isResizable = resizable; }
    
    // Configurable overlay behavior methods
    public void setTouchable(boolean touchable) {
        if (layoutParams != null) {
            if (touchable) {
                layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            updateLayoutParams();
        }
    }
    
    public void setFocusable(boolean focusable) {
        if (layoutParams != null) {
            if (focusable) {
                layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            } else {
                layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            updateLayoutParams();
        }
    }
    
    public void setExpandOutsideLayout(boolean expandOutside) {
        if (layoutParams != null) {
            if (expandOutside) {
                layoutParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            } else {
                layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            }
            updateLayoutParams();
        }
    }
    
    public void setWatchOutsideTouch(boolean watchOutside) {
        if (layoutParams != null) {
            if (watchOutside) {
                layoutParams.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            } else {
                layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            }
            updateLayoutParams();
        }
    }
    
    private void updateLayoutParams() {
        if (isVisible && windowManager != null && flutterView != null) {
            try {
                windowManager.updateViewLayout(flutterView, layoutParams);
                Log.d(TAG, "Updated overlay layout params for overlay: " + overlayId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update overlay layout params: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Refresh the Flutter engine by creating a new one with the same entrypoint
     * This is useful for development hot reload functionality
     */
    public boolean refreshEngine() {
        if (entrypoint == null || entrypoint.isEmpty()) {
            Log.w(TAG, "Cannot refresh engine - no entrypoint stored for overlay: " + overlayId);
            return false;
        }
        
        try {
            // Get reference to the engine group from AccessibilityListener
            boolean wasVisible = isVisible;
            
            // Detach current engine
            if (flutterEngine != null) {
                detachFlutterEngine();
                Log.d(TAG, "Detached old engine for overlay: " + overlayId);
            }
            
            // Create new engine with same entrypoint
            FlutterEngine newEngine = AccessibilityListener.createEngineForOverlay(context, entrypoint);
            if (newEngine != null) {
                // Attach new engine
                attachFlutterEngine(newEngine, entrypoint);
                Log.d(TAG, "Attached new engine for overlay: " + overlayId);
                
                // Restore visibility if it was visible before
                if (wasVisible && !isVisible) {
                    show();
                }
                
                lastUpdated = System.currentTimeMillis();
                return true;
            } else {
                Log.e(TAG, "Failed to create new engine for overlay: " + overlayId);
                return false;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing engine for overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }
    
    // Getter for entrypoint
    public String getEntrypoint() { return entrypoint; }
    
    /**
     * Set up message channel for communication between overlay and other components
     */
    private void setupMessageChannel() {
        if (flutterEngine == null) return;
        
        try {
            messageChannel = new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                "x-slayer/accessibility_message"
            );
            
            messageChannel.setMethodCallHandler((call, result) -> {
                if ("invokeMethod".equals(call.method)) {
                    Integer targetOverlayId = call.argument("targetOverlayId");
                    String method = call.argument("method");
                    String arguments = call.argument("arguments");
                    
                    if (targetOverlayId != null && arguments != null) {
                        boolean success = AccessibilityListener.sendMessage(targetOverlayId, method, arguments);
                        result.success(success);
                    } else {
                        result.error("INVALID_ARGS", "Target overlay ID and arguments are required", null);
                    }
                } else {
                    result.notImplemented();
                }
            });
            
            Log.d(TAG, "Message channel set up for overlay: " + overlayId);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up message channel for overlay " + overlayId + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Send a message to this overlay via its message channel
     */
    public boolean sendMessage(String method, String jsonMessage, int fromOverlayId) {
        if (messageChannel == null) {
            Log.w(TAG, "Message channel not available for overlay: " + overlayId);
            return false;
        }
        
        try {
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("fromOverlayId", fromOverlayId);
            messageData.put("method", method);
            messageData.put("arguments", jsonMessage);
            
            messageChannel.invokeMethod("receiveMessage", messageData);
            // Log.d(TAG, "Message sent to overlay " + overlayId + " from " + fromOverlayId + ": " + jsonMessage);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error sending message to overlay " + overlayId + ": " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send a message to this overlay via its message channel (legacy method)
     */
    public boolean sendMessage(String method, String jsonMessage) {
        return sendMessage(method, jsonMessage, -1); // Unknown source
    }
    
    /**
     * Get the message channel for this overlay
     */
    public MethodChannel getMessageChannel() {
        return messageChannel;
    }
    
    // Helper method to convert Android gravity to string name
    private String gravityToString(int gravity) {
        // Simply return "top" as default for all cases to avoid enum parsing issues
        return "top";
    }
}