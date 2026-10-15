package com.quectel.demo;

public final class GpioNative {
    static {
        System.loadLibrary("gpio");
    }
    private GpioNative() {}
    public static final String DIR_IN = "in", DIR_OUT = "out",
            DIR_LOW = "low", DIR_HIGH = "high";
    public static final String EDGE_NONE = "none", EDGE_RISING = "rising",
            EDGE_FALLING = "falling", EDGE_BOTH = "both";
    /** gpioWaitForEdge() returned -ETIMEDOUT. */
    public static final int ERR_TIMEOUT = -110;
    public static boolean isOk(int rc) { return rc == 0; }
    public static native int gpioExport(int pin);
    public static native int gpioUnexport(int pin);
    public static native int gpioSetDirection(int pin, String direction);
    public static native int gpioSetValue(int pin, int value);
    public static native int gpioGetValue(int pin);
    public static native int gpioSetActiveLow(int pin, boolean activeLow);
    public static native int gpioSetEdge(int pin, String edge);
    public static native int gpioWaitForEdge(int pin, int timeoutMs);
}
