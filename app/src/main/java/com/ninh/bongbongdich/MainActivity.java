package com.ninh.bongbongdich;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button stopButton;
    private MediaProjectionManager projectionManager;
    private boolean requestingOverlayPermission;

    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> screenCaptureLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        Button startButton = findViewById(R.id.button_start);
        stopButton = findViewById(R.id.button_stop);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        registerPermissionLaunchers();

        startButton.setOnClickListener(view -> beginStartFlow());
        stopButton.setOnClickListener(view -> stopBubbleService());

        updateStatus();
    }

    private void registerPermissionLaunchers() {
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    requestingOverlayPermission = false;
                    if (Settings.canDrawOverlays(this)) {
                        requestNotificationThenCapture();
                    } else {
                        setStatus("Chưa được cấp quyền hiển thị bong bóng.");
                        Toast.makeText(this, "Hãy bật quyền “Hiển thị trên ứng dụng khác”", Toast.LENGTH_LONG).show();
                    }
                }
        );

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> requestScreenCapture()
        );

        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                        startBubbleService(result.getResultCode(), data);
                    } else {
                        setStatus("Bạn chưa đồng ý chia sẻ màn hình.");
                    }
                }
        );
    }

    private void beginStartFlow() {
        if (!Settings.canDrawOverlays(this)) {
            requestingOverlayPermission = true;
            setStatus("Hãy bật quyền hiển thị trên ứng dụng khác…");
            Intent permissionIntent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            overlayPermissionLauncher.launch(permissionIntent);
            return;
        }
        requestNotificationThenCapture();
    }

    private void requestNotificationThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        if (projectionManager == null) {
            setStatus("Điện thoại không hỗ trợ chức năng chụp màn hình.");
            return;
        }
        setStatus("Hãy bấm Bắt đầu để cho phép dịch toàn bộ màn hình.");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MediaProjectionConfig config =
                    MediaProjectionConfig.createConfigForDefaultDisplay();
            screenCaptureLauncher.launch(
                    projectionManager.createScreenCaptureIntent(config)
            );
        } else {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
        }
    }

    private void startBubbleService(int resultCode, Intent resultData) {
        stopService(new Intent(this, BubbleService.class));

        Intent serviceIntent = new Intent(this, BubbleService.class);
        serviceIntent.setAction(BubbleService.ACTION_START);
        serviceIntent.putExtra(BubbleService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(BubbleService.EXTRA_RESULT_DATA, resultData);
        ContextCompat.startForegroundService(this, serviceIntent);

        setStatus("Đang bật bong bóng dịch…");
        stopButton.setEnabled(true);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateStatus();
            moveTaskToBack(true);
        }, 700);
    }

    private void stopBubbleService() {
        Intent stopIntent = new Intent(this, BubbleService.class);
        stopIntent.setAction(BubbleService.ACTION_STOP);
        startService(stopIntent);
        getSharedPreferences(BubbleService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(BubbleService.KEY_RUNNING, false)
                .apply();
        setStatus("Đã tắt bong bóng dịch.");
        stopButton.setEnabled(false);
    }

    private void updateStatus() {
        boolean running = getSharedPreferences(BubbleService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(BubbleService.KEY_RUNNING, false);
        if (running) {
            setStatus("● Bong bóng dịch đang hoạt động");
            stopButton.setEnabled(true);
        } else if (!requestingOverlayPermission) {
            setStatus(getString(R.string.status_off));
            stopButton.setEnabled(false);
        }
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!requestingOverlayPermission) {
            updateStatus();
        }
    }
}
