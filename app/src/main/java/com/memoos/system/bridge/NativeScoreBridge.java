package com.memoos.system.bridge;
import dalvik.annotation.optimization.CriticalNative;
public class NativeScoreBridge {
    static{
        System.loadLibrary("memo-native");
    }
    @CriticalNative
    public static native void normalize(float[] score);
    @CriticalNative
    public static native float mergeThresholds(float[] thresholds);
}
