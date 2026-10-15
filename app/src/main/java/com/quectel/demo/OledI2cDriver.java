package com.quectel.demo;

/**
 * SSD1306 OLED driver over I2C.
 * <p>
 * Uses I2cNative for bus access. The SSD1306 "register" is the control byte:
 * 0x00 = next byte is a command
 * 0x40 = next byte(s) are display data
 * <p>
 * Supports 128x64 (8 pages) and 128x32 (4 pages) displays.
 */
public class OledI2cDriver {

    private static final int SSD1306_ADDR = 0x3C;
    private static final int CTRL_CMD = 0x00;
    private static final int CTRL_DAT = 0x40;

    private int fd = -1;
    private int width, height, pages;
    private byte[][] buffer;

    // ---- 5x7 font (same as SPI version) ----
    private static final byte[][] FONT_5X7 = {
            {0x00, 0x00, 0x00, 0x00, 0x00}, // Space
            {0x3E, 0x51, 0x49, 0x45, 0x3E}, // 0
            {0x00, 0x42, 0x7F, 0x40, 0x00}, // 1
            {0x42, 0x61, 0x51, 0x49, 0x46}, // 2
            {0x21, 0x41, 0x45, 0x4B, 0x31}, // 3
            {0x18, 0x14, 0x12, 0x7F, 0x10}, // 4
            {0x27, 0x45, 0x45, 0x45, 0x39}, // 5
            {0x3C, 0x4A, 0x49, 0x49, 0x30}, // 6
            {0x01, 0x71, 0x09, 0x05, 0x03}, // 7
            {0x36, 0x49, 0x49, 0x49, 0x36}, // 8
            {0x06, 0x49, 0x49, 0x29, 0x1E}, // 9
            {0x7E, 0x11, 0x11, 0x11, 0x7E}, // A
            {0x7F, 0x49, 0x49, 0x49, 0x36}, // B
            {0x3E, 0x41, 0x41, 0x41, 0x22}, // C
            {0x7F, 0x41, 0x41, 0x22, 0x1C}, // D
            {0x7F, 0x49, 0x49, 0x49, 0x41}, // E
            {0x7F, 0x09, 0x09, 0x09, 0x01}, // F
            {0x3E, 0x41, 0x49, 0x49, 0x7A}, // G
            {0x7F, 0x08, 0x08, 0x08, 0x7F}, // H
            {0x00, 0x41, 0x7F, 0x41, 0x00}, // I
            {0x20, 0x40, 0x41, 0x3F, 0x01}, // J
            {0x7F, 0x08, 0x14, 0x22, 0x41}, // K
            {0x7F, 0x40, 0x40, 0x40, 0x40}, // L
            {0x7F, 0x02, 0x0C, 0x02, 0x7F}, // M
            {0x7F, 0x04, 0x08, 0x10, 0x7F}, // N
            {0x3E, 0x41, 0x41, 0x41, 0x3E}, // O
            {0x7F, 0x09, 0x09, 0x09, 0x06}, // P
            {0x3E, 0x41, 0x51, 0x21, 0x5E}, // Q
            {0x7F, 0x09, 0x19, 0x29, 0x46}, // R
            {0x46, 0x49, 0x49, 0x49, 0x31}, // S
            {0x01, 0x01, 0x7F, 0x01, 0x01}, // T
            {0x3F, 0x40, 0x40, 0x40, 0x3F}, // U
            {0x1F, 0x20, 0x40, 0x20, 0x1F}, // V
            {0x3F, 0x40, 0x38, 0x40, 0x3F}, // W
            {0x63, 0x14, 0x08, 0x14, 0x63}, // X
            {0x07, 0x08, 0x70, 0x08, 0x07}, // Y
            {0x61, 0x51, 0x49, 0x45, 0x43}, // Z
    };

