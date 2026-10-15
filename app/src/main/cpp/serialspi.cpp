//
// Serial (UART) + SPI + OLED native driver for the 40-pin demo.
// JNI class: com.quectel.demo.SerialSpiNative
//
#include <jni.h>
#include <string>
#include <cstring>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <stdint.h>
#include <time.h>
#include <cstdarg>
#include <linux/spi/spidev.h>

#include "logutil.h"

namespace {

// ---- Serial helpers -------------------------------------------------------

    speed_t baudToSpeed(int baud) {
        switch (baud) {
            case 9600:    return B9600;
            case 19200:   return B19200;
            case 38400:   return B38400;
            case 57600:   return B57600;
            case 115200:  return B115200;
            case 230400:  return B230400;
            case 460800:  return B460800;
            case 500000:  return B500000;
            case 576000:  return B576000;
            case 921600:  return B921600;
            case 1000000: return B1000000;
            case 1152000: return B1152000;
            case 1500000: return B1500000;
            case 2000000: return B2000000;
            case 2500000: return B2500000;
            case 3000000: return B3000000;
            case 3500000: return B3500000;
            case 4000000: return B4000000;
            default:      return B115200;
        }
    }

// ---- SPI config -----------------------------------------------------------

    uint8_t  gSpiBits  = 8;
    uint32_t gSpiSpeed = 1000000;

    int spiTransferRaw(int fd, const uint8_t* tx, uint8_t* rx, uint32_t len) {
        if (len == 0) return 0;
        struct spi_ioc_transfer tr;
        memset(&tr, 0, sizeof(tr));
        tr.tx_buf        = (__u64)tx;
        tr.rx_buf        = (__u64)rx;
        tr.len           = len;
        tr.speed_hz      = gSpiSpeed;
        tr.bits_per_word = gSpiBits;
        return ioctl(fd, SPI_IOC_MESSAGE(1), &tr);
    }

// ---- OLED GPIO (sysfs with chardev fallback) ------------------------------

#include <linux/gpio.h>

// Cache for chardev GPIO handles
    static int gpioChipFd = -1;
    static int gpioHandles[1024]; // line number -> handle fd
    static bool gpioUseChardev = false;

    static void gpioInitChardev() {
        if (gpioChipFd >= 0) return;
        // Try common gpiochip paths
        const char* chips[] = {"/dev/gpiochip0", "/dev/gpiochip1", "/dev/gpiochip2", "/dev/gpiochip3"};
        for (auto path : chips) {
            int fd = open(path, 0);
            if (fd >= 0) {
                gpioChipFd = fd;
                ALOGI("oledGpio: using chardev %s (fd=%d)", path, fd);
                gpioUseChardev = true;
                memset(gpioHandles, 0xFF, sizeof(gpioHandles));
                return;
            }
        }
        ALOGI("oledGpio: no chardev found, using sysfs");
        gpioUseChardev = false;
    }

    static int gpioRequestLine(int gpio) {
        if (!gpioUseChardev) return 0;
        if (gpioHandles[gpio] != -1) return 0; // already requested

        struct gpiohandle_request req;
        memset(&req, 0, sizeof(req));
        req.lineoffsets[0] = gpio;
        req.flags = GPIOHANDLE_REQUEST_OUTPUT;
        req.lines = 1;

        if (ioctl(gpioChipFd, GPIO_GET_LINEHANDLE_IOCTL, &req) < 0) {
            ALOGE("oledGpio: GPIO_GET_LINEHANDLE_IOCTL(%d) failed: %s", gpio, strerror(errno));
            return -1;
        }
        gpioHandles[gpio] = req.fd;
        return 0;
    }

    int oledGpioExport(int gpio) {
        gpioInitChardev();
        if (gpioUseChardev) {
            return gpioRequestLine(gpio);
        }
        // Fallback: sysfs
        int fd = open("/sys/class/gpio/export", O_WRONLY);
        if (fd < 0) return -1;
        char buf[10];
        snprintf(buf, sizeof(buf), "%d", gpio);
        write(fd, buf, strlen(buf));
        close(fd);
        usleep(100000);
        return 0;
    }

