package com.quectel.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * UI demo for reading and writing an I2C sensor over /dev/i2c-<bus>.
 *
 * Defaults target a typical sensor/RTC at 0x68 (e.g. MPU-6050, DS1307/DS3231):
 *   - bus scan (0x03..0x77)
 *   - single-register read/write (8-bit and 16-bit, SMBus byte/word order)
 *   - block read/write (register pointer + N bytes)
 *   - raw register-less read/write
 *
 * Every field stays editable, so the page works for whichever bus node and
 * chip the board actually exposes. The slave address can also be changed while
 * the bus is open - the native layer re-targets on the next operation.
 */
public class I2cActivity extends Activity {

    private EditText etDev, etAddr, etReg, etLen, etWriteData, etRawTx, etRawLen;
    private Button btnOpen, btnScan, btnReadReg8, btnReadReg16, btnReadReg, btnWriteReg;
    private Button btnRawWrite, btnRawRead;
    private TextView tvScan, tvOut;

    private int i2cFd = -1;
    private int appliedAddr = -1;
    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_i2c);

        etDev       = findViewById(R.id.et_i2c_dev);
        etAddr      = findViewById(R.id.et_i2c_addr);
        etReg       = findViewById(R.id.et_i2c_reg);
        etLen       = findViewById(R.id.et_i2c_len);
        etWriteData = findViewById(R.id.et_i2c_wdata);
        etRawTx     = findViewById(R.id.et_i2c_raw_tx);
        etRawLen    = findViewById(R.id.et_i2c_raw_len);
        tvScan      = findViewById(R.id.tv_i2c_scan);
        tvOut       = findViewById(R.id.tv_i2c_out);

        btnOpen      = findViewById(R.id.btn_i2c_open);
        btnScan      = findViewById(R.id.btn_i2c_scan);
        btnReadReg8  = findViewById(R.id.btn_i2c_read_reg8);
        btnReadReg16 = findViewById(R.id.btn_i2c_read_reg16);
        btnReadReg   = findViewById(R.id.btn_i2c_read_reg);
        btnWriteReg  = findViewById(R.id.btn_i2c_write_reg);
        btnRawWrite  = findViewById(R.id.btn_i2c_raw_write);
        btnRawRead   = findViewById(R.id.btn_i2c_raw_read);

        findViewById(R.id.btn_open_gpio).setOnClickListener(v ->
                startActivity(new Intent(this, GpioActivity.class)));

        btnOpen.setOnClickListener(v -> {
            if (i2cFd >= 0) closeBus(); else openBus();
        });
        btnScan.setOnClickListener(v -> scanBus());
        btnReadReg8.setOnClickListener(v -> readReg8());
        btnReadReg16.setOnClickListener(v -> readReg16());
        btnReadReg.setOnClickListener(v -> readRegBlock());
        btnWriteReg.setOnClickListener(v -> writeRegBlock());
        btnRawWrite.setOnClickListener(v -> rawWrite());
        btnRawRead.setOnClickListener(v -> rawRead());
    }

    // ==================== bus open / close ====================

    private void openBus() {
        String dev = etDev.getText().toString().trim();
        if (TextUtils.isEmpty(dev)) {
            toast("Enter a device node, e.g. /dev/i2c-1");
            return;
        }
        int addr = parseHex(etAddr.getText().toString());
        if (addr < 0) {
            toast("Invalid slave address (hex, e.g. 68 or 0x68)");
            return;
        }

        int fd = I2cNative.i2cOpen(dev, addr);
        if (fd < 0) {
            String msg = "i2cOpen(" + dev + ") failed: " + I2cNative.errnoName(fd);
            log(msg);
            toast("I2C open failed: " + I2cNative.errnoName(fd));
            return;
        }

        i2cFd = fd;
        appliedAddr = addr;
        btnOpen.setText("Close");
        etDev.setEnabled(false);
        log("opened " + dev + "  fd=" + fd + "  slave=0x" + hex2(addr));
    }

    private void closeBus() {
        if (i2cFd >= 0) {
            I2cNative.i2cClose(i2cFd);
            log("closed fd=" + i2cFd);
            i2cFd = -1;
        }
        appliedAddr = -1;
        btnOpen.setText("Open");
        etDev.setEnabled(true);
    }

    /** Re-targets the native layer if the address field changed. Returns -1 on error. */
    private int useAddress() {
        int addr = parseHex(etAddr.getText().toString());
        if (addr < 0) {
            toast("Invalid slave address");
            return -1;
        }
        if (addr != appliedAddr) {
            int rc = I2cNative.i2cSetAddress(i2cFd, addr);
            if (rc != 0) {
                String msg = "setAddress(0x" + hex2(addr) + ") failed: " + I2cNative.errnoName(rc);
                log(msg);
                toast(msg);
                return -1;
            }
            appliedAddr = addr;
            log("slave -> 0x" + hex2(addr));
        }
        return addr;
    }

    private boolean ready() {
        if (i2cFd < 0) {
            toast("Open the bus first");
            return false;
        }
        return useAddress() >= 0;
    }

    // ==================== scan ====================

    private void scanBus() {
        if (i2cFd < 0) {
            toast("Open the bus first");
            return;
        }
        int[] found = I2cNative.i2cScan(i2cFd, I2cNative.ADDR_FIRST, I2cNative.ADDR_LAST);
        if (found == null) {
            toast("Scan failed");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "scan 0x%02X..0x%02X : %d device(s) found",
                I2cNative.ADDR_FIRST, I2cNative.ADDR_LAST, found.length));
        for (int a : found) {
            sb.append(String.format(Locale.US, "%n  0x%02X", a));
        }
        tvScan.setText(sb.toString());
        log(sb.toString().replace("%n", " "));
    }

    // ==================== register access ====================

    private void readReg8() {
        if (!ready()) return;
        int reg = parseHex(etReg.getText().toString());
        if (reg < 0) {
            toast("Invalid register (hex, e.g. 75)");
            return;
        }

        int v = I2cNative.i2cReadReg8(i2cFd, reg);
        if (v < 0) {
            log("readReg8(0x" + hex2(reg) + ") failed: " + I2cNative.errnoName(v));
            toast("Read failed: " + I2cNative.errnoName(v));
            return;
        }
        char c = (v >= 0x20 && v < 0x7F) ? (char) v : '.';
        log(String.format(Locale.US, "readReg8 (0x%02X) = 0x%02X  %d  '%c'", reg, v, v, c));
    }

    private void readReg16() {
        if (!ready()) return;
        int reg = parseHex(etReg.getText().toString());
        if (reg < 0) {
            toast("Invalid register (hex, e.g. 75)");
            return;
        }

        int v = I2cNative.i2cReadReg16(i2cFd, reg);
        if (v < 0) {
            log("readReg16(0x" + hex2(reg) + ") failed: " + I2cNative.errnoName(v));
            toast("Read failed: " + I2cNative.errnoName(v));
            return;
        }
        log(String.format(Locale.US, "readReg16(0x%02X) = 0x%04X  %d", reg, v, v));
    }

    private void readRegBlock() {
        if (!ready()) return;
        int reg = parseHex(etReg.getText().toString());
        if (reg < 0) {
            toast("Invalid register (hex, e.g. 75)");
            return;
        }
        int len = parseDec(etLen.getText().toString());
        if (len <= 0) {
            toast("Invalid length");
            return;
        }

        byte[] rx = I2cNative.i2cReadReg(i2cFd, reg, len);
        if (rx == null) {
            log("readReg(0x" + hex2(reg) + ", " + len + ") failed");
            toast("Read failed");
            return;
        }
        log("readReg(0x" + hex2(reg) + ", " + len + ")"
                + "\n  hex   " + bytesToHex(rx)
                + "\n  ascii " + bytesToAscii(rx));
    }

    private void writeRegBlock() {
        if (!ready()) return;
        int reg = parseHex(etReg.getText().toString());
        if (reg < 0) {
            toast("Invalid register (hex, e.g. 75)");
            return;
        }
        byte[] data = hexToBytes(etWriteData.getText().toString());
        if (data == null || data.length == 0) {
            toast("Write data must be hex, e.g. 01 or A0 12");
            return;
        }

        int rc = I2cNative.i2cWriteReg(i2cFd, reg, data);
        if (rc != 0) {
            log("writeReg(0x" + hex2(reg) + ") failed: " + I2cNative.errnoName(rc));
            toast("Write failed: " + I2cNative.errnoName(rc));
            return;
        }
        log("writeReg(0x" + hex2(reg) + ") <- " + bytesToHex(data) + "  ok");

        // Read back so a write is immediately verifiable.
        byte[] rx = I2cNative.i2cReadReg(i2cFd, reg, data.length);
        if (rx != null) {
            log("  read-back " + bytesToHex(rx)
                    + (java.util.Arrays.equals(rx, data) ? "  (matches)" : "  (DIFFERS)"));
        }
    }

    // ==================== raw (no register pointer) ====================

    private void rawWrite() {
        if (!ready()) return;
        byte[] data = hexToBytes(etRawTx.getText().toString());
        if (data == null || data.length == 0) {
            toast("Raw TX must be hex, e.g. 01 02 AA");
            return;
        }
        int n = I2cNative.i2cWrite(i2cFd, data);
        if (n < 0) {
            log("rawWrite failed: " + I2cNative.errnoName(n));
            toast("Write failed: " + I2cNative.errnoName(n));
            return;
        }
        log("rawWrite " + n + "B  " + bytesToHex(data));
    }

    private void rawRead() {
        if (!ready()) return;
        int len = parseDec(etRawLen.getText().toString());
        if (len <= 0) {
            toast("Invalid length");
            return;
        }
        byte[] rx = I2cNative.i2cRead(i2cFd, len);
        if (rx == null) {
            log("rawRead(" + len + ") failed");
            toast("Read failed");
            return;
        }
        log("rawRead " + rx.length + "B"
                + "\n  hex   " + bytesToHex(rx)
                + "\n  ascii " + bytesToAscii(rx));
    }

    // ==================== helpers ====================

    private void log(String s) {
        logBuf.append(s).append('\n');
        tvOut.setText(logBuf.toString());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String hex2(int v) {
        return String.format(Locale.US, "%02X", v & 0xFF);
    }

    /** Parses a hex value: "0x68", "68", "A0". Returns -1 on error. */
    static int parseHex(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.isEmpty()) return -1;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int parseDec(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Parses "AA BB", "AABB", "0xAA 0xBB". Returns null on error. */
    static byte[] hexToBytes(String s) {
        if (s == null) return null;
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
        if (i2cFd >= 0) closeBus();
    }
}
