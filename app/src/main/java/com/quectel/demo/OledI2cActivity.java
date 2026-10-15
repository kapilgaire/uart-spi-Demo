package com.quectel.demo;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * SSD1306 OLED (I2C) demo activity.
 *
 * Four modes:
 *  - Text: type text, choose alignment, display on OLED
 *  - Icons: pick a preset 16x16 icon, display it
 *  - Animation: scrolling text or bouncing icon
 *  - Eye: animated eyes with expression buttons (ESP32-style)
 */
public class OledI2cActivity extends Activity {

    private static final int SSD1306_ADDR = 0x3C;
    private static final int DISPLAY_W = 128;
    private static final int DISPLAY_H = 64;

    private EditText etI2cDev, etText;
    private RadioGroup rgAlign;
    private RadioButton rbCenter, rbLeft, rbRight;
    private Button btnConnect, btnShowText, btnShowIcon;
    private Button btnStartScroll, btnStartBounce, btnStop;
    private Button btnEyeNormal, btnEyeHappy, btnEyeSad, btnEyeAngry;
    private Button btnEyeSurprised, btnEyeWink, btnEyeSleepy, btnEyeBlink;
    private Button btnStartEyes;
    private TextView tvStatus;
    private ScrollView svLog;
    private TextView tvLog;