    int oledGpioSetDir(int gpio) {
        if (gpioUseChardev) return 0; // direction set at request time
        char path[50];
        snprintf(path, sizeof(path), "/sys/class/gpio/gpio%d/direction", gpio);
        int fd = open(path, O_WRONLY);
        if (fd < 0) return -1;
        write(fd, "out", 3);
        close(fd);
        return 0;
    }

    int oledGpioWrite(int gpio, int value) {
        if (gpioUseChardev) {
            if (gpioHandles[gpio] < 0) return -1;
            struct gpiohandle_data data;
            memset(&data, 0, sizeof(data));
            data.values[0] = value ? 1 : 0;
            if (ioctl(gpioHandles[gpio], GPIOHANDLE_SET_LINE_VALUES_IOCTL, &data) < 0) {
                ALOGE("oledGpio: SET_LINE_VALUES(%d=%d) failed: %s", gpio, value, strerror(errno));
                return -1;
            }
            return 0;
        }
        // Fallback: sysfs
        char path[50];
        snprintf(path, sizeof(path), "/sys/class/gpio/gpio%d/value", gpio);
        int fd = open(path, O_WRONLY);
        if (fd < 0) return -1;
        char buf[2] = {value ? '1' : '0', 0};
        write(fd, buf, 1);
        close(fd);
        return 0;
    }

// ---- OLED constants -------------------------------------------------------

#define OLED_SPI_MODE    SPI_MODE_0
#define OLED_SPI_SPEED   4000000
#define OLED_WIDTH       128
#define OLED_HEIGHT      32
#define OLED_PAGES       4

// Complete 5x7 font
    static const uint8_t font_5x7[][5] = {
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
            {0x08, 0x08, 0x08, 0x08, 0x08}, // -
            {0x00, 0x36, 0x36, 0x00, 0x00}, // :
            {0x00, 0x60, 0x60, 0x00, 0x00}, // .
    };

    static int charToIndex(char c) {
        if (c == ' ') return 0;
        if (c >= '0' && c <= '9') return 1 + (c - '0');
        if (c >= 'A' && c <= 'Z') return 11 + (c - 'A');
        if (c >= 'a' && c <= 'z') return 11 + (c - 'a');
        if (c == '-') return 37;
        if (c == ':') return 38;
        if (c == '.') return 39;
        return 0;
    }

// ---- OLED drawing state ---------------------------------------------------

    static uint8_t oledBuffer[OLED_PAGES][OLED_WIDTH];

    static void oledSetPixel(int x, int y, int color) {
        if (x >= 0 && x < OLED_WIDTH && y >= 0 && y < OLED_HEIGHT) {
            int page = y / 8;
            int bit = y % 8;
            if (color)
                oledBuffer[page][x] |= (1 << bit);
            else
                oledBuffer[page][x] &= ~(1 << bit);
        }
    }

    static void oledDrawChar(int x, int y, char c) {
        int idx = charToIndex(c);
        for (int col = 0; col < 5; col++) {
            uint8_t data = font_5x7[idx][col];
            for (int row = 0; row < 8; row++) {
                if (data & (1 << row)) {
                    oledSetPixel(x + col, y + row, 1);
                }
            }
        }
    }

    static void oledDrawText(int x, int y, const char* text) {
        int cursor = x;
        while (*text) {
            oledDrawChar(cursor, y, *text);
            cursor += 6;
            text++;
        }
    }

    static void oledClearBuffer() {
        memset(oledBuffer, 0, sizeof(oledBuffer));
    }

    static void oledSendCmd(int fd, int dcGpio, uint8_t cmd) {
        oledGpioWrite(dcGpio, 0);
        write(fd, &cmd, 1);
        usleep(1000);
    }

    static void oledDisplayBuffer(int fd, int dcGpio) {
        for (int page = 0; page < OLED_PAGES; page++) {
            oledSendCmd(fd, dcGpio, 0xB0 + page);
            oledSendCmd(fd, dcGpio, 0x04);
            oledSendCmd(fd, dcGpio, 0x10);
            oledGpioWrite(dcGpio, 1);
            write(fd, oledBuffer[page], OLED_WIDTH);
        }
    }

