package com.memoos.system.bridge

object NativeScoreBridge {
    private val isLoaded: Boolean

    init {
        isLoaded = runCatching {
            System.loadLibrary("memo-native")
            true
        }.getOrDefault(false)
    }

    fun normalize(scores: FloatArray): FloatArray {
        if (!isLoaded || scores.isEmpty()) return scores
        return runCatching { normalizeScoresNative(scores) }.getOrDefault(scores)
    }

    fun mergeThresholds(thresholds: FloatArray): Float {
        if (!isLoaded || thresholds.isEmpty()) return thresholds.average().toFloat()
        return runCatching { mergeThresholdsNative(thresholds) }.getOrDefault(thresholds.average().toFloat())
    }

    private external fun normalizeScoresNative(scores: FloatArray): FloatArray
    private external fun mergeThresholdsNative(thresholds: FloatArray): Float
}
