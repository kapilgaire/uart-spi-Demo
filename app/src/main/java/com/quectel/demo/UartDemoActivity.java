package com.quectel.demo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class UartDemoActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText etUartDev, etUartBaud, etUartTx;
    private CheckBox cbUartHex;
    private Button btnUartOpen, btnUartSend, btnUartClear;
    private TextView tvUartRx;

    private int uartFd = -1;
    private Thread uartRxThread;
    private volatile boolean uartRxRunning;
    private final StringBuilder uartRxLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_uart_demo);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bindViews();
        bindUart();
    }

    private void bindViews() {
        etUartDev    = findViewById(R.id.et_uart_dev);
        etUartBaud   = findViewById(R.id.et_uart_baud);
        etUartTx     = findViewById(R.id.et_uart_tx);
        cbUartHex    = findViewById(R.id.cb_uart_hex);
        btnUartOpen  = findViewById(R.id.btn_uart_open);
        btnUartSend  = findViewById(R.id.btn_uart_send);
        btnUartClear = findViewById(R.id.btn_uart_clear);
        tvUartRx     = findViewById(R.id.tv_uart_rx);
    }

    private void bindUart() {
        btnUartOpen.setOnClickListener(v -> {
            if (uartFd >= 0) {
                closeUart();
            } else {
                openUart();
            }
        });

        btnUartSend.setOnClickListener(v -> sendUart());

        btnUartClear.setOnClickListener(v -> {
            uartRxLog.setLength(0);
            tvUartRx.setText("");
        });
    }

    private void openUart() {
        String dev = etUartDev.getText().toString().trim();
        int baud;
        try {
            baud = Integer.parseInt(etUartBaud.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("Invalid baud rate");
            return;
        }

        int fd = SerialSpiNative.serialOpen(dev, baud);
        if (fd < 0) {
            toast("Serial open failed (code " + fd + ")");
            return;
        }

        uartFd = fd;
        btnUartOpen.setText("Close");
        etUartDev.setEnabled(false);
        etUartBaud.setEnabled(false);
        toast("Serial opened fd=" + fd);

        startUartRx();
    }

    private void closeUart() {
        uartRxRunning = false;
        if (uartRxThread != null) {
            uartRxThread.interrupt();
            uartRxThread = null;
        }
        if (uartFd >= 0) {
            SerialSpiNative.serialClose(uartFd);
            uartFd = -1;
        }
        btnUartOpen.setText("Open");
        etUartDev.setEnabled(true);
        etUartBaud.setEnabled(true);
        toast("Serial closed");
    }

    private void startUartRx() {
        uartRxRunning = true;
        uartRxThread = new Thread(() -> {
            byte[] buf;
            while (uartRxRunning) {
                try {
                    buf = SerialSpiNative.serialRead(uartFd, 200);
                } catch (Throwable t) {
                    buf = null;
                }
                if (buf != null && buf.length > 0) {
                    final String line = "RX " + buf.length + "B | hex " + bytesToHex(buf)
                            + " | " + bytesToAscii(buf) + "\n";
                    ui.post(() -> appendUartRx(line));
                }
            }
        }, "uart-rx");
        uartRxThread.start();
    }

    private void appendUartRx(String s) {
        uartRxLog.append(s);
        tvUartRx.setText(uartRxLog.toString());
    }

    private void sendUart() {
        if (uartFd < 0) {
            toast("Open serial first");
            return;
        }
        String text = etUartTx.getText().toString();
        if (TextUtils.isEmpty(text)) {
            toast("Send content is empty");
            return;
        }

        byte[] data;
        if (cbUartHex.isChecked()) {
            data = hexToBytes(text);
            if (data == null) {
                toast("HEX format error");
                return;
            }
        } else {
            data = text.getBytes(StandardCharsets.UTF_8);
        }

        int n = SerialSpiNative.serialWrite(uartFd, data);
        if (n < 0) {
            toast("Serial write failed");
        } else {
            appendUartRx("TX " + n + "B | hex " + bytesToHex(data) + "\n");
        }
    }

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
        if (uartFd >= 0) closeUart();
    }
}