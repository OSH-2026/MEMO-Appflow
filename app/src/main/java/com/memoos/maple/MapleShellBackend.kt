package com.memoos.maple

import android.content.Context
import com.memoos.device.DevicePaths
import com.memoos.device.RootShell
import org.json.JSONObject
import java.io.File

class MapleShellBackend(private val context: Context) {
    fun predict(modelPath: String, scenario: MapleScenario): MaplePrediction {
        val binaryCheck = RootShell.run("test -x '${DevicePaths.MAPLE_DEMO}'", requireRoot = true, timeoutMs = 3_000L)
        if (!binaryCheck.ok) return MaplePrediction.unavailable("Android MAPLE executable missing at ${DevicePaths.MAPLE_DEMO}")
        val modelCheck = RootShell.run("test -r '$modelPath'", requireRoot = true, timeoutMs = 3_000L)
        if (!modelCheck.ok) return MaplePrediction.unavailable("MAPLE model missing at $modelPath")
        val scenarioFile = File(context.getExternalFilesDir(null), "latest_maple_scenario.json")
        scenarioFile.parentFile?.mkdirs()
        scenarioFile.writeText(scenario.scenarioJson)
        val publicScenario = "${DevicePaths.SCENARIO_DIR}/latest_maple_scenario.json"
        RootShell.run("mkdir -p ${DevicePaths.SCENARIO_DIR}; cp '${scenarioFile.absolutePath}' '$publicScenario'; chmod 644 '$publicScenario'", timeoutMs = 5_000L)
        val result = RootShell.run(
            "cd ${DevicePaths.MEMO_ROOT} && nice -n 10 env LD_LIBRARY_PATH=${DevicePaths.MEMO_ROOT} ${DevicePaths.MAPLE_DEMO} --model '$modelPath' --scenarios '$publicScenario'",
            requireRoot = true,
            timeoutMs = MAPLE_DEVICE_WATCHDOG_MS,
        )
        if (result.timedOut) {
            return MaplePrediction.unavailable("MAPLE process did not finish before the device watchdog (${MAPLE_DEVICE_WATCHDOG_MS / 1000}s)")
        }
        if (!result.ok) {
            return MaplePrediction.unavailable("MAPLE shell backend failed: ${result.stderr.ifBlank { result.stdout }.take(300)}")
        }
        return parseDemoOutput(result.stdout)
    }

    private fun parseDemoOutput(output: String): MaplePrediction {
        val structured = parseStructuredOutput(output)
        val appId = Regex("""->\s*Predicted specific app:\s*App\s+(\d+)""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""This user will use App\s+(\d+)""")
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: -1
        val categories = Regex("""->\s*Predicted category:\s*"([^"]+)"(?:\s*\(confidence:\s*(\d+(?:\.\d+)?)%\))?""")
            .findAll(output)
            .map {
                Stage1Category(
                    it.groupValues[1].trim(),
                    (it.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0) / 100.0,
                )
            }
            .filter { it.name.isNotBlank() }
            .take(5)
            .toList()
        return MaplePrediction(
            stage1 = categories,
            predictedAppId = appId,
            reasoning = output.lineSequence().lastOrNull { it.contains("App ") }.orEmpty(),
            rawStage1 = output,
            rawStage2 = output,
            backend = "shell",
            available = appId > 0 || categories.isNotEmpty(),
            error = if (appId > 0 || categories.isNotEmpty()) null else "Could not parse MAPLE demo output",
            schedulerBits = structured?.let { MaplePrediction.parseSchedulerBits(it) }.orEmpty(),
            schedulerPlan = structured?.let { MaplePrediction.parseSchedulerPlan(it) }.orEmpty(),
            pressureAnalysis = structured?.let { MaplePrediction.parsePressureAnalysis(it) }.orEmpty(),
        )
    }

    private fun parseStructuredOutput(output: String): JSONObject? {
        parseJsonAfterMarker(output, "MAPLE_STRUCTURED_OUTPUT:")?.let { return it }
        val markerIndex = listOf("scheduler_bits", "scheduler_bitmask", "scheduler_plan", "pressure_analysis")
            .map { output.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        val start = output.lastIndexOf('{', markerIndex).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (idx in start until output.length) {
            val ch = output[idx]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') inString = !inString
            if (inString) continue
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) {
                    return try {
                        JSONObject(output.substring(start, idx + 1))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    private fun parseJsonAfterMarker(output: String, marker: String): JSONObject? {
        val markerIndex = output.lastIndexOf(marker).takeIf { it >= 0 } ?: return null
        val start = output.indexOf('{', markerIndex).takeIf { it >= 0 } ?: return null
        return parseJsonObjectAt(output, start)
    }

    private fun parseJsonObjectAt(output: String, start: Int): JSONObject? {
        var depth = 0
        var inString = false
        var escaped = false
        for (idx in start until output.length) {
            val ch = output[idx]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') inString = !inString
            if (inString) continue
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) {
                    return try {
                        JSONObject(output.substring(start, idx + 1))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    private companion object {
        const val MAPLE_DEVICE_WATCHDOG_MS = 3_600_000L
    }
}
