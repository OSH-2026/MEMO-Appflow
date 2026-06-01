package com.memoos.store

import android.content.Context
import com.memoos.action.ActionResult
import com.memoos.action.RecommendedApp
import com.memoos.maple.MaplePrediction
import com.memoos.maple.MapleScenario
import com.memoos.perf.PipelineLatency
import org.json.JSONArray
import org.json.JSONObject

data class RecommendationState(
    val packageName: String,
    val label: String,
    val category: String,
    val confidence: Double,
    val reason: String,
)

data class ActionState(
    val name: String,
    val target: String,
    val status: String,
    val detail: String,
    val durationMs: Long,
    val timestampMs: Long,
)

data class MapleState(
    val available: Boolean,
    val backend: String,
    val predictedAppId: Int,
    val stage1: List<String>,
    val error: String?,
)

data class LatencyStageState(
    val name: String,
    val durationMs: Long,
)

data class LatencyState(
    val mode: String,
    val totalMs: Long,
    val foregroundMs: Long,
    val parsedEvents: Int,
    val realtimeStatus: String,
    val stages: List<LatencyStageState>,
) {
    val isPresent: Boolean get() = totalMs > 0L || stages.isNotEmpty()
}

data class LastMemoState(
    val updatedAt: Long,
    val recommendationsUpdatedAt: Long,
    val recommendations: List<RecommendationState>,
    val evidenceLines: List<String>,
    val rawTracePath: String,
    val freeUsageSession: FreeUsageSessionState,
    val maple: MapleState,
    val actions: List<ActionState>,
    val scenarioJson: String,
    val rawMapleJson: String,
    val rawActionsJson: String,
    val latency: LatencyState,
    val rawLatencyJson: String,
    val usageReportJson: String,
    val ablationReportJson: String,
    val pressureReportJson: String,
)

data class FreeUsageSessionState(
    val active: Boolean,
    val startedAtMs: Long,
    val rawTracePath: String,
    val appSamplesPath: String,
    val flagPath: String,
    val beforeStateJson: String,
)

class MemoStore(context: Context) {
    private val prefs = context.getSharedPreferences("memo_pipeline", Context.MODE_PRIVATE)

    fun clearSyntheticDemoState() {
        val scenario = prefs.getString("scenario_json", "").orEmpty()
        if ("\"id\": \"demo_" in scenario || "Device-side reproducible demo case" in scenario) {
            prefs.edit().clear().apply()
        }
    }

    fun savePending(scenario: MapleScenario, message: String, latency: PipelineLatency? = null) {
        val mapleObj = JSONObject()
            .put("available", false)
            .put("backend", "pending")
            .put("predicted_app_id", -1)
            .put("stage1", JSONArray())
            .put("error", message)
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putLong("recommendations_updated_at", 0L)
            .putString("recommendations", "[]")
            .putString("recommendation_packages", "[]")
            .putString("recommendation_labels", "[]")
            .putString("evidence", scenario.evidenceLines.joinToString("\n"))
            .putString("scenario_json", scenario.scenarioJson)
            .putString("maple", mapleObj.toString(2))
            .putString("actions", "[]")
            .putString("latency", latency?.toJson()?.toString(2) ?: "{}")
            .commit()
    }

    fun saveFailure(message: String, latency: PipelineLatency? = null, scenarioJson: String = "") {
        val mapleObj = JSONObject()
            .put("available", false)
            .put("backend", "blocked")
            .put("predicted_app_id", -1)
            .put("stage1", JSONArray())
            .put("error", message)
        val actionArr = JSONArray().put(
            JSONObject()
                .put("name", "pipeline_failure")
                .put("target", "strict_runtime")
                .put("status", "blocked")
                .put("detail", message)
                .put("timestamp_ms", System.currentTimeMillis()),
        )
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putLong("recommendations_updated_at", 0L)
            .putString("recommendations", "[]")
            .putString("recommendation_packages", "[]")
            .putString("recommendation_labels", "[]")
            .putString("evidence", message)
            .putString("scenario_json", scenarioJson)
            .putString("maple", mapleObj.toString(2))
            .putString("actions", actionArr.toString(2))
            .putString("latency", latency?.toJson()?.toString(2) ?: "{}")
            .commit()
    }

