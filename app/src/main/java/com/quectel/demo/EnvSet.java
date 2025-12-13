package com.quectel.demo;

public class EnvSet {

    static {
        System.loadLibrary("envset");
    }

    public static native void initEnv(String nativeDir);
}
