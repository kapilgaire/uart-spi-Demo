package com.quectel.demo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Gesture recognition using PAJ7620U2 sensor.
 *
 * I2C address: 0x73 (7-bit, default). Detects 9 gestures:
 * Forward, Backward, Right, Left, Up, Down, CW, CCW, Wave.
 *
 * Directional gestures (Left/Right/Up/Down) animate a ball moving in that direction.
 * Non-directional gestures (Forward/Backward/CW/CCW/Wave) pulse the ball.
 *
 * Includes I2C bus scan to auto-discover sensor address if not at 0x73.
 */
public class GestureActivity extends AppCompatActivity {

    private static final int PAJ7620_ADDR = 0x73;

    private EditText etI2cDev;
    private Button btnScan, btnInit, btnClear;
    private TextView tvStatus, tvCurrentGesture, tvLog;
    private FrameLayout ballContainer;
    private View ball;

    private int i2cFd = -1;
    private Thread pollThread;
    private volatile boolean polling;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Gesture name lookup
    private static final String[] GESTURE_NAMES = {
            "None", "Forward", "Backward", "Right", "Left",
            "Up", "Down", "Clockwise", "Counter-CW", "Wave"
    };

    // PAJ7620U2 initialization sequence (Bank 0)
    private static final int[][] INIT_BANK0 = {
            {0xEF, 0x00}, {0x32, 0x29}, {0x33, 0x01}, {0x34, 0x00},
            {0x35, 0x01}, {0x36, 0x00}, {0x37, 0x07}, {0x38, 0x17},
            {0x39, 0x06}, {0x3A, 0x12}, {0x3F, 0x00}, {0x3E, 0x00},
            {0x41, 0x00}, {0x42, 0x00}, {0x43, 0x00}, {0x45, 0x00},
            {0x46, 0x00}, {0x47, 0x00}, {0x48, 0x00}, {0x49, 0x00},
            {0x4A, 0x00}, {0x4B, 0x00}, {0x4C, 0x00}, {0x4D, 0x00},
            {0x4E, 0x00}, {0x4F, 0x00}, {0x58, 0x08}, {0x59, 0x02},
            {0x5A, 0x08}, {0x5B, 0x02}, {0x5C, 0x00}, {0x5D, 0x00},
            {0x5E, 0x00}, {0x5F, 0x00}, {0x60, 0x08}, {0x61, 0x02},
            {0x62, 0x08}, {0x63, 0x02}, {0x64, 0x00}, {0x65, 0x00},
            {0x66, 0x00}, {0x67, 0x00}, {0x68, 0x00}, {0x69, 0x00},
            {0x6A, 0x00}, {0x6B, 0x00}, {0x6C, 0x00}, {0x6D, 0x00},
            {0x6E, 0x00}, {0x6F, 0xFF}, {0x70, 0x00}, {0x71, 0x00},
            {0x72, 0x00}, {0x73, 0x00}, {0x74, 0x00}, {0x75, 0x00},
            {0x76, 0x00}, {0x77, 0x00},
    };

    private static final int[][] INIT_BANK1 = {
            {0xEF, 0x01}, {0x41, 0x00}, {0x42, 0x00}, {0x43, 0x00},
            {0x44, 0x00}, {0x45, 0x00}, {0x46, 0x00}, {0x47, 0x00},
            {0x48, 0x00}, {0x49, 0x00}, {0x4A, 0x00}, {0x4B, 0x00},
            {0x4C, 0x00}, {0x4D, 0x00}, {0x4E, 0x00}, {0x4F, 0x00},
            {0x50, 0x00}, {0x51, 0x00}, {0x52, 0x00}, {0x53, 0x00},
            {0x54, 0x00}, {0x55, 0x00}, {0x56, 0x00}, {0x57, 0x00},
            {0x58, 0x08}, {0x59, 0x10}, {0x5A, 0x08}, {0x5B, 0x10},
            {0x5C, 0x02}, {0x5D, 0x02}, {0x5E, 0x02}, {0x5F, 0x02},
            {0x60, 0x02}, {0x61, 0x02}, {0x62, 0x02}, {0x63, 0x02},
            {0x64, 0x00}, {0x65, 0x00}, {0x66, 0x00}, {0x67, 0x00},
            {0x68, 0x00}, {0x69, 0x00}, {0x6A, 0x00}, {0x6B, 0x00},
            {0x6C, 0x00}, {0x6D, 0x00}, {0x6E, 0x00}, {0x6F, 0x00},
            {0x70, 0x00}, {0x71, 0x00}, {0x72, 0x00}, {0x73, 0x00},
    };

