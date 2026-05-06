package com.memoos.maple

import android.content.Context
import com.memoos.device.DevicePaths
import com.memoos.device.RootShell

class MapleInferenceOrchestrator(private val context: Context) {
    fun predict(scenario: MapleScenario, modelPath: String = DevicePaths.DEFAULT_MODEL): MaplePrediction {
        val resolvedModel = resolveModelPath(modelPath)
            ?: return MaplePrediction.unavailable("model file not found or not readable: $modelPath")

        return MapleShellBackend(context).predict(resolvedModel, scenario)
    }

    private fun resolveModelPath(requestedPath: String): String? {
        val check = RootShell.run(
            "test -r '$requestedPath'",
            requireRoot = true,
            timeoutMs = 3_000L,
        )
        return if (check.ok) requestedPath else null
    }
}