    private OledI2cDriver oled;
    private volatile boolean animRunning;
    private Thread animThread;
    private volatile int currentExpression = OledI2cDriver.EXPR_NORMAL;
    private volatile int targetPupilX = 0, targetPupilY = 0;
    private volatile int currentPupilX = 0, currentPupilY = 0;
    private volatile int blinkOpenness = 100;
    private volatile long nextBlinkTime = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oled_i2c);

        etI2cDev = findViewById(R.id.et_i2c_dev);
        etText = findViewById(R.id.et_text);
        rgAlign = findViewById(R.id.rg_align);
        rbCenter = findViewById(R.id.rb_center);
        rbLeft = findViewById(R.id.rb_left);
        rbRight = findViewById(R.id.rb_right);
        btnConnect = findViewById(R.id.btn_connect);
        btnShowText = findViewById(R.id.btn_show_text);
        btnShowIcon = findViewById(R.id.btn_show_icon);
        btnStartScroll = findViewById(R.id.btn_start_scroll);
        btnStartBounce = findViewById(R.id.btn_start_bounce);
        btnStop = findViewById(R.id.btn_stop);
        btnStartEyes = findViewById(R.id.btn_start_eyes);
        btnEyeNormal = findViewById(R.id.btn_eye_normal);
        btnEyeHappy = findViewById(R.id.btn_eye_happy);
        btnEyeSad = findViewById(R.id.btn_eye_sad);
        btnEyeAngry = findViewById(R.id.btn_eye_angry);
        btnEyeSurprised = findViewById(R.id.btn_eye_surprised);
        btnEyeWink = findViewById(R.id.btn_eye_wink);
        btnEyeSleepy = findViewById(R.id.btn_eye_sleepy);
        btnEyeBlink = findViewById(R.id.btn_eye_blink);
        tvStatus = findViewById(R.id.tv_status);
        svLog = findViewById(R.id.sv_log);
        tvLog = findViewById(R.id.tv_log);

        btnConnect.setOnClickListener(v -> toggleConnect());
        btnShowText.setOnClickListener(v -> showText());
        btnShowIcon.setOnClickListener(v -> showIcon());
        btnStartScroll.setOnClickListener(v -> startScroll());
        btnStartBounce.setOnClickListener(v -> startBounce());
        btnStartEyes.setOnClickListener(v -> startEyes());
        btnStop.setOnClickListener(v -> stopAnim());

        btnEyeNormal.setOnClickListener(v -> setExpression(OledI2cDriver.EXPR_NORMAL));
        btnEyeHappy.setOnClickListener(v -> setExpression(OledI2cDriver.EXPR_HAPPY));
        btnEyeSad.setOnClickListener(v -> setExpression(OledI2cDriver.EXPR_SAD));
        btnEyeAngry.setOnClickListener(v -> setExpression(OledI2cDriver.EXPR_ANGRY));
        btnEyeSurprised.setOnClickListener(v -> setExpression(OledI2cDriver.EXPR_SURPRISED));
        btnEyeWink.setOnClickListener(v -> setExpression(OledI2cDriver.EXPR_WINK));
        btnEyeSleepy.setOnClickListener(v -> setExpression(OledI2cDriver.EXPR_SLEEPY));
        btnEyeBlink.setOnClickListener(v -> triggerBlink());

        setButtonsEnabled(false);
    }

    private void log(String s) {
        logBuf.append(s).append("\n");
        if (logBuf.length() > 3000) logBuf.delete(0, logBuf.length() - 2000);
        tvLog.setText(logBuf.toString());
    }

    private void setStatus(String s) { tvStatus.setText(s); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private void setButtonsEnabled(boolean on) {
        btnShowText.setEnabled(on);
        btnShowIcon.setEnabled(on);
        btnStartScroll.setEnabled(on);
        btnStartBounce.setEnabled(on);
        btnStartEyes.setEnabled(on);
        btnStop.setEnabled(on);
        btnEyeNormal.setEnabled(on);
        btnEyeHappy.setEnabled(on);
        btnEyeSad.setEnabled(on);
        btnEyeAngry.setEnabled(on);
        btnEyeSurprised.setEnabled(on);
        btnEyeWink.setEnabled(on);
        btnEyeSleepy.setEnabled(on);
        btnEyeBlink.setEnabled(on);
        etText.setEnabled(on);
        rgAlign.setEnabled(on);
    }

    // ---- Connection ----

    private void toggleConnect() {
        if (oled != null) disconnect(); else connect();
    }

    private void connect() {
        String dev = etI2cDev.getText().toString().trim();
        if (dev.isEmpty()) { toast("Enter I2C device"); return; }

        oled = new OledI2cDriver(DISPLAY_W, DISPLAY_H);
        if (!oled.open(dev, SSD1306_ADDR)) {
            toast("I2C open failed");
            setStatus("Error: cannot open " + dev);
            oled = null;
            return;
        }

        oled.refresh();
        btnConnect.setText("Disconnect");
        etI2cDev.setEnabled(false);
        setButtonsEnabled(true);
        setStatus("Connected: " + dev + " @ 0x" + String.format("%02X", SSD1306_ADDR));
        log("OLED connected (" + DISPLAY_W + "x" + DISPLAY_H + ")");
    }

    private void disconnect() {
        stopAnim();
        if (oled != null) {
            oled.clear();
            oled.refresh();
            oled.close();
            oled = null;
        }
        btnConnect.setText("Connect");
        etI2cDev.setEnabled(true);
        setButtonsEnabled(false);
        setStatus("Disconnected");
        log("OLED disconnected");
    }

    // ---- Text ----

    private void showText() {
        if (oled == null) return;
        String text = etText.getText().toString();
        if (text.isEmpty()) { toast("Enter text"); return; }
        stopAnim();

        String[] lines = text.split("\n", -1);
        int maxLines = DISPLAY_H / 8;
        int lineH = 8;
        oled.clear();

        int totalLines = Math.min(lines.length, maxLines);
        int totalH = totalLines * lineH;
        int yStart = (DISPLAY_H - totalH) / 2;

        for (int i = 0; i < totalLines; i++) {
            String line = lines[i];
            int textW = line.length() * 6;
            int x = rbRight.isChecked() ? DISPLAY_W - textW - 4
                  : rbLeft.isChecked() ? 4
                  : (DISPLAY_W - textW) / 2;
            if (x < 0) x = 0;
            oled.drawText(x, yStart + i * lineH, line);
        }
        oled.refresh();
        log("Displayed: \"" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "\"");
    }

    // ---- Icons ----

    private void showIcon() {
        if (oled == null) return;
        stopAnim();
        oled.clear();

        byte[][] icons = {
                OledI2cDriver.ICON_HEART_DATA,
                OledI2cDriver.ICON_STAR_DATA,
                OledI2cDriver.ICON_WIFI_DATA,
                OledI2cDriver.ICON_BATTERY_DATA,
                OledI2cDriver.ICON_ARROW_UP_DATA,
                OledI2cDriver.ICON_CHECK_DATA
        };
        int iconW = 16, iconH = 16;
        int gapX = (DISPLAY_W - 3 * iconW) / 4;
        int gapY = (DISPLAY_H - 2 * iconH) / 3;

        for (int i = 0; i < icons.length; i++) {
            int col = i % 3, row = i / 3;
            oled.drawIcon(gapX + col * (iconW + gapX), gapY + row * (iconH + gapY), icons[i]);
        }
        oled.refresh();
        log("Displayed 6 preset icons in 3x2 grid");
    }

    // ---- Scroll animation ----

    private void startScroll() {
        if (oled == null) return;
        String text = etText.getText().toString();
        if (text.isEmpty()) text = "Hello OLED!";
        stopAnim();
        animRunning = true;
        btnStop.setEnabled(true);
        log("Starting scroll animation...");

        String finalText = text;
        animThread = new Thread(() -> {
            int textW = finalText.length() * 6;
            int x = DISPLAY_W;
            while (animRunning) {
                oled.clear();
                int y = (DISPLAY_H - 8) / 2;
                int drawX = x;
                for (int i = 0; i < finalText.length(); i++) {
                    oled.drawChar(drawX, y, finalText.charAt(i));
                    drawX += 6;
                }
                if (x < 0) {
                    drawX = x + DISPLAY_W + 20;
                    for (int i = 0; i < finalText.length(); i++) {
                        oled.drawChar(drawX, y, finalText.charAt(i));
                        drawX += 6;
                    }
                }
                oled.refresh();
                x -= 1;
                if (x < -(textW + 20)) x = DISPLAY_W;
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
        }, "oled-scroll");
        animThread.start();
    }

    // ---- Bounce animation ----

    private void startBounce() {
        if (oled == null) return;
        stopAnim();
        animRunning = true;
        btnStop.setEnabled(true);
        log("Starting bouncing icon...");

        animThread = new Thread(() -> {
            int x = 0, y = 0, dx = 2, dy = 1;
            while (animRunning) {
                oled.clear();
                oled.drawIcon(x, y, OledI2cDriver.ICON_HEART_DATA);
                oled.refresh();
                x += dx; y += dy;
                if (x <= 0 || x >= DISPLAY_W - 16) { dx = -dx; x = Math.max(0, Math.min(x, DISPLAY_W - 16)); }
                if (y <= 0 || y >= DISPLAY_H - 16) { dy = -dy; y = Math.max(0, Math.min(y, DISPLAY_H - 16)); }
                try { Thread.sleep(30); } catch (InterruptedException e) { break; }
            }
        }, "oled-bounce");
        animThread.start();
    }

    // ---- Eye animation (smooth, cartoon-style) ----

    private void startEyes() {
        if (oled == null) return;
        stopAnim();
        animRunning = true;
        currentExpression = OledI2cDriver.EXPR_NORMAL;
        currentPupilX = 0; currentPupilY = 0;
        targetPupilX = 0; targetPupilY = 0;
        nextBlinkTime = System.currentTimeMillis() + 2000;
        blinkOpenness = 100; // for smooth blink animation
        btnStop.setEnabled(true);
        log("Starting animated eyes...");

        // Cartoon eye params for 128x64 display:
        // Left eye center=(38,32), right eye center=(90,32)
        // Half-width=18, half-height=14, corner radius=6
        final int leftCx = 38, rightCx = 90, cy = 32;
        final int w = 18, h = 14, cornerR = 6;
        final int maxPupilOffX = w - 5;
        final int maxPupilOffY = h - 4;

        animThread = new Thread(() -> {
            long lastLookChange = System.currentTimeMillis();
            long lookInterval = 1200 + (long)(Math.random() * 1800);
            long frameCount = 0;

            while (animRunning) {
                long now = System.currentTimeMillis();
                frameCount++;

                // Saccade: quick look to new random position
                if (now - lastLookChange > lookInterval) {
                    targetPupilX = (int)((Math.random() - 0.5) * 2 * maxPupilOffX);
                    targetPupilY = (int)((Math.random() - 0.5) * 2 * maxPupilOffY);
                    lastLookChange = now;
                    lookInterval = 800 + (long)(Math.random() * 2500);
                }

                // Smooth eased pupil interpolation (fast start, slow end)
                int dx = targetPupilX - currentPupilX;
                int dy = targetPupilY - currentPupilY;
                currentPupilX += dx / 5;
                currentPupilY += dy / 5;

                // Smooth blink animation (gradual close/open over ~120ms)
                int expr = currentExpression;
                if (expr == OledI2cDriver.EXPR_NORMAL || expr == OledI2cDriver.EXPR_HAPPY) {
                    if (now > nextBlinkTime && blinkOpenness > 0) {
                        // Closing phase
                        blinkOpenness -= 8;
                        if (blinkOpenness <= 0) {
                            blinkOpenness = 0;
                            nextBlinkTime = now + 60; // hold closed briefly
                        }
                    } else if (blinkOpenness < 100 && now > nextBlinkTime) {
                        // Opening phase
                        blinkOpenness += 10;
                        if (blinkOpenness >= 100) {
                            blinkOpenness = 100;
                            nextBlinkTime = now + 2500 + (long)(Math.random() * 3000);
                        }
                    }
                    // Use custom openness for smooth blink
                    expr = OledI2cDriver.EXPR_NORMAL; // base expression
                } else {
                    blinkOpenness = 100; // reset when not in auto-blink
                }

                // Draw eyes
                oled.clear();
                oled.drawEye(leftCx, cy, w, h, cornerR, currentPupilX, currentPupilY, expr, true);
                oled.drawEye(rightCx, cy, w, h, cornerR, currentPupilX, currentPupilY, expr, false);

                // Apply smooth blink by drawing eyelid overlay
                if (blinkOpenness < 100) {
                    drawBlinkOverlay(leftCx, cy, w, h, blinkOpenness);
                    drawBlinkOverlay(rightCx, cy, w, h, blinkOpenness);
                }

                oled.refresh();

                // 50fps for smooth animation
                try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        }, "oled-eyes");
        animThread.start();
    }

    /**
     * Draw a blink overlay (black fill from top) with smooth openness.
     */
    private void drawBlinkOverlay(int cx, int cy, int w, int h, int openness) {
        int lidY = cy - h + (h * 2 * (100 - openness)) / 100;
        if (lidY <= cy - h) return; // fully open
        for (int y = cy - h; y < lidY && y <= cy + h; y++) {
            for (int x = cx - w; x <= cx + w; x++) {
                oled.clearPixel(x, y);
            }
        }
        // Draw lid edge
        if (openness > 5 && openness < 95) {
            for (int x = cx - w + 4; x <= cx + w - 4; x++) {
                oled.setPixel(x, lidY);
            }
        }
    }

    private void setExpression(int expr) {
        currentExpression = expr;
        // For blink, trigger a single blink then return to normal
        if (expr == OledI2cDriver.EXPR_BLINK) {
            triggerBlink();
        } else {
            log("Expression: " + exprName(expr));
        }
    }

    private void triggerBlink() {
        int prevExpr = currentExpression;
        currentExpression = OledI2cDriver.EXPR_BLINK;
        log("Blink!");
        ui.postDelayed(() -> {
            if (currentExpression == OledI2cDriver.EXPR_BLINK) {
                currentExpression = prevExpr;
            }
        }, 200);
    }

    private String exprName(int expr) {
        switch (expr) {
            case OledI2cDriver.EXPR_NORMAL:    return "Normal";
            case OledI2cDriver.EXPR_HAPPY:     return "Happy";
            case OledI2cDriver.EXPR_SAD:       return "Sad";
            case OledI2cDriver.EXPR_ANGRY:     return "Angry";
            case OledI2cDriver.EXPR_SURPRISED: return "Surprised";
            case OledI2cDriver.EXPR_WINK:      return "Wink";
            case OledI2cDriver.EXPR_SLEEPY:    return "Sleepy";
            case OledI2cDriver.EXPR_BLINK:     return "Blink";
            default: return "?";
        }
    }

    // ---- Stop ----

    private void stopAnim() {
        animRunning = false;
        if (animThread != null) {
            animThread.interrupt();
            animThread = null;
        }
        btnStop.setEnabled(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
