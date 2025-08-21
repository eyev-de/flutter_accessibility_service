package slayer.accessibility.service.flutter_accessibility_service;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.RequiresApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Utils {

    public static boolean isAccessibilitySettingsOn(Context mContext) {
        int accessibilityEnabled = 0;
        final String service = mContext.getPackageName() + "/" + AccessibilityListener.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(mContext.getApplicationContext().getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
        TextUtils.SimpleStringSplitter mStringColonSplitter = new TextUtils.SimpleStringSplitter(':');
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(mContext.getApplicationContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue);
                while (mStringColonSplitter.hasNext()) {
                    String accessibilityService = mStringColonSplitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    static AccessibilityNodeInfo findNode(AccessibilityNodeInfo nodeInfo, String nodeId) {
        if (nodeInfo.getViewIdResourceName() != null && nodeInfo.getViewIdResourceName().equals(nodeId)) {
            return nodeInfo;
        }
        for (int i = 0; i < nodeInfo.getChildCount(); i++) {
            AccessibilityNodeInfo child = nodeInfo.getChild(i);
            AccessibilityNodeInfo result = findNode(child, nodeId);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    static AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo nodeInfo, String text) {
        if (nodeInfo.getText() != null && nodeInfo.getText().equals(text)) {
            return nodeInfo;
        }
        for (int i = 0; i < nodeInfo.getChildCount(); i++) {
            AccessibilityNodeInfo child = nodeInfo.getChild(i);
            AccessibilityNodeInfo result = findNodeByText(child, text);
            if (result != null) {
                return result;
            }
        }
        return null;
    }


    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    static Bundle bundleIdentifier(Integer actionType, Object extra) {
        Bundle arguments = new Bundle();
        if (extra == null) return null;
        
        try {
            if (actionType == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                if (extra instanceof String) {
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, (String) extra);
                } else {
                    return null;
                }
            } else if (actionType == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY) {
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
                if (extra instanceof Boolean) {
                    arguments.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, (Boolean) extra);
                } else {
                    return null;
                }
            } else if (actionType == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT) {
                if (extra instanceof String) {
                    arguments.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, (String) extra);
                } else {
                    return null;
                }
            } else if (actionType == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY) {
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
                if (extra instanceof Boolean) {
                    arguments.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, (Boolean) extra);
                } else {
                    return null;
                }
            } else if (actionType == AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT) {
                if (extra instanceof String) {
                    arguments.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, (String) extra);
                } else {
                    return null;
                }
            } else if (actionType == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
                if (extra instanceof HashMap) {
                    @SuppressWarnings("unchecked")
                    HashMap<String, Integer> map = (HashMap<String, Integer>) extra;
                    Integer start = map.get("start");
                    Integer end = map.get("end");
                    if (start != null && end != null) {
                        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
                        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                arguments = null;
            }
        } catch (ClassCastException e) {
            // Handle unexpected type casting issues gracefully
            return null;
        }
        
        return arguments;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    public static HashMap<String, Object> getDisplayInformation(Context context) {
        HashMap<String, Object> displayInfo = new HashMap<>();
        
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getMetrics(displayMetrics);
            
            DisplayMetrics realDisplayMetrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealMetrics(realDisplayMetrics);
            } else {
                realDisplayMetrics = displayMetrics;
            }
            
            displayInfo.put("displayId", display.getDisplayId());
            displayInfo.put("width", realDisplayMetrics.widthPixels);
            displayInfo.put("height", realDisplayMetrics.heightPixels);
            displayInfo.put("density", realDisplayMetrics.density);
            displayInfo.put("densityDpi", realDisplayMetrics.densityDpi);
            displayInfo.put("rotation", display.getRotation());
            displayInfo.put("isValid", display.isValid());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                displayInfo.put("refreshRate", display.getRefreshRate());
            } else {
                displayInfo.put("refreshRate", 60.0f);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                displayInfo.put("name", display.getName());
            } else {
                displayInfo.put("name", "Display");
            }
            
            HashMap<String, Object> metrics = new HashMap<>();
            metrics.put("widthPixels", realDisplayMetrics.widthPixels);
            metrics.put("heightPixels", realDisplayMetrics.heightPixels);
            metrics.put("density", realDisplayMetrics.density);
            metrics.put("densityDpi", realDisplayMetrics.densityDpi);
            metrics.put("scaledDensity", realDisplayMetrics.scaledDensity);
            metrics.put("xdpi", realDisplayMetrics.xdpi);
            metrics.put("ydpi", realDisplayMetrics.ydpi);
            
            displayInfo.put("metrics", metrics);
            
        } catch (Exception e) {
            displayInfo.put("error", "Failed to get display information: " + e.getMessage());
        }
        
        return displayInfo;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    public static List<HashMap<String, Object>> getAllDisplays(Context context) {
        List<HashMap<String, Object>> displaysList = new ArrayList<>();
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                Display[] displays = displayManager.getDisplays();
                
                for (Display display : displays) {
                    HashMap<String, Object> displayInfo = getDisplayInfoForDisplay(display);
                    displaysList.add(displayInfo);
                }
            } else {
                WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                Display display = windowManager.getDefaultDisplay();
                HashMap<String, Object> displayInfo = getDisplayInfoForDisplay(display);
                displaysList.add(displayInfo);
            }
        } catch (Exception e) {
            HashMap<String, Object> errorDisplay = new HashMap<>();
            errorDisplay.put("error", "Failed to get displays: " + e.getMessage());
            displaysList.add(errorDisplay);
        }
        
        return displaysList;
    }

    private static HashMap<String, Object> getDisplayInfoForDisplay(Display display) {
        HashMap<String, Object> displayInfo = new HashMap<>();
        
        try {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getMetrics(displayMetrics);
            
            DisplayMetrics realDisplayMetrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealMetrics(realDisplayMetrics);
            } else {
                realDisplayMetrics = displayMetrics;
            }
            
            displayInfo.put("displayId", display.getDisplayId());
            displayInfo.put("width", realDisplayMetrics.widthPixels);
            displayInfo.put("height", realDisplayMetrics.heightPixels);
            displayInfo.put("density", realDisplayMetrics.density);
            displayInfo.put("densityDpi", realDisplayMetrics.densityDpi);
            displayInfo.put("rotation", display.getRotation());
            displayInfo.put("isValid", display.isValid());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                displayInfo.put("refreshRate", display.getRefreshRate());
            } else {
                displayInfo.put("refreshRate", 60.0f);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                displayInfo.put("name", display.getName());
            } else {
                displayInfo.put("name", "Display " + display.getDisplayId());
            }
            
            HashMap<String, Object> metrics = new HashMap<>();
            metrics.put("widthPixels", realDisplayMetrics.widthPixels);
            metrics.put("heightPixels", realDisplayMetrics.heightPixels);
            metrics.put("density", realDisplayMetrics.density);
            metrics.put("densityDpi", realDisplayMetrics.densityDpi);
            metrics.put("scaledDensity", realDisplayMetrics.scaledDensity);
            metrics.put("xdpi", realDisplayMetrics.xdpi);
            metrics.put("ydpi", realDisplayMetrics.ydpi);
            
            displayInfo.put("metrics", metrics);
            
        } catch (Exception e) {
            displayInfo.put("error", "Failed to get display info: " + e.getMessage());
        }
        
        return displayInfo;
    }

    public static HashMap<String, Object> getCurrentDisplayMetrics(Context context) {
        HashMap<String, Object> metricsMap = new HashMap<>();
        
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getMetrics(displayMetrics);
            
            DisplayMetrics realDisplayMetrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealMetrics(realDisplayMetrics);
            } else {
                realDisplayMetrics = displayMetrics;
            }
            
            metricsMap.put("widthPixels", realDisplayMetrics.widthPixels);
            metricsMap.put("heightPixels", realDisplayMetrics.heightPixels);
            metricsMap.put("density", realDisplayMetrics.density);
            metricsMap.put("densityDpi", realDisplayMetrics.densityDpi);
            metricsMap.put("scaledDensity", realDisplayMetrics.scaledDensity);
            metricsMap.put("xdpi", realDisplayMetrics.xdpi);
            metricsMap.put("ydpi", realDisplayMetrics.ydpi);
            
        } catch (Exception e) {
            metricsMap.put("error", "Failed to get display metrics: " + e.getMessage());
        }
        
        return metricsMap;
    }
}
