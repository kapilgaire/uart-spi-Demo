//
// I2C native driver for the 40-pin demo (Linux i2c-dev: /dev/i2c-<bus>).
// JNI class: com.quectel.demo.I2cNative
//
// Everything is issued through ioctl(I2C_RDWR) with an explicit per-message
// slave address, so no ioctl(I2C_SLAVE) is required: one open bus fd can talk
// to any chip on the bus (this is exactly what i2ctransfer(8) does).
//
// Register access uses the combined "write register pointer, repeated START,
// read N bytes" transaction - the same thing i2c-dev does internally when it
// emulates the SMBus read/write byte-data helpers. Doing it by hand keeps this
// file independent of the optional <linux/i2c-dev.h> SMBus structs.
//
#include <jni.h>
#include <string>
#include <cstring>
#include <cerrno>
#include <map>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <stdint.h>

#include "logutil.h"

// ---- i2c-dev kernel interface ---------------------------------------------
// <linux/i2c.h> / <linux/i2c-dev.h> ship with most NDK sysroots; include them
// when available and fall back to local copies otherwise, so this file builds
// on either kind of toolchain.
#if defined(__has_include)
#  if __has_include(<linux/i2c.h>)
#    include <linux/i2c.h>
#    define QL_HAVE_LINUX_I2C_H 1
#  endif
#  if __has_include(<linux/i2c-dev.h>)
#    include <linux/i2c-dev.h>
#  endif
#endif

#ifndef QL_HAVE_LINUX_I2C_H
struct i2c_msg {
    uint16_t addr;   // 7-bit slave address (R/W bit is added by the kernel)
    uint16_t flags;  // I2C_M_RD = read, I2C_M_NOSTART = no START before this msg
    uint16_t len;
    uint8_t* buf;
};
struct i2c_rdwr_ioctl_data {
    struct i2c_msg* msgs;
    uint32_t nmsgs;
};
#endif

#ifndef I2C_M_RD
#define I2C_M_RD        0x0001
#endif
#ifndef I2C_M_NOSTART
#define I2C_M_NOSTART   0x4000
#endif
#ifndef I2C_RDWR
#define I2C_RDWR        0x0707   /* combined R/W transfer (one STOP only) */
#endif
#ifndef I2C_RETRIES
#define I2C_RETRIES     0x0701
#endif
#ifndef I2C_TIMEOUT
#define I2C_TIMEOUT     0x0702   /* unit: 10 ms */
#endif

namespace {

    const int kMaxXfer   = 4096;  // below the kernel's 8192-byte per-message cap
    const int kAddrFirst = 0x03;  // 0x00..0x02 and 0x78..0x7F are reserved
    const int kAddrLast  = 0x77;

// 7-bit address currently selected per open bus fd (i2cOpen / i2cSetAddress).
    std::map<int, uint16_t> gAddr;

    uint16_t addrOf(int fd) {
        std::map<int, uint16_t>::iterator it = gAddr.find(fd);
        return (it == gAddr.end()) ? 0x00 : it->second;
    }

// One ioctl(I2C_RDWR) round trip. Returns 0 on success, -errno otherwise.
    int xfer(int fd, struct i2c_msg* msgs, uint32_t n) {
        struct i2c_rdwr_ioctl_data data;
        data.msgs  = msgs;
        data.nmsgs = n;
        if (ioctl(fd, I2C_RDWR, &data) < 0) return -errno;
        return 0;
    }

    void fillMsg(struct i2c_msg* m, uint16_t addr, uint16_t flags,
                 const uint8_t* buf, uint16_t len) {
        m->addr  = addr;
        m->flags = flags;
        m->len   = len;
        m->buf   = const_cast<uint8_t*>(buf);
    }

// "register pointer, repeated START, read len bytes" in a single transaction.
    int readRegRaw(int fd, int reg, uint8_t* rx, int len) {
        uint8_t regByte = (uint8_t)reg;
        uint16_t a = addrOf(fd);
        struct i2c_msg msgs[2];
        fillMsg(&msgs[0], a, 0,        &regByte, 1);
        fillMsg(&msgs[1], a, I2C_M_RD, rx,       (uint16_t)len);
        return xfer(fd, msgs, 2);
    }

// "register pointer, then write len bytes" in a single transaction.
    int writeRegRaw(int fd, int reg, const uint8_t* tx, int len) {
        std::vector<uint8_t> buf;
        buf.reserve((size_t)len + 1);
        buf.push_back((uint8_t)reg);
        buf.insert(buf.end(), tx, tx + len);

        struct i2c_msg msg;
        fillMsg(&msg, addrOf(fd), 0, buf.data(), (uint16_t)buf.size());
        return xfer(fd, &msg, 1);
    }

} // namespace

