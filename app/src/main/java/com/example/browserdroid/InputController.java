package com.example.browserdroid;

import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.json.JSONObject;

/**
 * Receives JSON control messages from the browser and dispatches them
 * as real touch gestures via the Accessibility Service.
 *
 * Message formats:
 * { type:'touch', action:'down'|'move'|'up', x:0..1, y:0..1 }
 * { type:'scroll', dx:float, dy:float }
 * { type:'key', code:'KeyA', key:'a', down:true|false }
 */
public class InputController {

    private static final String TAG = "InputController";

    private final int screenWidth;
    private final int screenHeight;

    // Track pointer state for converting down/move/up into gestures
    private float downX, downY;
    private long downTime;
    private boolean pointerDown = false;

    public InputController(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public void onControlMessage(String json) {
        try {
            JSONObject msg = new JSONObject(json);
            String type = msg.getString("type");

            switch (type) {
                case "touch":
                    handleTouch(msg);
                    break;
                case "scroll":
                    handleScroll(msg);
                    break;
                case "key":
                    handleKey(msg);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad control message: " + json + " — " + e.getMessage());
        }
    }

    private void handleTouch(JSONObject msg) throws Exception {
        BrowserDroidAccessibilityService svc = BrowserDroidAccessibilityService.getInstance();
        if (svc == null) {
            Log.w(TAG, "Accessibility service not enabled — touch ignored");
            return;
        }

        String action = msg.getString("action");
        // Coordinates come in as 0..1 normalised, convert to real pixels
        // The stream is at half resolution but we inject at full screen resolution
        float x = (float) msg.getDouble("x") * screenWidth;
        float y = (float) msg.getDouble("y") * screenHeight;

        switch (action) {
            case "down":
                downX = x;
                downY = y;
                downTime = System.currentTimeMillis();
                pointerDown = true;
                break;

            case "up":
                if (!pointerDown)
                    return;
                pointerDown = false;
                long duration = System.currentTimeMillis() - downTime;

                // If finger barely moved, treat as a tap
                float dx = Math.abs(x - downX);
                float dy = Math.abs(y - downY);
                if (dx < 10 && dy < 10) {
                    Log.d(TAG, "Tap at " + (int) x + "," + (int) y);
                    svc.tap(x, y);
                } else {
                    // It was a drag — do a swipe from down position to up position
                    Log.d(TAG, "Swipe " + (int) downX + "," + (int) downY + " → " + (int) x + "," + (int) y);
                    svc.swipe(downX, downY, x, y, Math.max(duration, 100));
                }
                break;

            case "move":
                // We accumulate moves; the gesture fires on "up"
                break;
        }
    }

    private void handleScroll(JSONObject msg) throws Exception {
        BrowserDroidAccessibilityService svc = BrowserDroidAccessibilityService.getInstance();
        if (svc == null)
            return;

        float dy = (float) msg.getDouble("dy");
        float cx = screenWidth / 2f;
        float cy = screenHeight / 2f;

        // Scroll wheel delta → vertical swipe from centre
        svc.swipe(cx, cy, cx, cy - dy * 3, 150);
    }

    private void handleKey(JSONObject msg) throws Exception {
        BrowserDroidAccessibilityService svc = BrowserDroidAccessibilityService.getInstance();
        if (svc == null)
            return;

        String key = msg.getString("key");
        boolean down = msg.getBoolean("down");

        if (!down)
            return; // only act on keydown

        switch (key) {
            case "Escape":
                svc.pressBack();
                break;
            case "Backspace":
                svc.pressBack();
                break;
            case "F1":
                svc.pressHome();
                break;
            case "F2":
                svc.pressRecents();
                break;
            default:
                Log.d(TAG, "Unhandled key: " + key);
                break;
        }
    }
}
