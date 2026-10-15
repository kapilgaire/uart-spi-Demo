//
// GPIO native driver for the 40-pin demo (Linux sysfs interface).
// JNI class: com.quectel.demo.GpioNative
//
#include <jni.h>
#include <string>
#include <cstring>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <sys/stat.h>

#include "logutil.h"

namespace {

    const char *kGpioBase = "/sys/class/gpio";
    const size_t kPathMax = 128;

    int buildPinPath(char *out, size_t outLen, int pin, const char *leaf) {
        int n = snprintf(out, outLen, "%s/gpio%d/%s", kGpioBase, pin, leaf);
        if (n < 0 || static_cast<size_t>(n) >= outLen) return -EINVAL;
        return 0;
    }

    int buildCtlPath(char *out, size_t outLen, const char *leaf) {
        int n = snprintf(out, outLen, "%s/%s", kGpioBase, leaf);
        if (n < 0 || static_cast<size_t>(n) >= outLen) return -EINVAL;
        return 0;
    }

// Returns 0 on success, a negative errno on failure.
    int writeSysfs(const char *path, const char *value) {
        const size_t len = strlen(value);
        int fd = open(path, O_WRONLY);
        if (fd < 0) return -errno;

        ssize_t n = write(fd, value, len);
        int err = (n == static_cast<ssize_t>(len)) ? 0 : -(errno ? errno : EIO);
        close(fd);
        return err;
    }

    int readSysfs(const char *path, char *buf, size_t bufLen) {
        if (bufLen < 2) return -EINVAL;
        int fd = open(path, O_RDONLY);
        if (fd < 0) return -errno;
        ssize_t n = read(fd, buf, bufLen - 1);
        close(fd);
        if (n < 0) return -errno;
        buf[n] = '\0';
        while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r' || buf[n - 1] == ' ')) {
            buf[--n] = '\0';
        }
        return 0;
    }

    bool pathExists(const char *path) {
        struct stat st;
        return stat(path, &st) == 0;
    }