    fun save(
        scenario: MapleScenario,
        prediction: MaplePrediction,
        recommendations: List<RecommendedApp>,
        actions: List<ActionResult>,
        latency: PipelineLatency? = null,
    ) {
        val now = System.currentTimeMillis()
        val recArr = JSONArray(recommendations.map { app ->
            JSONObject()
                .put("package_name", app.packageName)
                .put("label", app.label)
                .put("category", app.category)
                .put("confidence", app.confidence)
                .put("reason", app.reason)
        })
        val actionArr = JSONArray(actions.map {
            JSONObject()
                .put("name", it.name)
                .put("target", it.target)
                .put("status", it.status)
                .put("detail", it.detail)
                .put("duration_ms", it.durationMs)
                .put("timestamp_ms", it.timestampMs)
        })
        val mapleObj = JSONObject()
            .put("available", prediction.available)
            .put("backend", prediction.backend)
            .put("predicted_app_id", prediction.predictedAppId)
            .put("stage1", JSONArray(prediction.stage1.map { "${it.name} (${(it.probability * 100).toInt()}%)" }))
            .put("error", prediction.error ?: JSONObject.NULL)
        prefs.edit()
            .putLong("updated_at", now)
            .putLong("recommendations_updated_at", now)
            .putString("recommendations", recArr.toString())
            .putString("recommendation_packages", JSONArray(recommendations.map { it.packageName }).toString())
            .putString("recommendation_labels", JSONArray(recommendations.map { it.label }).toString())
            .putString("evidence", scenario.evidenceLines.joinToString("\n"))
            .putString("scenario_json", scenario.scenarioJson)
            .putString("maple", mapleObj.toString(2))
            .putString("actions", actionArr.toString(2))
            .putString("latency", latency?.toJson()?.toString(2) ?: "{}")
            .apply()
    }

