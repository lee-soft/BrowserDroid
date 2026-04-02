package com.example.browserdroid;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class BrowserDroidAccessibilityService extends AccessibilityService {

    private static final String TAG = "BrowserDroidA11y";
    private static BrowserDroidAccessibilityService instance;

    public static BrowserDroidAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isEnabled() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        instance = this;
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    public void tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build(), null, null);
    }

    public void swipe(float x1, float y1, float x2, float y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(), null, null);
    }

    public void pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void pressRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    }
}