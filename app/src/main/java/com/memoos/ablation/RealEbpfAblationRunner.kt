package com.memoos.ablation

import android.content.Context
import android.os.SystemClock
import com.memoos.action.ActionExecutor
import com.memoos.action.ActionResult
import com.memoos.action.AppIdMapping
import com.memoos.action.RecommendedApp
import com.memoos.device.DevicePaths
import com.memoos.device.RootShell
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MapleScenario
import com.memoos.perf.PipelineLatency
import com.memoos.state.SystemStateCollector
import com.memoos.state.SystemStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RealEbpfAblationRunner(private val context: Context) {
    fun run(latestScenarioJson: String): JSONObject {
        val baseRoot = JSONObject(latestScenarioJson)
        val baseScenario = baseRoot.getJSONArray("scenarios").getJSONObject(0)
        val baseContext = baseScenario.getJSONObject("context")
        val baseCategories = baseContext.getJSONArray("historical_app_categories").toStringList()
        val state = SystemStateCollector(context).collect()
        val configs = listOf(
            AblationConfig("full_real_ebpf", "Full real eBPF evidence"),
            AblationConfig("no_network", "Remove sendto/recvfrom and Network IO evidence", removeCategories = setOf("Network IO")),
            AblationConfig("no_camera_media", "Remove camera and media evidence", removeCategories = setOf("Camera Service", "Media Codec")),
            AblationConfig("no_display_ui", "Remove SurfaceFlinger/RenderThread/display evidence", removeCategories = setOf("Display Composition", "Input Interaction")),
            AblationConfig("no_binder_service", "Remove Binder and Android service evidence", removeCategories = setOf("Android Service IPC", "Android System Services")),
            AblationConfig("no_memory", "Remove memory pressure/reclaim evidence", removeCategories = setOf("Memory Management", "Power/Thermal Management"), keepMemory = false),
            AblationConfig("counters_only", "Keep only aggregate eBPF counters, remove target-action hint", countersOnly = true),
            AblationConfig("app_sequence_baseline", "Keep app/category sequence only, remove all eBPF/system evidence", appSequenceBaseline = true),
        )

        val resultObjects = configs.map { config -> runOne(baseRoot, baseScenario, baseCategories, state, config) }
        attachComparisons(resultObjects)
        val results = JSONArray(resultObjects)

        val report = JSONObject()
            .put("schema_version", "memo.real_ebpf_ablation.v1")
            .put("source", "android_device_side_real_ebpf")
            .put("base_scenario_id", baseScenario.optString("id"))
            .put("base_description", baseScenario.optString("description"))
            .put("configs", JSONArray(configs.map { it.id }))
            .put("metrics_scope", "MAPLE prediction, real Top-3 app mapping, non-intrusive scheduler action profile, and latency")
            .put("system_state", state.toMetricsJson())
            .put("summary", summarize(resultObjects))
            .put("results", results)

        val external = File(context.getExternalFilesDir(null), "latest_real_ablation.json")
        external.writeText(report.toString(2))
        RootShell.run(
            "mkdir -p ${DevicePaths.MEMO_PUBLIC_ROOT}/ablations; cp '${external.absolutePath}' '${DevicePaths.MEMO_PUBLIC_ROOT}/ablations/latest_real_ablation.json'; chmod 644 '${DevicePaths.MEMO_PUBLIC_ROOT}/ablations/latest_real_ablation.json'",
            timeoutMs = 5_000L,
        )
        return report
    }

    private fun runOne(
        baseRoot: JSONObject,
        baseScenario: JSONObject,
        baseCategories: List<String>,
        state: SystemStateSnapshot,
        config: AblationConfig,
    ): JSONObject {
        val variantRoot = JSONObject(baseRoot.toString())
        val variantScenario = variantRoot.getJSONArray("scenarios").getJSONObject(0)
        val variantContext = variantScenario.getJSONObject("context")
        variantScenario.put("id", "${baseScenario.optString("id")}_${config.id}")
        variantScenario.put("description", "${baseScenario.optString("description")} Ablation: ${config.description}.")
        mutateContext(variantContext, baseCategories, config)

        val scenario = variantScenario.toMapleScenario(variantRoot)
        val mapleStart = SystemClock.elapsedRealtime()
        val prediction = MapleInferenceOrchestrator(context).predict(scenario)
        val mapleDurationMs = SystemClock.elapsedRealtime() - mapleStart
        val stage1 = prediction.stage1.map { it.name }
        val mappingStart = SystemClock.elapsedRealtime()
        val recommendations = if (prediction.available) {
            AppIdMapping.resolveTopApps(
                context = context,
                predictedAppId = prediction.predictedAppId,
                stage1Categories = stage1,
                scenarioCategories = scenario.topCategories,
                foregroundPackage = null,
            )
        } else {
            emptyList()
        }
        val mappingDurationMs = SystemClock.elapsedRealtime() - mappingStart
        val actionStart = SystemClock.elapsedRealtime()
        val actions = if (prediction.available) {
            ActionExecutor(context).execute(
                scenario = scenario,
                prediction = prediction,
                recommendations = recommendations,
                state = state,
                latencyBeforeActionsMs = mapleDurationMs + mappingDurationMs,
                realtimeBudgetMs = PipelineLatency.REALTIME_BUDGET_MS,
                allowVisibleWarmLaunch = false,
                asyncPrediction = true,
                publishWidget = false,
            )
        } else {
            emptyList()
        }
        val actionDurationMs = SystemClock.elapsedRealtime() - actionStart

        return JSONObject()
            .put("config_id", config.id)
            .put("description", config.description)
            .put("enabled_categories", JSONArray(scenario.topCategories))
            .put("evidence_lines", JSONArray(scenario.evidenceLines))
            .put("maple_duration_ms", mapleDurationMs)
            .put("app_mapping_duration_ms", mappingDurationMs)
            .put("action_duration_ms", actionDurationMs)
            .put("end_to_end_ms", mapleDurationMs + mappingDurationMs + actionDurationMs)
            .put("maple_available", prediction.available)
            .put("maple_backend", prediction.backend)
            .put("predicted_app_id", prediction.predictedAppId)
            .put("stage1", JSONArray(prediction.stage1.map { "${it.name} (${(it.probability * 100).toInt()}%)" }))
            .put("top_apps", JSONArray(recommendations.map { "${it.label} (${it.packageName})" }))
            .put("top_app_packages", JSONArray(recommendations.map { it.packageName }))
            .put("top_app_categories", JSONArray(recommendations.map { it.category }))
            .put("actions", JSONArray(actions.map { it.toJson() }))
            .put("prediction_metrics", predictionMetrics(prediction.available, scenario, prediction.stage1.map { it.name }, recommendations))
            .put("predicted_scheduling_metrics", schedulingMetrics(actions, recommendations, scenario, mapleDurationMs, mappingDurationMs, actionDurationMs))
            .put("error", prediction.error ?: JSONObject.NULL)
    }

    private fun mutateContext(contextObj: JSONObject, baseCategories: List<String>, config: AblationConfig) {
        if (config.appSequenceBaseline) {
            contextObj.put("system_evidence", JSONArray())
            contextObj.put("memory_pressure", "")
            contextObj.put("scheduler_goal", "")
            return
        }

        val keptCategories = baseCategories.filterNot { it in config.removeCategories }
        contextObj.put("historical_app_categories", JSONArray(keptCategories))
        contextObj.put("historical_app_ids", JSONArray(keptCategories.map { AppIdMapping.categoryId(it) }))
        filterInstalledApps(contextObj, keptCategories)

        val evidence = contextObj.optJSONArray("system_evidence").toStringList()
            .filter { line -> keepEvidenceLine(line, config) }
        contextObj.put("system_evidence", JSONArray(evidence))
        if (!config.keepMemory) {
            contextObj.put("memory_pressure", "")
        }
        if (config.countersOnly) {
            contextObj.put(
                "points_of_interest",
                JSONArray(contextObj.optJSONArray("points_of_interest").toStringList().filterNot {
                    it.contains("workflow", ignoreCase = true) || it.contains("foreground", ignoreCase = true)
                }),
            )
        }
    }

    private fun filterInstalledApps(contextObj: JSONObject, keptCategories: List<String>) {
        val installed = contextObj.optJSONObject("installed_apps") ?: return
        val filtered = JSONObject()
        keptCategories.forEach { category ->
            if (installed.has(category)) filtered.put(category, installed.getJSONArray(category))
        }
        contextObj.put("installed_apps", filtered)
    }

    private fun keepEvidenceLine(line: String, config: AblationConfig): Boolean {
        if (config.countersOnly && !line.startsWith("event_type ") && !line.startsWith("MAPLE evidence/resource category ")) {
            return false
        }
        if (!config.keepMemory && line.contains("memory", ignoreCase = true)) return false
        if (config.removeCategories.any { line.contains(it, ignoreCase = true) }) return false
        if ("Network IO" in config.removeCategories && (line.contains("MEMO_SENDTO") || line.contains("MEMO_RECVFROM"))) return false
        if ("Android Service IPC" in config.removeCategories && line.contains("MEMO_BINDER")) return false
        return true
    }

    private fun JSONObject.toMapleScenario(root: JSONObject): MapleScenario {
        val contextObj = getJSONObject("context")
        return MapleScenario(
            scenarioId = optString("id"),
            description = optString("description"),
            contextJson = contextObj.toString(),
            scenarioJson = root.toString(2),
            evidenceLines = contextObj.optJSONArray("system_evidence").toStringList(),
            topCategories = contextObj.optJSONArray("historical_app_categories").toStringList(),
            memoryPressure = contextObj.optString("memory_pressure"),
        )
    }

    private fun attachComparisons(results: List<JSONObject>) {
        val full = results.firstOrNull { it.optString("config_id") == "full_real_ebpf" } ?: return
        val fullStage1 = full.optJSONArray("stage1").categoryNames()
        val fullApps = full.optJSONArray("top_app_packages").toStringList()
        val fullActionDomains = full.optJSONObject("predicted_scheduling_metrics")
            ?.optJSONArray("action_domains")
            .toStringList()
        results.forEach { result ->
            val stage1 = result.optJSONArray("stage1").categoryNames()
            val apps = result.optJSONArray("top_app_packages").toStringList()
            val actionDomains = result.optJSONObject("predicted_scheduling_metrics")
                ?.optJSONArray("action_domains")
                .toStringList()
            val comparison = JSONObject()
                .put("reference", "full_real_ebpf")
                .put("predicted_app_same", result.optInt("predicted_app_id", -1) == full.optInt("predicted_app_id", -2))
                .put("stage1_jaccard", jaccard(stage1, fullStage1))
                .put("top3_jaccard", jaccard(apps, fullApps))
                .put("top1_same", apps.firstOrNull() == fullApps.firstOrNull())
                .put("action_domain_jaccard", jaccard(actionDomains, fullActionDomains))
                .put("maple_latency_delta_ms", result.optLong("maple_duration_ms") - full.optLong("maple_duration_ms"))
                .put("end_to_end_delta_ms", result.optLong("end_to_end_ms") - full.optLong("end_to_end_ms"))
            result.put("comparison_to_full", comparison)
        }
    }

    private fun summarize(results: List<JSONObject>): JSONObject {
        val full = results.firstOrNull { it.optString("config_id") == "full_real_ebpf" }
        val fullId = full?.optInt("predicted_app_id", -1) ?: -1
        val fullTop1 = full?.optJSONArray("top_app_packages").toStringList().firstOrNull()
        val changedPrediction = results
            .filter { it.optString("config_id") != "full_real_ebpf" && it.optInt("predicted_app_id", -1) != fullId }
            .map { it.optString("config_id") }
        val changedTop1 = results
            .filter { it.optString("config_id") != "full_real_ebpf" && it.optJSONArray("top_app_packages").toStringList().firstOrNull() != fullTop1 }
            .map { it.optString("config_id") }
        val changedScheduling = results
            .filter {
                it.optString("config_id") != "full_real_ebpf" &&
                    it.optJSONObject("comparison_to_full")?.optDouble("action_domain_jaccard", 1.0) != 1.0
            }
            .map { it.optString("config_id") }
        val latencies = results.map { it.optLong("end_to_end_ms", 0L) }.filter { it > 0L }
        return JSONObject()
            .put("config_count", results.size)
            .put("maple_available_count", results.count { it.optBoolean("maple_available", false) })
            .put("changed_predicted_app_configs", JSONArray(changedPrediction))
            .put("changed_top1_app_configs", JSONArray(changedTop1))
            .put("changed_predicted_scheduler_domain_configs", JSONArray(changedScheduling))
            .put("avg_end_to_end_ms", if (latencies.isNotEmpty()) latencies.average() else 0.0)
            .put("max_end_to_end_ms", latencies.maxOrNull() ?: 0L)
            .put("min_end_to_end_ms", latencies.minOrNull() ?: 0L)
    }

    private fun predictionMetrics(
        mapleAvailable: Boolean,
        scenario: MapleScenario,
        stage1Categories: List<String>,
        recommendations: List<RecommendedApp>,
    ): JSONObject {
        val evidenceDomains = scenario.topCategories.mapNotNull { domainForCategory(it) }.distinct()
        val predictedDomains = (stage1Categories + recommendations.map { it.category })
            .mapNotNull { domainForCategory(it) }
            .distinct()
        return JSONObject()
            .put("maple_available", mapleAvailable)
            .put("stage1_category_count", stage1Categories.distinct().size)
            .put("top3_app_count", recommendations.size)
            .put("top3_are_real_launchable_apps", recommendations.size == 3 && recommendations.all { it.packageName.isNotBlank() })
            .put("evidence_domain_count", evidenceDomains.size)
            .put("predicted_domain_count", predictedDomains.size)
            .put("evidence_to_prediction_domain_jaccard", jaccard(evidenceDomains, predictedDomains))
            .put("predicted_domains", JSONArray(predictedDomains))
            .put("evidence_domains", JSONArray(evidenceDomains))
    }

    private fun schedulingMetrics(
        actions: List<ActionResult>,
        recommendations: List<RecommendedApp>,
        scenario: MapleScenario,
        mapleDurationMs: Long,
        mappingDurationMs: Long,
        actionDurationMs: Long,
    ): JSONObject {
        val predictedDomains = scenario.topCategories.mapNotNull { domainForCategory(it) }.distinct()
        val actionDomains = actions.mapNotNull { domainForAction(it) }.distinct()
        val statusCounts = actions.groupingBy { it.status }.eachCount()
        val intensity = actions.fold(0.0) { acc, action -> acc + actionWeight(action) }
        val maxUsefulIntensity = (predictedDomains.size.coerceAtLeast(1) * 1.5) + 1.0
        return JSONObject()
            .put("metric_type", "predicted_scheduler_profile")
            .put("action_count", actions.size)
            .put("ok_count", statusCounts["ok"] ?: 0)
            .put("planned_count", statusCounts["planned"] ?: 0)
            .put("skipped_count", statusCounts["skipped"] ?: 0)
            .put("failed_count", statusCounts["failed"] ?: 0)
            .put("blocked_count", statusCounts["blocked"] ?: 0)
            .put("predicted_resource_domains", JSONArray(predictedDomains))
            .put("action_domains", JSONArray(actionDomains))
            .put("resource_alignment_score", jaccard(predictedDomains, actionDomains))
            .put("scheduler_intensity_score", intensity)
            .put("scheduler_intensity_normalized", (intensity / maxUsefulIntensity).coerceIn(0.0, 1.0))
            .put("top3_real_app_count", recommendations.size)
            .put("would_publish_widget", actions.any { it.name == "widget_update" && it.status in setOf("ok", "planned") })
            .put("would_warm_launch_count", actions.count { it.name == "warm_launch" && it.status in setOf("ok", "requested") })
            .put("pressure_suppression_count", actions.count { it.status == "skipped" && it.detail.contains("pressure", ignoreCase = true) })
            .put("non_intrusive_suppression_count", actions.count { it.status == "skipped" && it.detail.contains("non-intrusive", ignoreCase = true) })
            .put("latency_ms", JSONObject()
                .put("maple", mapleDurationMs)
                .put("app_mapping", mappingDurationMs)
                .put("actions", actionDurationMs)
                .put("total", mapleDurationMs + mappingDurationMs + actionDurationMs))
    }

    private fun ActionResult.toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("target", target)
            .put("status", status)
            .put("detail", detail)
    }

    private fun SystemStateSnapshot.toMetricsJson(): JSONObject {
        return JSONObject()
            .put("memory_pressure", memory.pressureLevel)
            .put("mem_available_kb", memory.memAvailableKb ?: JSONObject.NULL)
            .put("mem_total_kb", memory.memTotalKb ?: JSONObject.NULL)
            .put("swap_free_kb", memory.swapFreeKb ?: JSONObject.NULL)
            .put("battery_percent", battery.levelPercent ?: JSONObject.NULL)
            .put("thermal_risk", battery.thermalRisk)
            .put("temperature_c", battery.temperatureC ?: JSONObject.NULL)
            .put("udp_in_datagrams", network.udpInDatagrams ?: JSONObject.NULL)
            .put("udp_out_datagrams", network.udpOutDatagrams ?: JSONObject.NULL)
            .put("udp_socket_count", network.udpSocketCount ?: JSONObject.NULL)
            .put("foreground_package", process.foregroundPackage ?: JSONObject.NULL)
            .put("surfaceflinger_pid", mediaDisplay.surfaceFlingerPid ?: JSONObject.NULL)
            .put("cameraserver_pid", mediaDisplay.cameraServerPid ?: JSONObject.NULL)
            .put("mediacodec_pid", mediaDisplay.mediaCodecPid ?: JSONObject.NULL)
            .put("render_thread_observed", mediaDisplay.renderThreadObserved)
    }

    private fun domainForCategory(category: String): String? {
        val lower = category.lowercase()
        return when {
            "network" in lower || "udp" in lower || "sendto" in lower || "recvfrom" in lower -> "network"
            "camera" in lower || "media" in lower || "codec" in lower -> "camera_media"
            "display" in lower || "input" in lower || "surfaceflinger" in lower || "render" in lower -> "display_ui"
            "binder" in lower || "service ipc" in lower || "system services" in lower || "communication" in lower -> "binder_service"
            "memory" in lower || "reclaim" in lower || "lmkd" in lower -> "memory"
            "power" in lower || "thermal" in lower || "battery" in lower -> "power_thermal"
            "payment" in lower || "security" in lower -> "payment_security"
            "location" in lower || "navigation" in lower -> "location"
            else -> null
        }
    }

    private fun domainForAction(action: ActionResult): String? {
        return when {
            action.name.startsWith("network") -> "network"
            action.name.startsWith("camera_media") -> "camera_media"
            action.name.startsWith("display") -> "display_ui"
            action.name.startsWith("binder") -> "binder_service"
            action.name.startsWith("memory") || action.name.startsWith("cache") -> "memory"
            action.name.startsWith("thermal") -> "power_thermal"
            action.name == "warm_launch" -> "app_prewarm"
            action.name == "widget_update" -> "widget"
            action.name == "latency_policy" || action.name == "latency_summary" -> "latency"
            else -> null
        }
    }

    private fun actionWeight(action: ActionResult): Double {
        if (action.status in setOf("failed", "blocked")) return 0.0
        if (action.status == "skipped") return 0.0
        return when (action.name) {
            "warm_launch" -> 1.0
            "memory_idle_maintenance" -> 0.7
            "cache_pressure_response" -> 0.8
            "memory_trim" -> 0.5
            "network_candidate_priority" -> 0.6
            "network_stats_refresh" -> 0.4
            "camera_media_candidate" -> 0.6
            "display_ui_policy" -> 0.4
            "binder_service_policy" -> 0.4
            "binder_service_refresh" -> 0.4
            "thermal_policy" -> 0.4
            "widget_update" -> 0.2
            "latency_policy" -> 0.1
            else -> 0.2
        }
    }

    private fun JSONArray?.categoryNames(): List<String> {
        return toStringList().map { value -> value.substringBeforeLast(" (").ifBlank { value } }
    }

    private fun jaccard(left: List<String>, right: List<String>): Double {
        val a = left.filter { it.isNotBlank() }.toSet()
        val b = right.filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val union = a + b
        if (union.isEmpty()) return 1.0
        return a.intersect(b).size.toDouble() / union.size.toDouble()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { idx -> optString(idx).takeIf { it.isNotBlank() } }
    }

    private data class AblationConfig(
        val id: String,
        val description: String,
        val removeCategories: Set<String> = emptySet(),
        val keepMemory: Boolean = true,
        val countersOnly: Boolean = false,
        val appSequenceBaseline: Boolean = false,
    )
}