    static int oledInitDisplay(int fd, int rstGpio, int dcGpio) {
        // Reset
        oledGpioWrite(rstGpio, 0);
        usleep(50000);
        oledGpioWrite(rstGpio, 1);
        usleep(50000);

        // SSD1306 init sequence
        oledSendCmd(fd, dcGpio, 0xAE); // Display OFF
        oledSendCmd(fd, dcGpio, 0x04);
        oledSendCmd(fd, dcGpio, 0x10);
        oledSendCmd(fd, dcGpio, 0x40);
        oledSendCmd(fd, dcGpio, 0x81); oledSendCmd(fd, dcGpio, 0xFF); // Contrast
        oledSendCmd(fd, dcGpio, 0xA1); // Segment remap
        oledSendCmd(fd, dcGpio, 0xA6); // Normal display
        oledSendCmd(fd, dcGpio, 0xA8); oledSendCmd(fd, dcGpio, 0x1F); // Multiplex (32 rows)
        oledSendCmd(fd, dcGpio, 0xC8); // COM scan direction
        oledSendCmd(fd, dcGpio, 0xD3); oledSendCmd(fd, dcGpio, 0x00); // Display offset
        oledSendCmd(fd, dcGpio, 0xD5); oledSendCmd(fd, dcGpio, 0xF0); // Clock div
        oledSendCmd(fd, dcGpio, 0xD8); oledSendCmd(fd, dcGpio, 0x05); // Area color
        oledSendCmd(fd, dcGpio, 0xD9); oledSendCmd(fd, dcGpio, 0xC2); // Precharge
        oledSendCmd(fd, dcGpio, 0xDA); oledSendCmd(fd, dcGpio, 0x12); // COM pins
        oledSendCmd(fd, dcGpio, 0xDB); oledSendCmd(fd, dcGpio, 0x08); // VCOM deselect
        oledSendCmd(fd, dcGpio, 0xAF); // Display ON
        return 0;
    }

} // namespace

extern "C" {

// ================= Serial =================

JNIEXPORT jint JNICALL
Java_com_quectel_demo_SerialSpiNative_serialOpen(
        JNIEnv* env, jclass, jstring path, jint baud) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string devPath(p ? p : "");
    env->ReleaseStringUTFChars(path, p);
    int fd = open(devPath.c_str(), O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) {
        ALOGE("serialOpen: open(%s) failed: %s", devPath.c_str(), strerror(errno));
        return -1;
    }

    struct termios tty;
    memset(&tty, 0, sizeof(tty));
    if (tcgetattr(fd, &tty) != 0) {
        ALOGE("serialOpen: tcgetattr failed: %s", strerror(errno));
        close(fd);
        return -2;
    }

    cfmakeraw(&tty);
    tty.c_cflag |= (CLOCAL | CREAD);
    tty.c_cflag &= ~CSIZE;
    tty.c_cflag |= CS8;
    tty.c_cflag &= ~PARENB;
    tty.c_cflag &= ~CSTOPB;
    tty.c_cflag &= ~CRTSCTS;

    speed_t spd = baudToSpeed(baud);
    cfsetispeed(&tty, spd);
    cfsetospeed(&tty, spd);

    tty.c_cc[VMIN]  = 0;
    tty.c_cc[VTIME] = 1;

    if (tcsetattr(fd, TCSANOW, &tty) != 0) {
        ALOGE("serialOpen: tcsetattr failed: %s", strerror(errno));
        close(fd);
        return -3;
    }

    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);

    tcflush(fd, TCIOFLUSH);
    ALOGI("serialOpen: fd=%d baud=%d 8N1", fd, baud);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_SerialSpiNative_serialWrite(
        JNIEnv* env, jclass, jint fd, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    ssize_t n = write(fd, buf, (size_t)len);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    if (n < 0) ALOGE("serialWrite: write failed: %s", strerror(errno));
    return (jint)n;
}

