package com.quectel.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class GpioActivity extends Activity {


    /**
     * Controlled GPIO pin. Change this to the pin you actually wired.
     */
    /**
     * Base of the gpiochip that carries these lines.
     * Confirm it on the board with:  cat /sys/class/gpio/gpiochip385/base
     */
    private static final int GPIOCHIP_BASE = 385;

    /**
     * Labels exactly as the BSP documents them.
     */
    private static final int GPIO_100 = 100;   // RST
    private static final int GPIO_101 = 101;   // WAKE

    /**
     * BSP label -> sysfs number. Everything below works in sysfs numbers.
     */
    private static int sysfsPin(int label) {
        return GPIOCHIP_BASE + label;
    }

    private static final int PIN_100 = sysfsPin(GPIO_100);   // 485
    private static final int PIN_101 = sysfsPin(GPIO_101);   // 486


    private boolean initialized;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gpio);

        // Initialize GPIO.
        int rc = GpioNative.gpioExport(PIN_100);
        if (rc == 0) {
            rc = GpioNative.gpioSetDirection(PIN_100, GpioNative.DIR_OUT);
        }
        if (rc == 0) {
            initialized = true;
            status("gpio " + PIN_100 + " ready (output)");
        } else {
            status("gpioInit(" + PIN_100 + ") failed: " + errno(rc));
            toast("GPIO init failed: " + errno(rc));
        }

        // High level button.
        Button btnHigh = findViewById(R.id.btn_gpio_high);
        btnHigh.setOnClickListener(v -> setLevel(1));

        // Low level button.
        Button btnLow = findViewById(R.id.btn_gpio_low);
        btnLow.setOnClickListener(v -> setLevel(0));
    }

    private void setLevel(int value) {
        if (!initialized) {
            toast("GPIO not initialized");
            return;
        }

        int rc = GpioNative.gpioSetValue(PIN_100, value);
        if (rc == 0) {
            status("gpio " + PIN_100 + " -> " + (value != 0 ? "HIGH" : "LOW"));
        } else {
            status("gpioSetValue(" + value + ") failed: " + errno(rc));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Release GPIO. Note: this unexports the pin even if another process
        // exported it first - drop this call if the pin is shared.
        if (initialized) {
            GpioNative.gpioUnexport(PIN_100);
            initialized = false;
        }
    }

    // ==================== helpers ====================

    private void status(String s) {
        TextView tv = findViewById(R.id.tv_gpio_status);
        if (tv != null) tv.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String errno(int rc) {
        switch (rc) {
            case 0:
                return "OK";
            case -2:
                return "-2 (ENOENT - pin not exported?)";
            case -13:
                return "-13 (EACCES - permission denied, need root/SELinux)";
            case -16:
                return "-16 (EBUSY - pin busy / already in use)";
            case -19:
                return "-19 (ENODEV - no such gpio attribute)";
            case -22:
                return "-22 (EINVAL - invalid argument)";
            default:
                return rc + " (errno " + (-rc) + ")";
        }
    }


}