// The kernel creates gpioN/ asynchronously after "export" -> poll for it.
    int waitForAttr(int pin, const char *leaf, int timeoutMs) {
        char path[kPathMax];
        if (buildPinPath(path, sizeof(path), pin, leaf) != 0) return -EINVAL;
        for (int waited = 0; waited < timeoutMs; waited += 10) {
            if (pathExists(path)) return 0;
            usleep(10 * 1000);
        }
        return -ENOENT;
    }

    int writePinAttr(int pin, const char *leaf, const char *value) {
        char path[kPathMax];
        int rc = buildPinPath(path, sizeof(path), pin, leaf);
        if (rc != 0) {
            ALOGE("gpio%d/%s: path too long", pin, leaf);
            return rc;
        }
        return writeSysfs(path, value);
    }

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioExport(JNIEnv *, jclass, jint pin) {
    if (pin < 0) return -EINVAL;

    char ctl[kPathMax];
    if (buildCtlPath(ctl, sizeof(ctl), "export") != 0) return -EINVAL;

    char num[16];
    snprintf(num, sizeof(num), "%d", pin);

    int rc = writeSysfs(ctl, num);
    // EBUSY == already exported by someone else; perfectly usable for us.
    if (rc != 0 && rc != -EBUSY) {
        ALOGE("gpioExport(%d): write(export) failed: %s", pin, strerror(-rc));
        return rc;
    }

    rc = waitForAttr(pin, "direction", 1000);
    if (rc != 0) {
        ALOGE("gpioExport(%d): gpio%d/direction never appeared: %s",
              pin, pin, strerror(-rc));
        return rc;
    }

    ALOGI("gpioExport(%d) ok", pin);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioUnexport(JNIEnv *, jclass, jint pin) {
    if (pin < 0) return -EINVAL;

    char ctl[kPathMax];
    if (buildCtlPath(ctl, sizeof(ctl), "unexport") != 0) return -EINVAL;

    char num[16];
    snprintf(num, sizeof(num), "%d", pin);

    int rc = writeSysfs(ctl, num);
    if (rc != 0) {
        ALOGE("gpioUnexport(%d): write(unexport) failed: %s", pin, strerror(-rc));
        return rc;
    }
    ALOGI("gpioUnexport(%d) ok", pin);
    return 0;
}

// direction: "in" | "out" | "low" | "high"
// low/high = outputs that start at a defined level (no glitch during init).
JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioSetDirection(
        JNIEnv *env, jclass, jint pin, jstring direction) {
    const char *d = env->GetStringUTFChars(direction, nullptr);
    std::string dir(d ? d : "");
    env->ReleaseStringUTFChars(direction, d);

    if (dir != "in" && dir != "out" && dir != "low" && dir != "high") {
        ALOGE("gpioSetDirection(%d): invalid direction '%s'", pin, dir.c_str());
        return -EINVAL;
    }

    int rc = writePinAttr(pin, "direction", dir.c_str());
    if (rc != 0) {
        ALOGE("gpioSetDirection(%d,%s) failed: %s", pin, dir.c_str(), strerror(-rc));
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioSetValue(JNIEnv *, jclass, jint pin, jint value) {
    int rc = writePinAttr(pin, "value", value ? "1" : "0");
    if (rc != 0) {
        ALOGE("gpioSetValue(%d,%d) failed: %s", pin, value, strerror(-rc));
    }
    return rc;
}

// Returns the level (0/1), or a negative errno.
JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioGetValue(JNIEnv *, jclass, jint pin) {
    char path[kPathMax];
    int rc = buildPinPath(path, sizeof(path), pin, "value");
    if (rc != 0) return rc;

    char buf[8];
    rc = readSysfs(path, buf, sizeof(buf));
    if (rc != 0) {
        ALOGE("gpioGetValue(%d) failed: %s", pin, strerror(-rc));
        return rc;
    }

    if (buf[0] == '0') return 0;
    if (buf[0] == '1') return 1;
    return -EIO;
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioSetActiveLow(
        JNIEnv *, jclass, jint pin, jboolean activeLow) {
    return writePinAttr(pin, "active_low", activeLow ? "1" : "0");
}

// edge: "none" | "rising" | "falling" | "both"
JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioSetEdge(
        JNIEnv *env, jclass, jint pin, jstring edge) {
    const char *e = env->GetStringUTFChars(edge, nullptr);
    std::string edgeStr(e ? e : "");
    env->ReleaseStringUTFChars(edge, e);

    if (edgeStr != "none" && edgeStr != "rising" &&
        edgeStr != "falling" && edgeStr != "both") {
        ALOGE("gpioSetEdge(%d): invalid edge '%s'", pin, edgeStr.c_str());
        return -EINVAL;
    }

    int rc = writePinAttr(pin, "edge", edgeStr.c_str());
    if (rc != 0) {
        ALOGE("gpioSetEdge(%d,%s) failed: %s", pin, edgeStr.c_str(), strerror(-rc));
    }
    return rc;
}

// Blocks until the configured edge fires or timeoutMs expires.
//   >= 0       : level after the edge (0/1)
//   -ETIMEDOUT : nothing happened in time
JNIEXPORT jint JNICALL
Java_com_quectel_demo_GpioNative_gpioWaitForEdge(
        JNIEnv *, jclass, jint pin, jint timeoutMs) {
    char path[kPathMax];
    int rc = buildPinPath(path, sizeof(path), pin, "value");
    if (rc != 0) return rc;

    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        ALOGE("gpioWaitForEdge(%d): open failed: %s", pin, strerror(errno));
        return -errno;
    }

    // Drain the current state: sysfs reports the present value as an initial
    // POLLPRI event, which would otherwise fire immediately.
    char buf[8];
    lseek(fd, 0, SEEK_SET);
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    (void) n;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLPRI | POLLERR;

    int ret = poll(&pfd, 1, timeoutMs < 0 ? -1 : timeoutMs);

    if (ret == 0) {
        close(fd);
        return -ETIMEDOUT;
    }
    if (ret < 0) {
        int err = errno;
        ALOGE("gpioWaitForEdge(%d): poll failed: %s", pin, strerror(err));
        close(fd);
        return -err;
    }

    lseek(fd, 0, SEEK_SET);
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -EIO;

    buf[n] = '\0';
    return (buf[0] == '1') ? 1 : 0;
}

} // extern "C"
