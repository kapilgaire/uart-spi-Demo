package com.quectel.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Waveshare SPI OLED demo activity.
 *
 * Two functions:
 *  - "Run All Tests": runs the full 6-test suite (init, text, fill, blink, FPS, CS check)
 *  - "Show on OLED": displays the user's text input on the OLED screen
 *
 * GPIO pins (sysfs or chardev) control RST and DC; SPI handles display data.
 */
public class OledActivity extends Activity {

    private EditText etSpiDev, etRstGpio, etDcGpio, etText;
    private Button btnRun, btnShow;
    private TextView tvLog;
    private ScrollView svLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oled);

        etSpiDev  = findViewById(R.id.et_spi_dev);
        etRstGpio = findViewById(R.id.et_rst_gpio);
        etDcGpio  = findViewById(R.id.et_dc_gpio);
        etText    = findViewById(R.id.et_text);
        btnRun    = findViewById(R.id.btn_run);
        btnShow   = findViewById(R.id.btn_show);
        tvLog     = findViewById(R.id.tv_log);
        svLog     = findViewById(R.id.sv_log);

        btnRun.setOnClickListener(v -> runTests());
        btnShow.setOnClickListener(v -> showText());
    }

    private void runTests() {
        String spiDev = etSpiDev.getText().toString().trim();
        int rstGpio, dcGpio;

        if (spiDev.isEmpty()) { toast("Enter SPI device path"); return; }
        try {
            rstGpio = Integer.parseInt(etRstGpio.getText().toString().trim());
            dcGpio  = Integer.parseInt(etDcGpio.getText().toString().trim());
        } catch (NumberFormatException e) { toast("Invalid GPIO numbers"); return; }

        btnRun.setEnabled(false);
        btnRun.setText("Running...");
        btnShow.setEnabled(false);
        tvLog.setText("Running tests...\n(please wait ~30 seconds)");

        new Thread(() -> {
            final String result = SerialSpiNative.oledRunTests(spiDev, rstGpio, dcGpio);
            runOnUiThread(() -> {
                tvLog.setText(result);
                svLog.fullScroll(ScrollView.FOCUS_DOWN);
                btnRun.setEnabled(true);
                btnRun.setText("Run All Tests");
                btnShow.setEnabled(true);
            });
        }).start();
    }

    private void showText() {
        String spiDev = etSpiDev.getText().toString().trim();
        String text = etText.getText().toString();

        if (spiDev.isEmpty()) { toast("Enter SPI device path"); return; }
        if (text.isEmpty()) { toast("Enter text to display"); return; }

        int rstGpio, dcGpio;
        try {
            rstGpio = Integer.parseInt(etRstGpio.getText().toString().trim());
            dcGpio  = Integer.parseInt(etDcGpio.getText().toString().trim());
        } catch (NumberFormatException e) { toast("Invalid GPIO numbers"); return; }

        btnShow.setEnabled(false);
        btnShow.setText("Sending...");

        new Thread(() -> {
            final String result = SerialSpiNative.oledShowText(spiDev, rstGpio, dcGpio, text);
            runOnUiThread(() -> {
                tvLog.setText(result);
                btnShow.setEnabled(true);
                btnShow.setText("Show on OLED");
            });
        }).start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