JNIEXPORT jbyteArray JNICALL
Java_com_quectel_demo_SerialSpiNative_serialRead(
        JNIEnv* env, jclass, jint fd, jint timeoutMs) {
    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(fd, &rfds);
    struct timeval tv;
    tv.tv_sec  = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;

    int ret = select(fd + 1, &rfds, nullptr, nullptr, &tv);
    if (ret <= 0) return nullptr;

    uint8_t buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf));
    if (n <= 0) return nullptr;

    jbyteArray out = env->NewByteArray((jsize)n);
    env->SetByteArrayRegion(out, 0, (jsize)n, (const jbyte*)buf);
    return out;
}

JNIEXPORT void JNICALL
Java_com_quectel_demo_SerialSpiNative_serialClose(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) {
        tcflush(fd, TCIOFLUSH);
        close(fd);
        ALOGI("serialClose: fd=%d", fd);
    }
}

// ================= SPI =================

JNIEXPORT jint JNICALL
Java_com_quectel_demo_SerialSpiNative_spiOpen(
        JNIEnv* env, jclass, jstring path, jint mode, jint bits, jint speedHz) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDWR);
    env->ReleaseStringUTFChars(path, p);
    if (fd < 0) {
        ALOGE("spiOpen: open failed: %s", strerror(errno));
        return -1;
    }

    uint8_t  m = (uint8_t)(mode & 0x03);
    uint8_t  b = (uint8_t)bits;
    uint32_t s = (uint32_t)speedHz;

    if (ioctl(fd, SPI_IOC_WR_MODE, &m) < 0) {
        ALOGE("spiOpen: WR_MODE failed: %s", strerror(errno));
        close(fd); return -2;
    }
    if (ioctl(fd, SPI_IOC_WR_BITS_PER_WORD, &b) < 0) {
        ALOGE("spiOpen: WR_BITS_PER_WORD failed: %s", strerror(errno));
        close(fd); return -3;
    }
    if (ioctl(fd, SPI_IOC_WR_MAX_SPEED_HZ, &s) < 0) {
        ALOGE("spiOpen: WR_MAX_SPEED_HZ failed: %s", strerror(errno));
        close(fd); return -4;
    }

    gSpiBits  = b;
    gSpiSpeed = s;
    ALOGI("spiOpen: fd=%d mode=%d bits=%d speed=%u", fd, m, b, s);
    return fd;
}

JNIEXPORT jbyteArray JNICALL
Java_com_quectel_demo_SerialSpiNative_spiTransfer(
        JNIEnv* env, jclass, jint fd, jbyteArray txArray) {
    jsize len = env->GetArrayLength(txArray);
    if (len <= 0) return nullptr;

    jbyte* tx = env->GetByteArrayElements(txArray, nullptr);
    uint8_t rx[4096];
    int ret = spiTransferRaw(fd, (const uint8_t*)tx, rx, (uint32_t)len);
    env->ReleaseByteArrayElements(txArray, tx, JNI_ABORT);
    if (ret < 0) {
        ALOGE("spiTransfer: ioctl failed: %s", strerror(errno));
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, (const jbyte*)rx);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_quectel_demo_SerialSpiNative_spiRead(
        JNIEnv* env, jclass, jint fd, jint nBytes) {
    if (nBytes <= 0) return nullptr;

    uint8_t tx[4096];
    uint8_t rx[4096];
    memset(tx, 0, sizeof(tx));

    uint32_t n = (uint32_t)nBytes;
    int ret = spiTransferRaw(fd, tx, rx, n);
    if (ret < 0) {
        ALOGE("spiRead: ioctl failed: %s", strerror(errno));
        return nullptr;
    }

    jbyteArray out = env->NewByteArray((jsize)n);
    env->SetByteArrayRegion(out, 0, (jsize)n, (const jbyte*)rx);
    return out;
}

JNIEXPORT void JNICALL
Java_com_quectel_demo_SerialSpiNative_spiClose(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) {
        close(fd);
        ALOGI("spiClose: fd=%d", fd);
    }
}

// ================= OLED =================