extern "C" {

// ================= bus / address =================

JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cOpen(
        JNIEnv* env, jclass, jstring path, jint addr) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string dev(p ? p : "");
    env->ReleaseStringUTFChars(path, p);
    if (dev.empty()) return -EINVAL;

    int fd = open(dev.c_str(), O_RDWR);
    if (fd < 0) {
        ALOGE("i2cOpen: open(%s) failed: %s", dev.c_str(), strerror(errno));
        return -errno;
    }

    // 1 s per transaction (I2C_TIMEOUT counts 10 ms units). Keep retries low
    // so a missing chip fails fast instead of stalling the UI.
    ioctl(fd, I2C_TIMEOUT, 100);
    ioctl(fd, I2C_RETRIES, 1);

    if (addr >= 0) {
        if (addr < kAddrFirst || addr > kAddrLast) {
            ALOGE("i2cOpen: bad slave address 0x%02X", addr);
            close(fd);
            return -EINVAL;
        }
        gAddr[fd] = (uint16_t)addr;
    }

    ALOGI("i2cOpen: fd=%d dev=%s slave=0x%02X", fd, dev.c_str(), addr);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_quectel_demo_I2cNative_i2cClose(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) {
        gAddr.erase(fd);
        close(fd);
        ALOGI("i2cClose: fd=%d", fd);
    }
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cSetAddress(JNIEnv*, jclass, jint fd, jint addr) {
    if (fd < 0) return -EBADF;
    if (addr < kAddrFirst || addr > kAddrLast) {
        ALOGE("i2cSetAddress: bad slave address 0x%02X", addr);
        return -EINVAL;
    }
    gAddr[fd] = (uint16_t)addr;
    ALOGI("i2cSetAddress: fd=%d slave=0x%02X", fd, addr);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cSetTimeout(JNIEnv*, jclass, jint fd, jint timeoutMs) {
    if (fd < 0) return -EBADF;
    if (timeoutMs <= 0) return -EINVAL;
    int units = (timeoutMs + 9) / 10;   // kernel granularity is 10 ms
    if (ioctl(fd, I2C_TIMEOUT, units) < 0) return -errno;
    return 0;
}

// ================= plain transfers (no register pointer) =================

JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cWrite(
        JNIEnv* env, jclass, jint fd, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;
    if (len > kMaxXfer) return -EINVAL;

    jbyte* tx = env->GetByteArrayElements(data, nullptr);
    struct i2c_msg msg;
    fillMsg(&msg, addrOf(fd), 0, (const uint8_t*)tx, (uint16_t)len);
    int rc = xfer(fd, &msg, 1);
    env->ReleaseByteArrayElements(data, tx, JNI_ABORT);

    if (rc != 0) {
        ALOGE("i2cWrite(%d bytes) failed: %s", (int)len, strerror(-rc));
        return rc;
    }
    return (jint)len;   // bytes written
}

JNIEXPORT jbyteArray JNICALL
Java_com_quectel_demo_I2cNative_i2cRead(JNIEnv* env, jclass, jint fd, jint len) {
    if (len <= 0) return nullptr;
    if (len > kMaxXfer) len = kMaxXfer;

    uint8_t rx[kMaxXfer];
    struct i2c_msg msg;
    fillMsg(&msg, addrOf(fd), I2C_M_RD, rx, (uint16_t)len);
    int rc = xfer(fd, &msg, 1);
    if (rc != 0) {
        ALOGE("i2cRead(%d bytes) failed: %s", len, strerror(-rc));
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, (const jbyte*)rx);
    return out;
}

// ================= register transfers =================

JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cWriteReg(
        JNIEnv* env, jclass, jint fd, jint reg, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;
    if (len > kMaxXfer) return -EINVAL;

    jbyte* tx = env->GetByteArrayElements(data, nullptr);
    int rc = writeRegRaw(fd, reg, (const uint8_t*)tx, (int)len);
    env->ReleaseByteArrayElements(data, tx, JNI_ABORT);

    if (rc != 0) ALOGE("i2cWriteReg(0x%02X) failed: %s", reg, strerror(-rc));
    return rc;
}

JNIEXPORT jbyteArray JNICALL
Java_com_quectel_demo_I2cNative_i2cReadReg(
        JNIEnv* env, jclass, jint fd, jint reg, jint len) {
    if (len <= 0) return nullptr;
    if (len > kMaxXfer) len = kMaxXfer;

    uint8_t rx[kMaxXfer];
    int rc = readRegRaw(fd, reg, rx, len);
    if (rc != 0) {
        ALOGE("i2cReadReg(0x%02X,%d) failed: %s", reg, len, strerror(-rc));
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, (const jbyte*)rx);
    return out;
}

// Returns 0..255, or a negative errno.
JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cReadReg8(JNIEnv*, jclass, jint fd, jint reg) {
    uint8_t b = 0;
    int rc = readRegRaw(fd, reg, &b, 1);
    if (rc != 0) {
        ALOGE("i2cReadReg8(0x%02X) failed: %s", reg, strerror(-rc));
        return rc;
    }
    return b;
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cWriteReg8(JNIEnv*, jclass, jint fd, jint reg, jint value) {
    uint8_t b = (uint8_t)(value & 0xFF);
    int rc = writeRegRaw(fd, reg, &b, 1);
    if (rc != 0) ALOGE("i2cWriteReg8(0x%02X) failed: %s", reg, strerror(-rc));
    return rc;
}

// SMBus word order: low byte first. Returns 0..65535, or a negative errno.
JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cReadReg16(JNIEnv*, jclass, jint fd, jint reg) {
    uint8_t b[2] = {0, 0};
    int rc = readRegRaw(fd, reg, b, 2);
    if (rc != 0) {
        ALOGE("i2cReadReg16(0x%02X) failed: %s", reg, strerror(-rc));
        return rc;
    }
    return (jint)(b[0] | (b[1] << 8));
}

JNIEXPORT jint JNICALL
Java_com_quectel_demo_I2cNative_i2cWriteReg16(JNIEnv*, jclass, jint fd, jint reg, jint value) {
    uint8_t b[2];
    b[0] = (uint8_t)(value & 0xFF);          // low byte first
    b[1] = (uint8_t)((value >> 8) & 0xFF);
    int rc = writeRegRaw(fd, reg, b, 2);
    if (rc != 0) ALOGE("i2cWriteReg16(0x%02X) failed: %s", reg, strerror(-rc));
    return rc;
}

// Write tx, repeated START, read rxLen bytes - one transaction, one STOP.
JNIEXPORT jbyteArray JNICALL
Java_com_quectel_demo_I2cNative_i2cTransfer(
        JNIEnv* env, jclass, jint fd, jbyteArray txArray, jint rxLen) {
    jsize txLen = env->GetArrayLength(txArray);
    if (txLen <= 0 || txLen > kMaxXfer) return nullptr;
    if (rxLen <= 0 || rxLen > kMaxXfer) return nullptr;

    jbyte* tx = env->GetByteArrayElements(txArray, nullptr);
    uint8_t rx[kMaxXfer];

    uint16_t a = addrOf(fd);
    struct i2c_msg msgs[2];
    fillMsg(&msgs[0], a, 0,        (const uint8_t*)tx, (uint16_t)txLen);
    fillMsg(&msgs[1], a, I2C_M_RD, rx,                 (uint16_t)rxLen);

    int rc = xfer(fd, msgs, 2);
    env->ReleaseByteArrayElements(txArray, tx, JNI_ABORT);
    if (rc != 0) {
        ALOGE("i2cTransfer(%d->%d) failed: %s", (int)txLen, rxLen, strerror(-rc));
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(rxLen);
    env->SetByteArrayRegion(out, 0, rxLen, (const jbyte*)rx);
    return out;
}

// ================= bus scan =================

// Probes [first, last] with a 1-byte read and returns every ACKing address.
// Reads only (never writes), so it is safe to run against unknown chips; a
// device that NACKs a read without a register pointer will not show up.
JNIEXPORT jintArray JNICALL
Java_com_quectel_demo_I2cNative_i2cScan(
        JNIEnv* env, jclass, jint fd, jint first, jint last) {
    if (fd < 0) return nullptr;
    if (first < kAddrFirst) first = kAddrFirst;
    if (last  > kAddrLast)  last  = kAddrLast;
    if (first > last) return env->NewIntArray(0);

    std::vector<jint> found;
    for (int a = first; a <= last; a++) {
        uint8_t dummy = 0;
        struct i2c_msg msg;
        fillMsg(&msg, (uint16_t)a, I2C_M_RD, &dummy, 1);
        if (xfer(fd, &msg, 1) == 0) {
            found.push_back(a);
            ALOGI("i2cScan: found 0x%02X", a);
        }
    }

    jintArray out = env->NewIntArray((jsize)found.size());
    if (out != nullptr && !found.empty()) {
        env->SetIntArrayRegion(out, 0, (jsize)found.size(), found.data());
    }
    return out;
}

} // extern "C"
