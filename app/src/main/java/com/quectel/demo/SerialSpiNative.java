package com.quectel.demo;

/**
 * JNI bridge to the native serial (UART), SPI, and OLED drivers.
 */
public final class SerialSpiNative {

    static {
        System.loadLibrary("io");
    }

    private SerialSpiNative() {}

    // ---- Serial (UART) ----

    public static native int serialOpen(String path, int baud);
    public static native int serialWrite(int fd, byte[] data);
    public static native byte[] serialRead(int fd, int timeoutMs);
    public static native void serialClose(int fd);

    // ---- SPI ----

    public static native int spiOpen(String path, int mode, int bits, int speedHz);
    public static native byte[] spiTransfer(int fd, byte[] tx);
    public static native byte[] spiRead(int fd, int nBytes);
    public static native void spiClose(int fd);

    // ---- OLED (Waveshare SPI display) ----

    /**
     * Run the full OLED demo test sequence.
     *
     * @param spiDev   SPI device path (e.g. "/dev/spidev0.0" for CE0, "/dev/spidev0.1" for CE1)
     * @param rstGpio  GPIO number for the RST pin (e.g. 417 = GPIO_BASE 385 + 32)
     * @param dcGpio   GPIO number for the DC pin  (e.g. 465 = GPIO_BASE 385 + 80)
     * @return         Multi-line log of test results (newline-separated)
     */
    public static native String oledRunTests(String spiDev, int rstGpio, int dcGpio);

    /**
     * Show text on the OLED display.
     * Supports up to 4 lines of 21 characters each, separated by newlines.
     *
     * @return status log
     */
    public static native String oledShowText(String spiDev, int rstGpio, int dcGpio, String text);
}
