package com.memoos.system.bridge;

import dalvik.annotation.optimization.CriticalNative;

public class NativeScoreBridge {

    static{
        NativeLibrary.load();
    }

    public static void normalize(float[] score) {
        if (score == null || score.length == 0) {
            return;
        }
        if (NativeLibrary.NATIVE_LIBRARY_AVAILABLE) {
            normalizeNative(score);
            return;
        }
        float sum = 0f;
        for (float value : score) {
            if (value > 0f) {
                sum += value;
            }
        }
        if (sum <= 0f) {
            float uniform = 1f / score.length;
            for (int i = 0; i < score.length; i++) {
                score[i] = uniform;
            }
            return;
        }
        for (int i = 0; i < score.length; i++) {
            score[i] = Math.max(score[i], 0f) / sum;
        }
    }

    public static float mergeThresholds(float[] thresholds) {
        if (thresholds == null || thresholds.length == 0) {
            return 0f;
        }
        if (NativeLibrary.NATIVE_LIBRARY_AVAILABLE) {
            return mergeThresholdsNative(thresholds);
        }
        float merged = thresholds[0];
        for (int i = 1; i < thresholds.length; i++) {
            merged = Math.min(merged, thresholds[i]);
        }
        return merged;
    }

    @CriticalNative
    private static native void normalizeNative(float[] score);

    @CriticalNative
    private static native float mergeThresholdsNative(float[] thresholds);
}