JNIEXPORT jstring JNICALL
Java_com_quectel_demo_SerialSpiNative_oledRunTests(
        JNIEnv* env, jclass, jstring spiDevPath, jint rstGpio, jint dcGpio) {

    const char* devPath = env->GetStringUTFChars(spiDevPath, nullptr);
    std::string dev(devPath ? devPath : "");
    env->ReleaseStringUTFChars(spiDevPath, devPath);

    std::string log;

    auto append = [&](const char* fmt, ...) {
        char buf[512];
        va_list args;
        va_start(args, fmt);
        vsnprintf(buf, sizeof(buf), fmt, args);
        va_end(args);
        log += buf;
        log += "\n";
    };

    // Detect CE0 vs CE1 from path
    const char* ceName = strstr(dev.c_str(), "0.1") ? "CE1 (Pin26)" : "CE0 (Pin24)";
    const char* otherDev = strstr(dev.c_str(), "0.1") ? "/dev/spidev0.0" : "/dev/spidev0.1";

    append("========================================");
    append("  OLED Demo Test - %s", ceName);
    append("========================================");

    // ---- Setup GPIO ----
    append("\n[Setup] Exporting GPIO pins...");
    append("  RST: gpio%d, DC: gpio%d", rstGpio, dcGpio);

    oledGpioExport(rstGpio);
    oledGpioExport(dcGpio);
    usleep(200000);
    oledGpioSetDir(rstGpio);
    oledGpioSetDir(dcGpio);
    append("  GPIO configured OK");

    // ---- Open SPI ----
    append("\n[Setup] Opening %s...", dev.c_str());
    int spiFd = open(dev.c_str(), O_RDWR);
    if (spiFd < 0) {
        append("  ERROR: Cannot open device: %s", strerror(errno));
        append("\nTEST FAILED - device not accessible");
        return env->NewStringUTF(log.c_str());
    }
    append("  Device opened OK");

    // ---- Configure SPI ----
    uint8_t spiMode = OLED_SPI_MODE;
    uint32_t spiSpeed = OLED_SPI_SPEED;
    ioctl(spiFd, SPI_IOC_WR_MODE, &spiMode);
    ioctl(spiFd, SPI_IOC_WR_MAX_SPEED_HZ, &spiSpeed);
    gSpiBits = 8;
    gSpiSpeed = spiSpeed;
    append("  SPI configured: mode 0, speed %u Hz", spiSpeed);

    // ---- Initialize OLED ----
    append("\n[Setup] Initializing OLED...");
    oledInitDisplay(spiFd, rstGpio, dcGpio);
    append("  OLED initialized OK");

    // ---- Test 1: Display CE info ----
    append("\n[Test 1] Display chip select info...");
    oledClearBuffer();
    oledDrawText(10, 4, "USING:");
    if (strstr(ceName, "CE0"))
        oledDrawText(25, 16, "CE0");
    else
        oledDrawText(25, 16, "CE1");
    oledDisplayBuffer(spiFd, dcGpio);
    append("  Screen showing: USING %s", strstr(ceName, "CE0") ? "CE0" : "CE1");
    append("  (Pause 3s...)");
    sleep(3);

    // ---- Test 2: Fill white ----
    append("\n[Test 2] Filling screen WHITE...");
    memset(oledBuffer, 0xFF, sizeof(oledBuffer));
    oledDisplayBuffer(spiFd, dcGpio);
    append("  Screen should be WHITE");
    append("  (Pause 2s...)");
    sleep(2);

    // ---- Test 3: Blink test ----
    append("\n[Test 3] Blink test (5 times)...");
    for (int i = 0; i < 5; i++) {
        memset(oledBuffer, 0xFF, sizeof(oledBuffer));
        oledDisplayBuffer(spiFd, dcGpio);
        usleep(200000);
        memset(oledBuffer, 0x00, sizeof(oledBuffer));
        oledDisplayBuffer(spiFd, dcGpio);
        usleep(200000);
        append("  Blink %d/5", i + 1);
    }
    append("  Blink test complete");

    // ---- Test 4: FPS test ----
    append("\n[Test 4] FPS Performance Test...");
    append("  Running 100 frame updates...");

    struct timespec startTs, endTs;
    clock_gettime(CLOCK_MONOTONIC, &startTs);

    for (int frame = 0; frame < 100; frame++) {
        for (int page = 0; page < OLED_PAGES; page++) {
            for (int x = 0; x < OLED_WIDTH; x++) {
                oledBuffer[page][x] = (x + frame) & 0xFF;
            }
        }
        oledDisplayBuffer(spiFd, dcGpio);
    }

    clock_gettime(CLOCK_MONOTONIC, &endTs);
    double elapsed = (endTs.tv_sec - startTs.tv_sec) +
                     (endTs.tv_nsec - startTs.tv_nsec) / 1000000000.0;
    double fps = 100.0 / elapsed;

    append("  Performance: %.1f FPS", fps);

    // Show FPS on screen
    oledClearBuffer();
    oledDrawText(10, 4, "FPS TEST");
    char fpsStr[20];
    snprintf(fpsStr, sizeof(fpsStr), "%.1f FPS", fps);
    oledDrawText(20, 16, fpsStr);
    oledDisplayBuffer(spiFd, dcGpio);
    append("  FPS result displayed on screen");
    append("  (Pause 3s...)");
    sleep(3);

    // ---- Test 5: CS pin test ----
    append("\n[Test 5] CS Pin Functionality Test...");
    append("  Step 1: Displaying pattern 1...");
    oledClearBuffer();
    oledDrawText(15, 4, "PATTERN");
    oledDrawText(50, 16, "1");
    oledDisplayBuffer(spiFd, dcGpio);
    append("  Pattern 1 displayed");
    append("  (Pause 2s...)");
    sleep(2);

    append("  Step 2: Trying to update via OTHER CS (%s)...",
           strstr(ceName, "CE0") ? "CE1" : "CE0");

    int otherFd = open(otherDev, O_RDWR);
    if (otherFd >= 0) {
        uint8_t m = OLED_SPI_MODE;
        uint32_t s = OLED_SPI_SPEED;
        ioctl(otherFd, SPI_IOC_WR_MODE, &m);
        ioctl(otherFd, SPI_IOC_WR_MAX_SPEED_HZ, &s);

        oledClearBuffer();
        oledDrawText(15, 4, "PATTERN");
        oledDrawText(50, 16, "2");

        for (int page = 0; page < OLED_PAGES; page++) {
            oledSendCmd(otherFd, dcGpio, 0xB0 + page);
            oledSendCmd(otherFd, dcGpio, 0x04);
            oledSendCmd(otherFd, dcGpio, 0x10);
            oledGpioWrite(dcGpio, 1);
            write(otherFd, oledBuffer[page], OLED_WIDTH);
        }

        close(otherFd);
        append("  Sent update via other CS");
        append("  (Pause 2s...)");
        sleep(2);

        append("\n  CHECK SCREEN:");
        append("  If still showing '1': CS pin WORKING correctly");
        append("  If changed to '2': CS pin NOT working");
        append("  (Pause 2s...)");
        sleep(2);
    } else {
        append("  Other device not accessible - skipping CS test");
    }

    append("  Step 3: Restoring pattern 1 via correct CS...");
    oledClearBuffer();
    oledDrawText(15, 4, "BACK TO");
    oledDrawText(50, 16, "1");
    oledDisplayBuffer(spiFd, dcGpio);
    append("  Pattern 1 restored");
    append("  (Pause 2s...)");
    sleep(2);

    // ---- Test 6: Final summary ----
    append("\n[Test 6] Final Summary...");
    oledClearBuffer();
    oledDrawText(30, 4, ceName);
    char sumStr[20];
    snprintf(sumStr, sizeof(sumStr), "%.1fFPS", fps);
    oledDrawText(20, 16, sumStr);
    oledDisplayBuffer(spiFd, dcGpio);
    append("  Summary displayed on screen");
    append("  (Pause 3s...)");
    sleep(3);

    // Clear screen
    oledClearBuffer();
    oledDisplayBuffer(spiFd, dcGpio);

    close(spiFd);

    append("\n========================================");
    append("  Test Complete!");
    append("========================================");
    append("  Performance: %.1f FPS", fps);
    append("========================================");

    ALOGI("oledRunTests: complete, log size=%zu", log.size());
    return env->NewStringUTF(log.c_str());
}

