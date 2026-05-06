package com.memoos.maple

import com.memoos.device.DevicePaths
import java.io.File

object MapleNative {
    private var attempted = false
    private var loaded = false
    private var loadError: String? = null
    private var cachedModelPath: String? = null
    private var cachedHandle: Long = 0L

    fun isLoaded(): Boolean {
        ensureLoaded()
        return loaded
    }

    fun error(): String? {
        ensureLoaded()
        return loadError
    }

    @Synchronized
    fun predict(modelPath: String, contextJson: String): MaplePrediction {
        ensureLoaded()
        if (!loaded) return MaplePrediction.unavailable(loadError ?: "libmaple-jni.so is not loaded")
        if (!File(modelPath).exists()) return MaplePrediction.unavailable("MAPLE model missing at $modelPath")
        return try {
            val handle = getOrCreateEngine(modelPath)
            if (handle == 0L) return MaplePrediction.unavailable("maple_engine_create returned null")
            val stage1 = predictAppType(handle, contextJson)
            val stage2 = predictNextApp(handle, contextJson, stage1)
            MaplePrediction.fromJson(stage1, stage2, "jni")
        } catch (exc: Throwable) {
            MaplePrediction.unavailable("MAPLE JNI call failed: ${exc.message ?: exc.javaClass.simpleName}")
        }
    }

    private fun getOrCreateEngine(modelPath: String): Long {
        if (cachedHandle != 0L && cachedModelPath == modelPath) return cachedHandle
        if (cachedHandle != 0L) {
            destroyEngine(cachedHandle)
            cachedHandle = 0L
            cachedModelPath = null
        }
        cachedHandle = createEngine(modelPath, 768, 2, 0.0f, 16)
        if (cachedHandle != 0L) cachedModelPath = modelPath
        return cachedHandle
    }

    private fun ensureLoaded() {
        if (attempted) return
        attempted = true
        try {
            System.loadLibrary("maple-jni")
            loaded = true
            return
        } catch (libExc: Throwable) {
            val deployed = File(DevicePaths.MAPLE_JNI_SO)
            if (deployed.exists()) {
                try {
                    System.load(deployed.absolutePath)
                    loaded = true
                    return
                } catch (fileExc: Throwable) {
                    loadError = fileExc.message ?: fileExc.javaClass.simpleName
                    return
                }
            }
            loadError = libExc.message ?: libExc.javaClass.simpleName
        }
    }

    private external fun createEngine(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        temperature: Float,
        maxTokens: Int,
    ): Long

    private external fun destroyEngine(handle: Long)
    private external fun predictAppType(handle: Long, contextJson: String): String
    private external fun predictNextApp(handle: Long, contextJson: String, stage1Json: String): String
}
