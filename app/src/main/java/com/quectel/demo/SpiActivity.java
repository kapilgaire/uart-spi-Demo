package com.quectel.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * UI demo for driving the 40-pin SPI interface.
 *
 * SPI defaults to /dev/spidev0.0, mode 0, 1 MHz, 8-bit. All paths/settings
 * are editable on the page so the demo works for whichever node the board exposes.
 */
public class SpiActivity extends Activity {

    // ---- SPI ----
    private EditText etSpiDev, etSpiMode, etSpiSpeed, etSpiTx, etSpiReadLen;
    private Button btnSpiOpen, btnSpiTransfer, btnSpiRead;
    private TextView tvSpiRx;
    private int spiFd = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_spi);

        bindViews();
        bindSpi();
    }

    private void bindViews() {
        etSpiDev     = findViewById(R.id.et_spi_dev);
        etSpiMode    = findViewById(R.id.et_spi_mode);
        etSpiSpeed   = findViewById(R.id.et_spi_speed);
        etSpiTx      = findViewById(R.id.et_spi_tx);
        etSpiReadLen = findViewById(R.id.et_spi_readlen);
        btnSpiOpen   = findViewById(R.id.btn_spi_open);
        btnSpiTransfer = findViewById(R.id.btn_spi_transfer);
        btnSpiRead   = findViewById(R.id.btn_spi_read);
        tvSpiRx      = findViewById(R.id.tv_spi_rx);
    }

    // ==================== SPI ====================

    private void bindSpi() {
        btnSpiOpen.setOnClickListener(v -> {
            if (spiFd >= 0) {
                closeSpi();
            } else {
                openSpi();
            }
        });

        btnSpiTransfer.setOnClickListener(v -> {
            if (spiFd < 0) {
                toast("Open SPI first");
                return;
            }
            String text = etSpiTx.getText().toString().trim();
            if (text.isEmpty()) {
                toast("TX is empty");
                return;
            }
            byte[] tx = hexToBytes(text);
            if (tx == null) {
                toast("TX HEX format error");
                return;
            }
            byte[] rx = SerialSpiNative.spiTransfer(spiFd, tx);
            if (rx == null) {
                toast("SPI transfer failed");
            } else {
                showSpiRx(rx);
            }
        });

        btnSpiRead.setOnClickListener(v -> {
            if (spiFd < 0) {
                toast("Open SPI first");
                return;
            }
            int n;
            try {
                n = Integer.parseInt(etSpiReadLen.getText().toString().trim());
            } catch (NumberFormatException e) {
                toast("Invalid read length");
                return;
            }
            if (n <= 0) {
                toast("Read length must be > 0");
                return;
            }
            byte[] rx = SerialSpiNative.spiRead(spiFd, n);
            if (rx == null) {
                toast("SPI read failed");
            } else {
                showSpiRx(rx);
            }
        });
    }

    private void openSpi() {
        String dev = etSpiDev.getText().toString().trim();
        int mode, bits = 8, speed;
        try {
            mode  = Integer.parseInt(etSpiMode.getText().toString().trim());
            speed = Integer.parseInt(etSpiSpeed.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("Invalid SPI params");
            return;
        }

        int fd = SerialSpiNative.spiOpen(dev, mode, bits, speed);
        if (fd < 0) {
            toast("SPI open failed (code " + fd + ")");
            return;
        }

        spiFd = fd;
        btnSpiOpen.setText("Close");
        etSpiDev.setEnabled(false);
        etSpiMode.setEnabled(false);
        etSpiSpeed.setEnabled(false);
        toast("SPI opened fd=" + fd);
    }

    private void closeSpi() {
        if (spiFd >= 0) {
            SerialSpiNative.spiClose(spiFd);
            spiFd = -1;
        }
        btnSpiOpen.setText("Open");
        etSpiDev.setEnabled(true);
        etSpiMode.setEnabled(true);
        etSpiSpeed.setEnabled(true);
        toast("SPI closed");
    }

    private void showSpiRx(byte[] rx) {
        tvSpiRx.setText("RX " + rx.length + "B\nhex  " + bytesToHex(rx)
                + "\nascii " + bytesToAscii(rx));
    }

    // ==================== helpers ====================

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** Parses "AA BB", "AABB", "0xAA 0xBB" (any non-hex separators). Returns null on error. */
    static byte[] hexToBytes(String s) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                hex.append(c);
            } else if (!Character.isWhitespace(c) && c != ',' && c != ':' && c != 'x' && c != 'X') {
                return null;
            }
        }
        if (hex.length() == 0 || hex.length() % 2 != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format(Locale.US, "%02X ", v));
        return sb.toString().trim();
    }

    static String bytesToAscii(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) {
            int c = v & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (spiFd >= 0) closeSpi();
    }
}