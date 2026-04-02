package com.leesoft.browserdroid;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1002;

    private MediaProjectionManager projectionManager;
    private TextView statusText;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        statusText = findViewById(R.id.statusText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> checkPermissionsAndStart());
        stopButton.setOnClickListener(v -> stopStreaming());

        findViewById(R.id.accessibilityButton).setOnClickListener(v -> {
            // Open Android accessibility settings so user can enable the service
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        updateUI(false);
        showUrl();
    }

    private void showUrl() {
        String ip = getWifiIp();
        boolean a11yEnabled = BrowserDroidAccessibilityService.isEnabled();
        statusText.setText(
                "Connect Browser to same WiFi, then visit:\n\n" +
                        "https://" + ip + ":8443\n\n" +
                        "Touch control: " + (a11yEnabled ? "✓ Enabled" : "✗ Not enabled — tap button below"));
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        REQUEST_NOTIFICATION_PERMISSION);
                return;
            }
        }
        launchProjectionDialog();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            launchProjectionDialog();
        }
    }

    private void launchProjectionDialog() {
        statusText.setText("Waiting for permission…");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startStreamingService(resultCode, data);
            } else {
                statusText.setText("Permission denied. Please try again.");
                updateUI(false);
            }
        }
    }

    private void startStreamingService(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, StreamingService.class);
        serviceIntent.putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(StreamingService.EXTRA_RESULT_DATA, data);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        updateUI(true);
        String ip = getWifiIp();
        statusText.setText("Streaming!\n\nOpen in browser:\nhttps://" + ip
                + ":8443\n\nMake sure Browser is on the same WiFi\nor connected to your phone's hotspot.");
    }

    private void stopStreaming() {
        stopService(new Intent(this, StreamingService.class));
        updateUI(false);
        showUrl();
    }

    private void updateUI(boolean streaming) {
        startButton.setEnabled(!streaming);
        stopButton.setEnabled(streaming);
    }

    private String getWifiIp() {
        return NetworkUtil.getLocalIpAddress();
    }
}