    private static final int[][] INIT_FINALIZE = {
            {0xEF, 0x00}, {0x41, 0x00}, {0x42, 0x00},
            {0x72, 0x00}, {0x73, 0x00}, {0x74, 0x00},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gesture);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bindViews();
        bindActions();
    }

    private void bindViews() {
        etI2cDev = findViewById(R.id.et_i2c_dev);
        btnScan = findViewById(R.id.btn_scan);
        btnInit = findViewById(R.id.btn_init);
        btnClear = findViewById(R.id.btn_clear);
        tvStatus = findViewById(R.id.tv_status);
        tvCurrentGesture = findViewById(R.id.tv_current_gesture);
        tvLog = findViewById(R.id.tv_log);
        ballContainer = findViewById(R.id.ball_container);
        ball = findViewById(R.id.ball);
    }

    private void bindActions() {
        btnScan.setOnClickListener(v -> scanBus());

        btnInit.setOnClickListener(v -> {
            if (i2cFd >= 0) {
                stopSensor();
            } else {
                startSensor();
            }
        });

        btnClear.setOnClickListener(v -> {
            logBuffer.setLength(0);
            tvLog.setText("Waiting for gestures...");
            tvCurrentGesture.setText("—");
            ball.animate().cancel();
            ball.setTranslationX(0);
            ball.setTranslationY(0);
            ball.setScaleX(1f);
            ball.setScaleY(1f);
        });
    }

    /**
     * Scan the I2C bus and display all responding addresses.
     * Uses a temporary fd without a preset address, then probes each address.
     */
    private void scanBus() {
        String dev = etI2cDev.getText().toString().trim();
        if (dev.isEmpty()) {
            toast("Enter I2C device path");
            return;
        }

        // Open bus without selecting any address
        int fd = I2cNative.i2cOpen(dev, -1);
        if (fd < 0) {
            toast("I2C open failed: " + I2cNative.errnoName(fd));
            updateStatus("Scan error: " + I2cNative.errnoName(fd));
            return;
        }

        // Scan full range
        int[] found = I2cNative.i2cScan(fd, I2cNative.ADDR_FIRST, I2cNative.ADDR_LAST);
        I2cNative.i2cClose(fd);

        StringBuilder sb = new StringBuilder();
        sb.append("Bus: ").append(dev).append("\n");
        if (found == null || found.length == 0) {
            sb.append("No devices found\n");
        } else {
            sb.append("Found ").append(found.length).append(" device(s):\n");
            for (int addr : found) {
                String marker = (addr == PAJ7620_ADDR) ? " ← PAJ7620?" : "";
                sb.append(String.format("  0x%02X (%d)%s\n", addr, addr, marker));
            }
        }
        sb.append("\nTip: tap 'Initialize Sensor' to try 0x73");

        updateStatus(sb.toString());
        toast(found != null && found.length > 0
                ? "Found " + found.length + " device(s)"
                : "No devices on bus");
    }

    private void startSensor() {
        String dev = etI2cDev.getText().toString().trim();
        if (dev.isEmpty()) {
            toast("Enter I2C device path");
            return;
        }

        int fd = I2cNative.i2cOpen(dev, PAJ7620_ADDR);
        if (fd < 0) {
            toast("I2C open failed: " + I2cNative.errnoName(fd));
            updateStatus("Error: " + I2cNative.errnoName(fd));
            return;
        }
        i2cFd = fd;

        // PAJ7620U2 needs time after bus open to become responsive.
        // i2cdetect works from root shell because it runs once per probe;
        // from userspace the sensor may still be in power-up state.
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // Retry Part ID read — sensor may need a few attempts to wake
        int partId = -1;
        final int MAX_RETRIES = 5;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            // Select Bank 0 first (some variants need this before Part ID read)
            I2cNative.i2cWriteReg8(i2cFd, 0xEF, 0x00);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            partId = I2cNative.i2cReadReg8(i2cFd, 0x00);
            if (partId >= 0) break;
            if (attempt < MAX_RETRIES) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        }

        if (partId < 0) {
            toast("No response at 0x" + String.format("%02X", PAJ7620_ADDR)
                    + " after " + MAX_RETRIES + " retries");
            updateStatus("No ACK from 0x" + String.format("%02X", PAJ7620_ADDR)
                    + " (" + I2cNative.errnoName(partId) + ")\n"
                    + "Check: SELinux, /dev/i2c-1 perms, or hardware wiring");
            I2cNative.i2cClose(i2cFd);
            i2cFd = -1;
            return;
        }

        // Accept any valid Part ID (0x73 is standard, but variants may differ)
        // Since i2cdetect confirmed the device exists, proceed with init
        if (partId != 0x73) {
            toast("Part ID = 0x" + String.format("%02X", partId)
                    + " (proceeding anyway)");
        }

        int rc = 0;
        // Initialize Bank 0 with small delays and retries
        for (int[] r : INIT_BANK0) {
            boolean ok = false;
            for (int retry = 0; retry < 3; retry++) {
                rc = I2cNative.i2cWriteReg8(i2cFd, r[0], r[1]);
                if (rc >= 0) { ok = true; break; }
                if (retry < 2) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
            if (!ok && r[0] == 0xEF) {
                // Bank select is critical
                toast("Bank select failed: " + I2cNative.errnoName(rc));
                stopSensor();
                return;
            }
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        }
        for (int[] r : INIT_BANK1) {
            boolean ok = false;
            for (int retry = 0; retry < 3; retry++) {
                rc = I2cNative.i2cWriteReg8(i2cFd, r[0], r[1]);
                if (rc >= 0) { ok = true; break; }
                if (retry < 2) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
            if (!ok && r[0] == 0xEF) {
                toast("Bank 1 select failed: " + I2cNative.errnoName(rc));
                stopSensor();
                return;
            }
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        }
        for (int[] r : INIT_FINALIZE) {
            boolean ok = false;
            for (int retry = 0; retry < 3; retry++) {
                rc = I2cNative.i2cWriteReg8(i2cFd, r[0], r[1]);
                if (rc >= 0) { ok = true; break; }
                if (retry < 2) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        }

        I2cNative.i2cReadReg8(i2cFd, 0x41);

        btnInit.setText("Stop");
        etI2cDev.setEnabled(false);
        btnScan.setEnabled(false);
        updateStatus("Initialized OK\nPart ID: 0x" + String.format("%02X", partId)
                + "\nDevice: " + dev);
        toast("PAJ7620U2 ready");

        startPolling();
    }

    private void stopSensor() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        if (i2cFd >= 0) {
            I2cNative.i2cClose(i2cFd);
            i2cFd = -1;
        }
        btnInit.setText("Initialize Sensor");
        etI2cDev.setEnabled(true);
        btnScan.setEnabled(true);
        updateStatus("Stopped");
        toast("Sensor stopped");
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                try {
                    int intr = I2cNative.i2cReadReg8(i2cFd, 0x41);
                    if (intr < 0) { Thread.sleep(50); continue; }
                    if ((intr & 0x01) != 0) {
                        int gestureId = I2cNative.i2cReadReg8(i2cFd, 0x43);
                        if (gestureId > 0 && gestureId < GESTURE_NAMES.length) {
                            final String name = GESTURE_NAMES[gestureId];
                            final String time = sdf.format(new Date());
                            ui.post(() -> onGestureDetected(name, time));
                        }
                    }
                    Thread.sleep(30);
                } catch (InterruptedException e) { break; }
                catch (Throwable t) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
                }
            }
        }, "paj7620-poll");
        pollThread.start();
    }

    private void onGestureDetected(String name, String time) {
        tvCurrentGesture.setText(name);
        animateBall(name);

        String line = "[" + time + "] " + name + "\n";
        logBuffer.append(line);
        if (logBuffer.length() > 5000) {
            logBuffer.delete(0, logBuffer.length() - 4000);
        }
        tvLog.setText(logBuffer.toString());
    }

    private void animateBall(String gesture) {
        ballContainer.post(() -> {
            float containerW = ballContainer.getWidth();
            float containerH = ballContainer.getHeight();
            float ballW = ball.getWidth();
            float ballH = ball.getHeight();

            float maxDx = (containerW - ballW) / 2f;
            float maxDy = (containerH - ballH) / 2f;

            float targetX = 0, targetY = 0;
            boolean isDirectional = true;

            switch (gesture) {
                case "Left":    targetX = -maxDx; break;
                case "Right":   targetX = maxDx; break;
                case "Up":      targetY = -maxDy; break;
                case "Down":    targetY = maxDy; break;
                default: isDirectional = false; break;
            }

            if (isDirectional) {
                ball.animate().cancel();
                ball.animate()
                        .translationX(targetX).translationY(targetY)
                        .setDuration(200).setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> ball.animate()
                                .translationX(0).translationY(0)
                                .setDuration(350).setInterpolator(new OvershootInterpolator(2f))
                                .start())
                        .start();
            } else {
                ball.animate().cancel();
                ball.animate()
                        .scaleX(1.4f).scaleY(1.4f)
                        .setDuration(150).setInterpolator(new OvershootInterpolator(3f))
                        .withEndAction(() -> ball.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(250).setInterpolator(new OvershootInterpolator(2f))
                                .start())
                        .start();
            }
        });
    }

    private void updateStatus(String s) { tvStatus.setText(s); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (i2cFd >= 0) stopSensor();
    }
}