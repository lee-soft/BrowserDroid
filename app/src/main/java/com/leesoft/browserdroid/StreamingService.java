package com.leesoft.browserdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

public class StreamingService extends Service {

    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "BrowserDroidStream";
    private static final int NOTIFICATION_ID = 1;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private MediaProjection mediaProjection;
    private ScreenEncoder screenEncoder;
    private StreamingServer streamingServer;
    private FrameBroadcaster broadcaster;
    private InputController inputController;

    private int screenWidth, screenHeight, screenDensity;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.w(TAG, "MediaProjection stopped");
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        getScreenMetrics();
        Log.i(TAG, "StreamingService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", ""));

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        if (resultData == null) {
            Log.e(TAG, "No projection data");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager pm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = pm.getMediaProjection(resultCode, resultData);
            mediaProjection.registerCallback(projectionCallback, null);

            // Wire up all the components
            broadcaster = new FrameBroadcaster();
            inputController = new InputController(screenWidth * 2, screenHeight * 2);

            streamingServer = new StreamingServer(this, broadcaster, inputController::onControlMessage);
            try {
                streamingServer.makeSecure(this);
            } catch (IOException e) {
                Log.w(TAG, "No keystore found, falling back to plain HTTP: " + e.getMessage());
            }

            streamingServer.start(0, false);
            Log.i(TAG, "HTTP server started on port " + StreamingServer.PORT);

            screenEncoder = new ScreenEncoder(mediaProjection, broadcaster,
                    screenWidth, screenHeight, screenDensity);
            screenEncoder.start();
            Log.i(TAG, "Screen encoder started");

            broadcaster.setOnClientConnected(() -> screenEncoder.requestKeyframe());

            String ip = getWifiIpAddress();
            String url = "https://" + ip + ":" + StreamingServer.PORT;
            Log.i(TAG, "Stream URL: " + url);
            updateNotification("Live at " + url, url);

        } catch (IOException e) {
            Log.e(TAG, "Failed to start streaming", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void getScreenMetrics() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);

        // Stream at half resolution to reduce bandwidth while keeping it crisp
        // e.g. 1080x2340 → 540x1170
        // Change divisor to 1 for full resolution
        int divisor = 2;
        screenWidth = (metrics.widthPixels / divisor / 2) * 2; // keep even
        screenHeight = (metrics.heightPixels / divisor / 2) * 2;
        screenDensity = metrics.densityDpi;

        Log.i(TAG, "Streaming at " + screenWidth + "x" + screenHeight);
    }

    private String getWifiIpAddress() {
        return NetworkUtil.getLocalIpAddress();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");

        if (screenEncoder != null) {
            screenEncoder.stop();
            screenEncoder = null;
        }

        if (streamingServer != null) {
            streamingServer.stop();
            streamingServer = null;
        }

        if (broadcaster != null) {
            broadcaster.reset();
            broadcaster = null;
        }

        if (mediaProjection != null) {
            mediaProjection.unregisterCallback(projectionCallback);
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "BrowserDroid Stream", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildNotification(title, text));
    }
}