    // ---- Preset icons (16x16) ----
    // Each icon is 32 bytes (16 rows x 2 bytes per row)
    public static final byte[][] ICON_HEART = {
            {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
    };

    public OledI2cDriver() {
        this(128, 64);
    }

    public OledI2cDriver(int width, int height) {
        this.width = width;
        this.height = height;
        this.pages = height / 8;
        this.buffer = new byte[pages][width];
    }

    /**
     * Open I2C bus and initialize the SSD1306.
     *
     * @return true if successful
     */
    public boolean open(String i2cDev, int addr) {
        fd = I2cNative.i2cOpen(i2cDev, addr);
        if (fd < 0) return false;

        // SSD1306 init sequence
        sendCmd(0xAE); // Display OFF
        sendCmd(0x00); // Lower column start
        sendCmd(0x10); // Upper column start
        sendCmd(0x40); // Start line 0
        sendCmd(0x81);
        sendCmd(0xFF); // Contrast max
        sendCmd(0xA1); // Segment remap (mirror)
        sendCmd(0xA6); // Normal (not inverted)
        sendCmd(0xA8);
        sendCmd((byte) (height - 1)); // Multiplex
        sendCmd(0xC8); // COM scan: bottom to top
        sendCmd(0xD3);
        sendCmd(0x00); // Display offset 0
        sendCmd(0xD5);
        sendCmd(0x80); // Clock div
        sendCmd(0xD9);
        sendCmd(0xF1); // Precharge (for I2C)
        sendCmd(0xDA);
        sendCmd(height == 64 ? 0x12 : 0x02); // COM pins
        sendCmd(0xDB);
        sendCmd(0x40); // VCOMH deselect
        sendCmd(0x8D);
        sendCmd(0x14); // Charge pump ON
        sendCmd(0xAF); // Display ON

        clear();
        return true;
    }

    public void close() {
        if (fd >= 0) {
            I2cNative.i2cClose(fd);
            fd = -1;
        }
    }

    // ---- Drawing primitives ----

    public void clear() {
        for (int p = 0; p < pages; p++)
            for (int x = 0; x < width; x++)
                buffer[p][x] = 0;
    }

    public void setPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        buffer[y / 8][x] |= (byte) (1 << (y % 8));
    }

