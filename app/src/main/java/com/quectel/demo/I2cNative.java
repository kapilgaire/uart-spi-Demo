package com.quectel.demo;

/**
 * JNI bridge to the native I2C (Linux i2c-dev) driver.
 * Bus target: /dev/i2c-<N> (configurable). Default chip: 0x68.
 *
 * Every call carries the target address in the I2C message itself, so one open
 * bus fd can talk to any sensor on that bus.
 *
 * Error convention:
 *   - register helpers return 0 on success, or a negative errno;
 *   - byte-array helpers return null on failure;
 *   - i2cWrite() returns the byte count, or a negative errno;
 *   - i2cReadReg8() / i2cReadReg16() return the value, or a negative errno.
 */
public final class I2cNative {

    static {
        System.loadLibrary("i2c");
    }

    private I2cNative() {}

    /** First / last 7-bit address worth probing on a bus. */
    public static final int ADDR_FIRST = 0x03;
    public static final int ADDR_LAST  = 0x77;

    // ---- bus / address ----

    /**
     * Opens a bus. Pass addr = -1 to open without preselecting a chip.
     * Returns fd >= 0, or a negative errno.
     */
    public static native int i2cOpen(String path, int addr);

    public static native void i2cClose(int fd);

    /** Changes the chip the register helpers talk to. Returns 0 or negative errno. */
    public static native int i2cSetAddress(int fd, int addr);

    /** Per-transaction timeout in milliseconds. Returns 0 or negative errno. */
    public static native int i2cSetTimeout(int fd, int timeoutMs);

    // ---- plain transfers (no register pointer) ----

    /** Writes bytes to the current chip. Returns bytes written, or negative errno. */
    public static native int i2cWrite(int fd, byte[] data);

    /** Reads len bytes from the current chip, no register pointer. Null on error. */
    public static native byte[] i2cRead(int fd, int len);

    // ---- register transfers ----

    /** reg := data, one write transaction. Returns 0 or negative errno. */
    public static native int i2cWriteReg(int fd, int reg, byte[] data);

    /** Reads len bytes starting at reg (repeated START). Null on error. */
    public static native byte[] i2cReadReg(int fd, int reg, int len);

    /** Reads one register. Returns 0..255, or a negative errno. */
    public static native int i2cReadReg8(int fd, int reg);

    /** Writes one register with an 8-bit value. Returns 0 or negative errno. */
    public static native int i2cWriteReg8(int fd, int reg, int value);

    /** Reads one 16-bit register, low byte first. Returns 0..65535, or negative errno. */
    public static native int i2cReadReg16(int fd, int reg);

    /** Writes one 16-bit register, low byte first. Returns 0 or negative errno. */
    public static native int i2cWriteReg16(int fd, int reg, int value);

    /**
     * Generic combined transaction: writes tx, then reads rxLen bytes with a
     * repeated START (single STOP). Null on error.
     */
    public static native byte[] i2cTransfer(int fd, byte[] tx, int rxLen);

    /** Probes every address in [first, last] and returns the ACKing ones. */
    public static native int[] i2cScan(int fd, int first, int last);

    // ---- helpers ----

    /** Maps a negative errno from any of the calls above to a readable string. */
    public static String errnoName(int rc) {
        switch (rc) {
            case 0:    return "OK";
            case -2:   return "-2 (ENOENT - no such device node)";
            case -5:   return "-5 (EIO - no ACK / bus error)";
            case -6:   return "-6 (ENXIO - no chip at that address)";
            case -13:  return "-13 (EACCES - permission denied, need root/SELinux)";
            case -16:  return "-16 (EBUSY - address claimed by a kernel driver)";
            case -19:  return "-19 (ENODEV)";
            case -22:  return "-22 (EINVAL - invalid argument)";
            case -110: return "-110 (ETIMEDOUT - chip did not respond)";
            case -121: return "-121 (EREMOTEIO - NACK on data)";
            default:   return rc + " (errno " + (-rc) + ")";
        }
    }
}