// ================= OLED Show Text =================

JNIEXPORT jstring JNICALL
Java_com_quectel_demo_SerialSpiNative_oledShowText(
        JNIEnv* env, jclass, jstring spiDevPath, jint rstGpio, jint dcGpio,
        jstring textStr) {

    const char* devPath = env->GetStringUTFChars(spiDevPath, nullptr);
    std::string dev(devPath ? devPath : "");
    env->ReleaseStringUTFChars(spiDevPath, devPath);

    const char* textPtr = env->GetStringUTFChars(textStr, nullptr);
    std::string text(textPtr ? textPtr : "");
    env->ReleaseStringUTFChars(textStr, textPtr);

    std::string log;
    auto append = [&](const char* fmt, ...) {
        char buf[256];
        va_list args;
        va_start(args, fmt);
        vsnprintf(buf, sizeof(buf), fmt, args);
        va_end(args);
        log += buf;
        log += "\n";
    };

    // Setup GPIO
    oledGpioExport(rstGpio);
    oledGpioExport(dcGpio);
    usleep(200000);
    oledGpioSetDir(rstGpio);
    oledGpioSetDir(dcGpio);

    // Open SPI
    int spiFd = open(dev.c_str(), O_RDWR);
    if (spiFd < 0) {
        append("ERROR: Cannot open %s", dev.c_str());
        return env->NewStringUTF(log.c_str());
    }

    uint8_t spiMode = OLED_SPI_MODE;
    uint32_t spiSpeed = OLED_SPI_SPEED;
    ioctl(spiFd, SPI_IOC_WR_MODE, &spiMode);
    ioctl(spiFd, SPI_IOC_WR_MAX_SPEED_HZ, &spiSpeed);
    gSpiBits = 8;
    gSpiSpeed = spiSpeed;

    // Init OLED
    oledInitDisplay(spiFd, rstGpio, dcGpio);
    append("OLED initialized");

    // Clear and draw text (multi-line, up to 4 lines of 21 chars)
    oledClearBuffer();

    // Split text into lines by newline
    int line = 0;
    int yPos = 2; // start 2px from top for centering in 8px row
    size_t pos = 0;
    const int CHARS_PER_LINE = 21;
    const int LINE_HEIGHT = 8;

    while (pos < text.size() && line < OLED_PAGES) {
        // Find end of this line
        size_t end = text.find('\n', pos);
        if (end == std::string::npos) end = text.size();

        std::string lineText = text.substr(pos, end - pos);

        // Truncate if too long
        if ((int)lineText.size() > CHARS_PER_LINE)
            lineText = lineText.substr(0, CHARS_PER_LINE);

        // Center horizontally
        int xStart = (OLED_WIDTH - (int)lineText.size() * 6) / 2;
        if (xStart < 0) xStart = 0;

        oledDrawText(xStart, yPos, lineText.c_str());

        yPos += LINE_HEIGHT;
        line++;
        pos = end + 1;
    }

    // If single line and no newline, center vertically
    if (line == 1 && text.find('\n') == std::string::npos) {
        oledClearBuffer();
        int totalH = LINE_HEIGHT;
        int yCenter = (OLED_HEIGHT - totalH) / 2;
        int xStart = (OLED_WIDTH - (int)text.substr(0, CHARS_PER_LINE).size() * 6) / 2;
        if (xStart < 0) xStart = 0;
        oledDrawText(xStart, yCenter, text.substr(0, CHARS_PER_LINE).c_str());
    }

    oledDisplayBuffer(spiFd, dcGpio);
    append("Text displayed: \"%s\"", text.substr(0, 60).c_str());

    close(spiFd);
    return env->NewStringUTF(log.c_str());
}

} // extern "C"