    public void clearPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        buffer[y / 8][x] &= (byte) ~(1 << (y % 8));
    }

    public void drawChar(int x, int y, char c) {
        int idx = charToIndex(c);
        if (idx < 0) return;
        for (int col = 0; col < 5; col++) {
            byte data = FONT_5X7[idx][col];
            for (int row = 0; row < 8; row++) {
                if ((data & (1 << row)) != 0) {
                    setPixel(x + col, y + row);
                }
            }
        }
    }

    public void drawText(int x, int y, String text) {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            drawChar(cursor, y, text.charAt(i));
            cursor += 6;
        }
    }

    /**
     * Draw a 16x16 icon at position (x, y).
     * Icon data: 32 bytes, row-major, 2 bytes per row (MSB left).
     */
    public void drawIcon(int x, int y, byte[] iconData) {
        for (int row = 0; row < 16; row++) {
            int byteIdx = row * 2;
            if (byteIdx + 1 >= iconData.length) break;
            int hi = iconData[byteIdx] & 0xFF;
            int lo = iconData[byteIdx + 1] & 0xFF;
            for (int bit = 0; bit < 16; bit++) {
                int val = bit < 8 ? (hi >> (7 - bit)) & 1 : (lo >> (15 - bit)) & 1;
                if (val != 0) setPixel(x + bit, y + row);
            }
        }
    }

    /**
     * Draw a bitmap at position (x, y).
     * Bitmap data: width*height/8 bytes, row-major, MSB first.
     */
    public void drawBitmap(int x, int y, int bmpW, int bmpH, byte[] data) {
        for (int row = 0; row < bmpH; row++) {
            int byteRow = row / 8;
            int bitRow = row % 8;
            for (int col = 0; col < bmpW; col++) {
                int byteIdx = byteRow * bmpW + col;
                if (byteIdx >= data.length) return;
                if ((data[byteIdx] & (1 << (7 - bitRow))) != 0) {
                    setPixel(x + col, y + row);
                }
            }
        }
    }

    // ---- Eye drawing (cartoon rounded-rect style) ----

    // Expression constants
    public static final int EXPR_NORMAL = 0;
    public static final int EXPR_HAPPY = 1;
    public static final int EXPR_SAD = 2;
    public static final int EXPR_ANGRY = 3;
    public static final int EXPR_SURPRISED = 4;
    public static final int EXPR_WINK = 5;
    public static final int EXPR_SLEEPY = 6;
    public static final int EXPR_BLINK = 7;

    /**
     * Draw a cartoon-style eye: rounded rectangle with smooth edges.
     * Uses a squircle-like shape — wider than tall for expressive look.
     *
     * @param cx         center X
     * @param cy         center Y
     * @param w          half-width of the eye
     * @param h          half-height of the eye
     * @param cornerR    corner radius
     * @param pupilOffX  pupil offset from center
     * @param pupilOffY  pupil offset from center
     * @param expression one of EXPR_* constants
     * @param isLeftEye  true for left eye (used for wink/eyebrow angle)
     */
    public void drawEye(int cx, int cy, int w, int h, int cornerR,
                        int pupilOffX, int pupilOffY,
                        int expression, boolean isLeftEye) {

        // Eyelid openness: 0 = fully closed, 100 = fully open
        int openness = 100;
        switch (expression) {
            case EXPR_HAPPY:
                openness = 50;
                break;
            case EXPR_SAD:
                openness = 60;
                break;
            case EXPR_ANGRY:
                openness = 35;
                break;
            case EXPR_SURPRISED:
                openness = 100;
                break;
            case EXPR_SLEEPY:
                openness = 25;
                break;
            case EXPR_BLINK:
                openness = 0;
                break;
            case EXPR_WINK:
                openness = isLeftEye ? 100 : 0;
                break;
            default:
                openness = 100;
                break;
        }

        // Effective height based on openness
        int effH = h * openness / 100;
        if (effH < 1) effH = 1;
        int effCornerR = cornerR * openness / 100;

        // Pupil size
        int pupilR = Math.min(w, h) / 3;
        if (expression == EXPR_SURPRISED) pupilR = Math.min(w, h) / 2;
        if (expression == EXPR_ANGRY) pupilR = Math.min(w, h) / 4;

        // Step 1: Draw eye body (filled rounded rectangle = white sclera)
        drawRoundedRectFilled(cx - w, cy - effH, cx + w, cy + effH, effCornerR, true);

        // Step 2: Draw pupil (circle inside the eye)
        int px = cx + pupilOffX;
        int py = cy + pupilOffY;
        // Clamp pupil inside the rounded rect
        int clampW = w - pupilR - 2;
        int clampH = effH - pupilR - 2;
        if (clampW < 0) clampW = 0;
        if (clampH < 0) clampH = 0;
        px = Math.max(cx - clampW, Math.min(cx + clampW, px));
        py = Math.max(cy - effH + pupilR, Math.min(cy + effH - pupilR, py));
        drawFilledCircle(px, py, pupilR, true);

        // Step 3: Eyelid line (if partially open)
        if (openness > 0 && openness < 100) {
            int lidY = cy - effH;
            // Draw a clean horizontal line across the eye at the lid position
            for (int x = cx - w + effCornerR; x <= cx + w - effCornerR; x++) {
                setPixel(x, lidY);
                setPixel(x, lidY + 1);
            }
            // Round the lid at the corners
            drawCornerArc(cx - w, lidY, effCornerR, 2, true);  // top-left
            drawCornerArc(cx + w, lidY, effCornerR, 1, true);  // top-right
        }

        // Step 4: Angry eyebrow (angled line above eye)
        if (expression == EXPR_ANGRY) {
            int browY = cy - h - 5;
            int sign = isLeftEye ? -1 : 1;
            for (int i = 0; i <= w; i++) {
                int y = browY + sign * i * 2 / 3;
                // Thick line (2px)
                setPixel(cx - w + i, y);
                setPixel(cx - w + i, y + 1);
            }
        }

        // Step 5: Happy eyebrow (arched)
        if (expression == EXPR_HAPPY) {
            int browY = cy - h - 4;
            for (int i = 0; i <= w; i++) {
                int arch = (i - w / 2) * (i - w / 2) * 3 / (w * w / 4 + 1);
                setPixel(cx - w / 2 + i, browY - arch);
                setPixel(cx - w / 2 + i, browY - arch + 1);
            }
        }
    }

    /**
     * Draw two cartoon eyes side by side.
     */
    public void drawEyes(int leftCx, int rightCx, int cy, int w, int h, int cornerR,
                         int pupilOffX, int pupilOffY, int expression) {
        drawEye(leftCx, cy, w, h, cornerR, pupilOffX, pupilOffY, expression, true);
        drawEye(rightCx, cy, w, h, cornerR, pupilOffX, pupilOffY, expression, false);
    }

    /**
     * Draw a filled rounded rectangle.
     */
    public void drawRoundedRectFilled(int x1, int y1, int x2, int y2, int r, boolean fill) {
        if (r < 0) r = 0;
        int w = x2 - x1;
        int h = y2 - y1;
        if (r > w / 2) r = w / 2;
        if (r > h / 2) r = h / 2;

        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                boolean inRect = true;
                // Check corners
                if (x < x1 + r && y < y1 + r) {
                    // Top-left corner
                    int dx = (x1 + r - 1) - x, dy = (y1 + r - 1) - y;
                    inRect = (dx * dx + dy * dy <= r * r);
                } else if (x > x2 - r && y < y1 + r) {
                    // Top-right corner
                    int dx = x - (x2 - r), dy = (y1 + r - 1) - y;
                    inRect = (dx * dx + dy * dy <= r * r);
                } else if (x < x1 + r && y > y2 - r) {
                    // Bottom-left corner
                    int dx = (x1 + r - 1) - x, dy = y - (y2 - r);
                    inRect = (dx * dx + dy * dy <= r * r);
                } else if (x > x2 - r && y > y2 - r) {
                    // Bottom-right corner
                    int dx = x - (x2 - r), dy = y - (y2 - r);
                    inRect = (dx * dx + dy * dy <= r * r);
                }
                if (inRect) {
                    if (fill) setPixel(x, y);
                    else clearPixel(x, y);
                }
            }
        }
    }

    /**
     * Draw a corner arc (quarter circle). quadrant: 1=top-right, 2=top-left, 3=bottom-left, 4=bottom-right
     */
    private void drawCornerArc(int cx, int cy, int r, int quadrant, boolean thick) {
        int sx = 0, sy = r;
        int d = 3 - 2 * r;
        while (sx <= sy) {
            plotCorner(cx, cy, sx, sy, quadrant, thick);
            plotCorner(cx, cy, sy, sx, quadrant, thick);
            if (d < 0) {
                d += 4 * sx + 6;
            } else {
                d += 4 * (sx - sy) + 10;
                sy--;
            }
            sx++;
        }
    }

    private void plotCorner(int cx, int cy, int x, int y, int quadrant, boolean thick) {
        int px, py;
        switch (quadrant) {
            case 1:
                px = cx + x;
                py = cy + y;
                break;  // top-right
            case 2:
                px = cx - x;
                py = cy + y;
                break;  // top-left
            case 3:
                px = cx - x;
                py = cy - y;
                break;  // bottom-left
            default:
                px = cx + x;
                py = cy - y;
                break; // bottom-right
        }
        setPixel(px, py);
        if (thick) setPixel(px, py + 1);
    }

    /**
     * Draw a filled circle using midpoint algorithm.
     */
    public void drawFilledCircle(int cx, int cy, int r, boolean fill) {
        if (r <= 0) return;
        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                if (x * x + y * y <= r * r) {
                    if (fill) setPixel(cx + x, cy + y);
                    else clearPixel(cx + x, cy + y);
                }
            }
        }
    }

    // ---- Display refresh ----

    public void refresh() {
        if (fd < 0) return;
        for (int page = 0; page < pages; page++) {
            sendCmd((byte) (0xB0 + page)); // Page address
            sendCmd(0x00);                 // Lower column
            sendCmd(0x10);                 // Upper column
            sendData(buffer[page]);
        }
    }

    // ---- SSD1306 low-level ----

    private void sendCmd(int cmd) {
        I2cNative.i2cWriteReg8(fd, CTRL_CMD, cmd);
    }

    private void sendCmd(byte cmd) {
        I2cNative.i2cWriteReg8(fd, CTRL_CMD, cmd & 0xFF);
    }

    private void sendData(byte[] data) {
        I2cNative.i2cWriteReg(fd, CTRL_DAT, data);
    }

    // ---- Font helper ----

    private static int charToIndex(char c) {
        if (c == ' ') return 0;
        if (c >= '0' && c <= '9') return 1 + (c - '0');
        if (c >= 'A' && c <= 'Z') return 11 + (c - 'A');
        if (c >= 'a' && c <= 'z') return 11 + (c - 'a');
        return -1;
    }

    // ---- Preset 16x16 icon data (row-major, MSB left) ----

    /**
     * Heart icon
     */
    public static final byte[] ICON_HEART_DATA = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x18, 0x18,
            0x3C, 0x3C, 0x7E, 0x7E, 0x7E, 0x7E, 0x7E, 0x7E,
            0x7E, 0x7E, 0x7E, 0x7E, 0x3C, 0x3C, 0x18, 0x18,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /**
     * Star icon
     */
    public static final byte[] ICON_STAR_DATA = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x18,
            0x00, 0x3C, 0x18, 0x7E, 0x18, 0x7E, 0x3C, (byte) 0xFF,
            0x7E, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x3C, 0x7E, 0x18, 0x3C,
            0x00, 0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /**
     * Wifi icon
     */
    public static final byte[] ICON_WIFI_DATA = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x18, 0x18, 0x3C, 0x3C, 0x7E, 0x7E, (byte) 0xFF, (byte) 0xFF,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x18,
    };

    /**
     * Battery full icon
     */
    public static final byte[] ICON_BATTERY_DATA = {
            0x00, 0x00, 0x7E, 0x00, (byte) 0xFF, 0x00, (byte) 0xC3, 0x00,
            (byte) 0xC3, 0x7E, (byte) 0xC3, (byte) 0xFF, (byte) 0xC3, (byte) 0xFF, (byte) 0xC3, (byte) 0xFF,
            (byte) 0xC3, (byte) 0xFF, (byte) 0xC3, (byte) 0xFF, (byte) 0xC3, 0x7E, (byte) 0xFF, 0x00,
            0x7E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };

    /**
     * Arrow up icon
     */
    public static final byte[] ICON_ARROW_UP_DATA = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x18, 0x00, 0x3C,
            0x00, 0x7E, (byte) 0xFF, (byte) 0xFF, 0x00, 0x7E, 0x00, 0x3C,
            0x00, 0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };

    /**
     * Checkmark icon
     */
    public static final byte[] ICON_CHECK_DATA = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x03,
            0x7C, 0x07, 0x3E, 0x06, 0x00, 0x00, 0x00, 0x00,
    };
}