    fun appendAction(action: ActionResult) {
        val arr = try {
            JSONArray(prefs.getString("actions", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        arr.put(
            JSONObject()
                .put("name", action.name)
                .put("target", action.target)
                .put("status", action.status)
                .put("detail", action.detail)
                .put("duration_ms", action.durationMs)
                .put("timestamp_ms", action.timestampMs),
        )
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putString("actions", arr.toString(2))
            .apply()
    }

    fun saveRawTracePath(path: String) {
        prefs.edit()
            .putString("raw_trace_path", path)
            .apply()
    }

    fun saveFreeUsageSession(
        startedAtMs: Long,
        rawTracePath: String,
        appSamplesPath: String,
        flagPath: String,
        beforeStateJson: String,
    ) {
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putBoolean("free_usage_active", true)
            .putLong("free_usage_started_at", startedAtMs)
            .putString("free_usage_trace_path", rawTracePath)
            .putString("free_usage_app_samples_path", appSamplesPath)
            .putString("free_usage_flag_path", flagPath)
            .putString("free_usage_before_state", beforeStateJson)
            .putString("raw_trace_path", rawTracePath)
            .apply()
    }

    fun clearFreeUsageSession() {
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putBoolean("free_usage_active", false)
            .remove("free_usage_started_at")
            .remove("free_usage_trace_path")
            .remove("free_usage_app_samples_path")
            .remove("free_usage_flag_path")
            .remove("free_usage_before_state")
            .apply()
    }

    fun saveUsageReport(report: JSONObject) {
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putString("usage_report", report.toString(2))
            .apply()
    }

    fun saveAblationReport(report: JSONObject) {
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putString("ablation_report", report.toString(2))
            .apply()
    }

    fun savePressureReport(report: JSONObject) {
        prefs.edit()
            .putLong("updated_at", System.currentTimeMillis())
            .putString("pressure_report", report.toString(2))
            .apply()
    }

    fun load(): LastMemoState {
        val rawMaple = prefs.getString("maple", "{}") ?: "{}"
        val rawActions = prefs.getString("actions", "[]") ?: "[]"
        val rawLatency = prefs.getString("latency", "{}") ?: "{}"
        val updatedAt = prefs.getLong("updated_at", 0L)
        return LastMemoState(
            updatedAt = updatedAt,
            recommendationsUpdatedAt = prefs.getLong("recommendations_updated_at", updatedAt),
            recommendations = readRecommendations(),
            evidenceLines = prefs.getString("evidence", "No evidence collected yet.")
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList(),
            rawTracePath = prefs.getString("raw_trace_path", "") ?: "",
            freeUsageSession = FreeUsageSessionState(
                active = prefs.getBoolean("free_usage_active", false),
                startedAtMs = prefs.getLong("free_usage_started_at", 0L),
                rawTracePath = prefs.getString("free_usage_trace_path", "") ?: "",
                appSamplesPath = prefs.getString("free_usage_app_samples_path", "") ?: "",
                flagPath = prefs.getString("free_usage_flag_path", "") ?: "",
                beforeStateJson = prefs.getString("free_usage_before_state", "") ?: "",
            ),
            maple = readMaple(rawMaple),
            actions = readActions(rawActions),
            scenarioJson = prefs.getString("scenario_json", "") ?: "",
            rawMapleJson = rawMaple,
            rawActionsJson = rawActions,
            latency = readLatency(rawLatency),
            rawLatencyJson = rawLatency,
            usageReportJson = prefs.getString("usage_report", "") ?: "",
            ablationReportJson = prefs.getString("ablation_report", "") ?: "",
            pressureReportJson = prefs.getString("pressure_report", "") ?: "",
        )
    }

    private fun readRecommendations(): List<RecommendationState> {
        val raw = prefs.getString("recommendations", null)
        if (!raw.isNullOrBlank()) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { idx ->
                    val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                    RecommendationState(
                        packageName = obj.optString("package_name"),
                        label = obj.optString("label"),
                        category = obj.optString("category"),
                        confidence = obj.optDouble("confidence", 0.0),
                        reason = obj.optString("reason"),
                    ).takeIf { it.packageName.isNotBlank() && it.label.isNotBlank() }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        val packages = readStringArray("recommendation_packages")
        val labels = readStringArray("recommendation_labels")
        return packages.zip(labels).map { (pkg, label) ->
            RecommendationState(pkg, label, "Installed app", 0.0, "Stored from previous run")
        }
    }

    private fun readMaple(raw: String): MapleState {
        return try {
            val obj = JSONObject(raw)
            val stage = obj.optJSONArray("stage1")
            MapleState(
                available = obj.optBoolean("available", false),
                backend = obj.optString("backend", "unknown"),
                predictedAppId = obj.optInt("predicted_app_id", -1),
                stage1 = if (stage == null) emptyList() else (0 until stage.length()).mapNotNull { stage.optString(it).takeIf { value -> value.isNotBlank() } },
                error = obj.opt("error")?.takeIf { it != JSONObject.NULL }?.toString(),
            )
        } catch (_: Exception) {
            MapleState(false, "unknown", -1, emptyList(), raw.takeIf { it.isNotBlank() && it != "{}" })
        }
    }

    private fun readActions(raw: String): List<ActionState> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                ActionState(
                    name = obj.optString("name"),
                    target = obj.optString("target"),
                    status = obj.optString("status"),
                    detail = obj.optString("detail"),
                    durationMs = obj.optLong("duration_ms", 0L),
                    timestampMs = obj.optLong("timestamp_ms", 0L),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readLatency(raw: String): LatencyState {
        return try {
            val obj = JSONObject(raw)
            val stageArr = obj.optJSONArray("stages")
            val stages = if (stageArr == null) {
                emptyList()
            } else {
                (0 until stageArr.length()).mapNotNull { idx ->
                    val stage = stageArr.optJSONObject(idx) ?: return@mapNotNull null
                    LatencyStageState(
                        name = stage.optString("name"),
                        durationMs = stage.optLong("duration_ms", 0L),
                    ).takeIf { it.name.isNotBlank() }
                }
            }
            LatencyState(
                mode = obj.optString("mode"),
                totalMs = obj.optLong("total_ms", 0L),
                foregroundMs = obj.optLong("foreground_ms", obj.optLong("total_ms", 0L)),
                parsedEvents = obj.optInt("parsed_events", 0),
                realtimeStatus = normalizeLatencyStatus(obj.optString("realtime_status", "unknown")),
                stages = stages,
            )
        } catch (_: Exception) {
            LatencyState("", 0L, 0L, 0, "unknown", emptyList())
        }
    }

    private fun normalizeLatencyStatus(value: String): String {
        val lower = value.lowercase()
        return when {
            lower in setOf("ok", "degraded", "timed_out") -> "completed"
            "realtime" in lower -> "completed"
            else -> value.ifBlank { "unknown" }
        }
    }

    private fun readStringArray(key: String): List<String> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { value -> value.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
