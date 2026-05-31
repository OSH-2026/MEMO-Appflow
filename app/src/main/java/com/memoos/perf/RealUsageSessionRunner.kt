package com.memoos.perf

import android.content.Context
import android.os.SystemClock
import com.memoos.ablation.RealEbpfAblationRunner
import com.memoos.action.ActionExecutor
import com.memoos.action.ActionResult
import com.memoos.action.AppIdMapping
import com.memoos.action.RecommendedApp
import com.memoos.device.DevicePaths
import com.memoos.device.EBPFCapabilityProbe
import com.memoos.device.RootShell
import com.memoos.ebpf.DeviceCollectorDeployer
import com.memoos.ebpf.EBPFEvent
import com.memoos.ebpf.EBPFTraceParser
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MaplePrediction
import com.memoos.maple.MapleScenario
import com.memoos.maple.MapleScenarioBuilder
import com.memoos.state.SystemStateCollector
import com.memoos.state.SystemStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class RealUsageSessionResult(
    val scenario: MapleScenario,
    val prediction: MaplePrediction,
    val recommendations: List<RecommendedApp>,
    val actions: List<ActionResult>,
    val latency: PipelineLatency,
    val usageReport: JSONObject,
    val ablationReport: JSONObject,
)

class RealUsageSessionRunner(private val context: Context) {
    fun run(iterations: Int = DEFAULT_ITERATIONS): RealUsageSessionResult {
        val timer = PipelineTimer("real_usage_${iterations}_app_rotation")
        val startedAtMs = System.currentTimeMillis()
        val reportDir = File(context.getExternalFilesDir(null), "reports").apply { mkdirs() }

        val capability = timer.measure("capability_probe") { EBPFCapabilityProbe.probe() }
        require(capability.canRunRawCollector) {
            "raw eBPF collector is required for real usage analysis; ${capability.notes.joinToString("; ")}"
        }
        timer.measure("local_runtime_preflight") { requireLocalMapleRuntime() }
        timer.measure("deploy_collector") { DeviceCollectorDeployer(context).ensureRawCollectorExecutable() }

        val before = timer.measure("system_state_before") { SystemStateCollector(context).collect() }
        val apps = timer.measure("select_real_apps") { chooseApps() }
        require(apps.isNotEmpty()) { "no launchable apps were available for real usage analysis" }
        val steps = buildSteps(apps, iterations)
        val tracePath = "${DevicePaths.LOG_DIR}/usage_${iterations}_${startedAtMs}.trace"
        val interaction = timer.measure("real_app_rotation") {
            collectWhileRunningInteractions(tracePath, steps)
        }
        val rawLines = timer.measure("ebpf_trace_read") { readMemoLines(tracePath) }
        val events = timer.measure("ebpf_parse") { EBPFTraceParser.parseLines(rawLines.asSequence()) }
        require(events.isNotEmpty()) {
            "100-use analysis produced zero eBPF events; trace=$tracePath"
        }
        val after = timer.measure("system_state_after") { SystemStateCollector(context).collect() }
        val launchStats = parseLaunchStats(interaction.stdout, steps)
        val topInteractionCategories = interactionCategoryCounts(steps)

        val scenario = timer.measure("scenario_build") {
            MapleScenarioBuilder(context).build(
                events = events,
                state = after,
                scenarioId = "real_usage_${iterations}_${startedAtMs}",
                description = "User-like Android session: MEMO opened real launchable apps $iterations times, collected raw eBPF evidence, then ran MAPLE and scheduling actions on device. Raw trace: $tracePath.",
                targetPackage = null,
                targetCategories = topInteractionCategories.keys.take(6),
            )
        }
        File(context.getExternalFilesDir(null), "latest_maple_scenario.json").writeText(scenario.scenarioJson)

        val prediction = timer.measure("maple_inference") { MapleInferenceOrchestrator(context).predict(scenario) }
        require(prediction.available) {
            "MAPLE prediction failed during real usage analysis: ${prediction.error ?: "unknown error"}"
        }
        val recommendations = timer.measure("maple_app_mapping") {
            AppIdMapping.resolveTopApps(
                context = context,
                predictedAppId = prediction.predictedAppId,
                stage1Categories = prediction.stage1.map { it.name },
                scenarioCategories = scenario.topCategories,
                foregroundPackage = after.process.foregroundPackage,
            )
        }
        val beforeActionsMs = timer.elapsedMs()
        val actions = timer.measure("action_execution") {
            ActionExecutor(context).execute(
                scenario = scenario,
                prediction = prediction,
                recommendations = recommendations,
                state = after,
                latencyBeforeActionsMs = beforeActionsMs,
                allowVisibleWarmLaunch = false,
            )
        }
        val ablationReport = timer.measure("ablation_analysis") { RealEbpfAblationRunner(context).run(scenario.scenarioJson) }
        val measuredLatency = timer.snapshot(events.size, mapleTimedOut = prediction.error?.contains("timed out", ignoreCase = true) == true)
        val latency = measuredLatency.copy(
            foregroundMs = measuredLatency.stages
                .filterNot { it.name in NON_BLOCKING_STAGES }
                .sumOf { it.durationMs }
                .coerceAtMost(measuredLatency.totalMs),
        )
        val report = buildReport(
            iterations = iterations,
            startedAtMs = startedAtMs,
            durationMs = System.currentTimeMillis() - startedAtMs,
            tracePath = tracePath,
            apps = apps,
            steps = steps,
            interaction = interaction,
            launches = launchStats,
            events = events,
            before = before,
            after = after,
            scenario = scenario,
            prediction = prediction,
            recommendations = recommendations,
            actions = actions,
            latency = latency,
            ablationReport = ablationReport,
        )
        val reportFile = File(reportDir, "latest_usage_${iterations}_report.json")
        reportFile.writeText(report.toString(2))
        File(context.getExternalFilesDir(null), "latest_pipeline_latency.json").writeText(latency.toJson().toString(2))
        publishPublicReport(reportFile)
        return RealUsageSessionResult(scenario, prediction, recommendations, actions, latency, report, ablationReport)
    }

    private fun chooseApps(): List<AppIdMapping.InstalledAppProfile> {
        return AppIdMapping.scanInstalledApps(context)
            .filter { app -> context.packageManager.getLaunchIntentForPackage(app.packageName)?.component != null }
            .sortedWith(
                compareByDescending<AppIdMapping.InstalledAppProfile> { it.isUserInstalled }
                    .thenByDescending { it.inferredCategories.size }
                    .thenBy { it.label.lowercase(Locale.US) },
            )
            .take(APP_POOL_SIZE)
    }

    private fun buildSteps(apps: List<AppIdMapping.InstalledAppProfile>, iterations: Int): List<UsageStep> {
        return (1..iterations).mapNotNull { index ->
            val app = apps[(index - 1) % apps.size]
            val component = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?.component
                ?.flattenToShortString()
                ?: return@mapNotNull null
            UsageStep(
                index = index,
                packageName = app.packageName,
                label = app.label,
                component = component,
                categories = app.inferredCategories.toList(),
            )
        }
    }

    private fun collectWhileRunningInteractions(tracePath: String, steps: List<UsageStep>): InteractionRun {
        RootShell.run("mkdir -p '${DevicePaths.LOG_DIR}'", requireRoot = true, timeoutMs = 3_000L)
        val collector = DevicePaths.RAW_COLLECTOR
        val durationSec = ((steps.size * 2.2) + 40.0).toInt().coerceAtLeast(120)
        RootShell.run(
            "rm -f '$tracePath'; ($collector --duration-sec $durationSec --max-events $MAX_EVENTS --output '$tracePath' 2>&1) & echo \$!",
            requireRoot = true,
            timeoutMs = 3_000L,
        )
        waitForCollector(tracePath)
        val command = buildInteractionCommand(steps)
        val output = RootShell.run(command, requireRoot = true, timeoutMs = (durationSec * 1_000L).coerceAtLeast(240_000L))
        RootShell.run("pkill -TERM -f memo_libbpf_collector 2>/dev/null", requireRoot = true, timeoutMs = 3_000L)
        waitForCollectorStop(tracePath)
        return InteractionRun(output.ok, output.stdout + "\n" + output.stderr)
    }

    private fun buildInteractionCommand(steps: List<UsageStep>): String {
        return steps.joinToString("; ") { step ->
            buildString {
                append("echo 'MEMO_STEP_BEGIN ${step.index} ${step.packageName}'; ")
                append("am start -W -n ").append(shellQuote(step.component)).append(" 2>&1; ")
                append(gestureFor(step.index)).append("; ")
                append("input keyevent KEYCODE_HOME >/dev/null 2>&1; ")
                append("sleep 0.20")
            }
        }
    }

    private fun gestureFor(index: Int): String {
        return when (index % 4) {
            0 -> "input swipe 540 1650 540 520 450 >/dev/null 2>&1; sleep 0.15"
            1 -> "input tap 540 980 >/dev/null 2>&1; sleep 0.15"
            2 -> "input swipe 540 520 540 1650 450 >/dev/null 2>&1; sleep 0.15"
            else -> "input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 0.15"
        }
    }

    private fun waitForCollector(tracePath: String) {
        val deadline = SystemClock.elapsedRealtime() + COLLECTOR_ATTACH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RootShell.run("grep -q 'collector_started' '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 1_000L).ok) return
            Thread.sleep(250L)
        }
        val head = RootShell.run("head -n 20 '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 3_000L).stdout
        error("raw eBPF collector did not attach for 100-use analysis; trace=$tracePath; head=${head.take(300)}")
    }

    private fun waitForCollectorStop(tracePath: String) {
        val deadline = SystemClock.elapsedRealtime() + COLLECTOR_STOP_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RootShell.run("grep -q 'collector_stopped' '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 1_000L).ok) return
            Thread.sleep(250L)
        }
    }

    private fun readMemoLines(tracePath: String): List<String> {
        return RootShell.run(
            "sed -n '/^MEMO/p' '$tracePath' 2>/dev/null | head -n $MAX_EVENTS",
            requireRoot = true,
            timeoutMs = 12_000L,
        ).stdout.lineSequence().filter { it.isNotBlank() }.toList()
    }

    private fun parseLaunchStats(output: String, steps: List<UsageStep>): List<LaunchObservation> {
        val byIndex = steps.associateBy { it.index }
        val observations = linkedMapOf<Int, MutableLaunchObservation>()
        var currentIndex: Int? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val marker = Regex("""^MEMO_STEP_BEGIN\s+(\d+)\s+(\S+)""").find(line)
            if (marker != null) {
                val index = marker.groupValues[1].toIntOrNull() ?: return@forEach
                currentIndex = index
                val step = byIndex[index] ?: return@forEach
                observations.getOrPut(index) { MutableLaunchObservation(step) }.seen = true
                return@forEach
            }
            val index = currentIndex ?: return@forEach
            val step = byIndex[index] ?: return@forEach
            val obs = observations.getOrPut(index) { MutableLaunchObservation(step) }
            when {
                line.startsWith("Status:") -> obs.status = line.substringAfter(':').trim()
                line.startsWith("LaunchState:") -> obs.launchState = line.substringAfter(':').trim()
                line.startsWith("TotalTime:") -> obs.totalTimeMs = line.substringAfter(':').trim().toLongOrNull()
                line.startsWith("WaitTime:") -> obs.waitTimeMs = line.substringAfter(':').trim().toLongOrNull()
            }
        }
        return steps.map { step ->
            val obs = observations[step.index]
            LaunchObservation(
                index = step.index,
                packageName = step.packageName,
                label = step.label,
                categories = step.categories,
                status = obs?.status,
                launchState = obs?.launchState,
                totalTimeMs = obs?.totalTimeMs,
                waitTimeMs = obs?.waitTimeMs,
                observed = obs?.seen == true,
            )
        }
    }

    private fun buildReport(
        iterations: Int,
        startedAtMs: Long,
        durationMs: Long,
        tracePath: String,
        apps: List<AppIdMapping.InstalledAppProfile>,
        steps: List<UsageStep>,
        interaction: InteractionRun,
        launches: List<LaunchObservation>,
        events: List<EBPFEvent>,
        before: SystemStateSnapshot,
        after: SystemStateSnapshot,
        scenario: MapleScenario,
        prediction: MaplePrediction,
        recommendations: List<RecommendedApp>,
        actions: List<ActionResult>,
        latency: PipelineLatency,
        ablationReport: JSONObject,
    ): JSONObject {
        val appCounts = launches.groupingBy { it.packageName }.eachCount()
        val labelByPackage = launches.associate { it.packageName to it.label }
        val eventCounts = events.groupingBy { it.eventType }.eachCount()
        val categoryCounts = interactionCategoryCounts(steps)
        val launchTimes = launches.mapNotNull { it.totalTimeMs }
        val waitTimes = launches.mapNotNull { it.waitTimeMs }
        val successfulLaunches = launches.count { it.status.equals("ok", ignoreCase = true) || it.observed }
        return JSONObject()
            .put("schema_version", "memo.real_usage_100.v1")
            .put("experiment_type", "real_user_like_100_app_rotation")
            .put("started_at_ms", startedAtMs)
            .put("duration_ms", durationMs)
            .put("interaction_count_requested", iterations)
            .put("interaction_count_observed", successfulLaunches)
            .put("interaction_command_ok", interaction.ok)
            .put("app_pool_size", apps.size)
            .put("unique_apps_opened", appCounts.size)
            .put("raw_trace_path", tracePath)
            .put("scenario_id", scenario.scenarioId)
            .put("report_files", JSONObject()
                .put("usage_report", "${DevicePaths.MEMO_PUBLIC_ROOT}/reports/latest_usage_${iterations}_report.json")
                .put("ablation_report", "${DevicePaths.MEMO_PUBLIC_ROOT}/ablations/latest_real_ablation.json"))
            .put("app_usage_top", JSONArray(appCounts.entries.sortedByDescending { it.value }.map { (pkg, count) ->
                JSONObject()
                    .put("package_name", pkg)
                    .put("label", labelByPackage[pkg] ?: pkg)
                    .put("count", count)
            }))
            .put("category_usage", JSONObject(categoryCounts.mapValues { it.value }))
            .put("launch_metrics", JSONObject()
                .put("avg_total_time_ms", launchTimes.averageOrNull())
                .put("p50_total_time_ms", launchTimes.percentileOrNull(50))
                .put("avg_wait_time_ms", waitTimes.averageOrNull())
                .put("p50_wait_time_ms", waitTimes.percentileOrNull(50))
                .put("launch_state_counts", JSONObject(launches.mapNotNull { it.launchState }.groupingBy { it }.eachCount().mapValues { it.value })))
            .put("ebpf", JSONObject()
                .put("parsed_events", events.size)
                .put("event_counts", JSONObject(eventCounts.mapValues { it.value }))
                .put("top_event_types", JSONArray(eventCounts.entries.sortedByDescending { it.value }.take(8).map { "${it.key}:${it.value}" })))
            .put("system_before", before.toJson())
            .put("system_after", after.toJson())
            .put("system_delta", systemDelta(before, after))
            .put("maple", JSONObject()
                .put("backend", prediction.backend)
                .put("predicted_app_id", prediction.predictedAppId)
                .put("stage1", JSONArray(prediction.stage1.map { "${it.name} (${(it.probability * 100).toInt()}%)" })))
            .put("recommendations", JSONArray(recommendations.map { app ->
                JSONObject()
                    .put("package_name", app.packageName)
                    .put("label", app.label)
                    .put("category", app.category)
                    .put("confidence", app.confidence)
            }))
            .put("actions", JSONArray(actions.map { it.toJson() }))
            .put("latency", latency.toJson())
            .put("ablation_summary", ablationReport.optJSONObject("summary") ?: JSONObject())
            .put("ablation_interpretation", ablationInterpretation(ablationReport))
    }

    private fun interactionCategoryCounts(steps: List<UsageStep>): LinkedHashMap<String, Int> {
        val counts = linkedMapOf<String, Int>()
        steps.forEach { step ->
            val categories = step.categories.ifEmpty { listOf("App Process Runtime") }
            categories.take(3).forEach { category -> counts[category] = (counts[category] ?: 0) + 1 }
        }
        return counts.entries.sortedByDescending { it.value }.associate { it.key to it.value }.toMap(LinkedHashMap())
    }

    private fun SystemStateSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("wall_time", wallTime)
            .put("mem_available_kb", memory.memAvailableKb ?: JSONObject.NULL)
            .put("mem_total_kb", memory.memTotalKb ?: JSONObject.NULL)
            .put("memory_pressure", memory.pressureLevel)
            .put("swap_free_kb", memory.swapFreeKb ?: JSONObject.NULL)
            .put("pgscan_direct", memory.pgscanDirect ?: JSONObject.NULL)
            .put("pgscan_kswapd", memory.pgscanKswapd ?: JSONObject.NULL)
            .put("battery_percent", battery.levelPercent ?: JSONObject.NULL)
            .put("temperature_c", battery.temperatureC ?: JSONObject.NULL)
            .put("thermal_risk", battery.thermalRisk)
            .put("udp_in_datagrams", network.udpInDatagrams ?: JSONObject.NULL)
            .put("udp_out_datagrams", network.udpOutDatagrams ?: JSONObject.NULL)
            .put("udp_socket_count", network.udpSocketCount ?: JSONObject.NULL)
            .put("foreground_package", process.foregroundPackage ?: JSONObject.NULL)
            .put("surfaceflinger_pid", mediaDisplay.surfaceFlingerPid ?: JSONObject.NULL)
            .put("cameraserver_pid", mediaDisplay.cameraServerPid ?: JSONObject.NULL)
            .put("mediacodec_pid", mediaDisplay.mediaCodecPid ?: JSONObject.NULL)
            .put("render_thread_observed", mediaDisplay.renderThreadObserved)
    }

    private fun systemDelta(before: SystemStateSnapshot, after: SystemStateSnapshot): JSONObject {
        fun longDelta(left: Long?, right: Long?): Any {
            return if (left != null && right != null) right - left else JSONObject.NULL
        }
        val beforeBattery = before.battery.levelPercent
        val afterBattery = after.battery.levelPercent
        val beforeTemp = before.battery.temperatureC
        val afterTemp = after.battery.temperatureC
        return JSONObject()
            .put("mem_available_delta_kb", longDelta(before.memory.memAvailableKb, after.memory.memAvailableKb))
            .put("udp_in_delta", longDelta(before.network.udpInDatagrams, after.network.udpInDatagrams))
            .put("udp_out_delta", longDelta(before.network.udpOutDatagrams, after.network.udpOutDatagrams))
            .put("pgscan_direct_delta", longDelta(before.memory.pgscanDirect, after.memory.pgscanDirect))
            .put("pgscan_kswapd_delta", longDelta(before.memory.pgscanKswapd, after.memory.pgscanKswapd))
            .put("battery_percent_delta", if (beforeBattery != null && afterBattery != null) afterBattery - beforeBattery else JSONObject.NULL)
            .put("temperature_c_delta", if (beforeTemp != null && afterTemp != null) afterTemp - beforeTemp else JSONObject.NULL)
    }

    private fun ablationInterpretation(report: JSONObject): String {
        val summary = report.optJSONObject("summary") ?: return "消融报告未生成。"
        val changedTop1 = summary.optJSONArray("changed_top1_app_configs").toStringList()
        val changedPrediction = summary.optJSONArray("changed_predicted_app_configs").toStringList()
        val changedScheduling = summary.optJSONArray("changed_predicted_scheduler_domain_configs").toStringList()
        return buildString {
            append("以 full_real_ebpf 为参照。")
            append("预测 ID 改变的配置: ").append(changedPrediction.ifEmpty { listOf("无") }.joinToString()).append("。")
            append("Top-1 应用改变的配置: ").append(changedTop1.ifEmpty { listOf("无") }.joinToString()).append("。")
            append("调度域改变的配置: ").append(changedScheduling.ifEmpty { listOf("无") }.joinToString()).append("。")
        }
    }

    private fun publishPublicReport(reportFile: File) {
        RootShell.run(
            "mkdir -p '${DevicePaths.MEMO_PUBLIC_ROOT}/reports'; cp '${reportFile.absolutePath}' '${DevicePaths.MEMO_PUBLIC_ROOT}/reports/${reportFile.name}'; chmod 0644 '${DevicePaths.MEMO_PUBLIC_ROOT}/reports/${reportFile.name}'",
            requireRoot = true,
            timeoutMs = 5_000L,
        )
    }

    private fun requireLocalMapleRuntime() {
        val required = listOf(
            DevicePaths.MAPLE_DEMO,
            DevicePaths.MAPLE_ENGINE_SO,
            DevicePaths.MAPLE_CXX_SHARED,
            DevicePaths.DEFAULT_MODEL,
        )
        val missing = required.filterNot { path ->
            RootShell.run("test -r '$path'", requireRoot = true, timeoutMs = 3_000L).ok
        }
        require(missing.isEmpty()) {
            "phone-local MAPLE runtime is incomplete: ${missing.joinToString()}"
        }
    }

    private fun ActionResult.toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("target", target)
            .put("status", status)
            .put("detail", detail)
            .put("duration_ms", durationMs)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun List<Long>.averageOrNull(): Any {
        return if (isEmpty()) JSONObject.NULL else average()
    }

    private fun List<Long>.percentileOrNull(percentile: Int): Any {
        if (isEmpty()) return JSONObject.NULL
        val sorted = sorted()
        val idx = (((percentile / 100.0) * (sorted.size - 1))).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { idx -> optString(idx).takeIf { it.isNotBlank() } }
    }

    private data class UsageStep(
        val index: Int,
        val packageName: String,
        val label: String,
        val component: String,
        val categories: List<String>,
    )

    private data class InteractionRun(
        val ok: Boolean,
        val stdout: String,
    )

    private data class MutableLaunchObservation(
        val step: UsageStep,
        var seen: Boolean = false,
        var status: String? = null,
        var launchState: String? = null,
        var totalTimeMs: Long? = null,
        var waitTimeMs: Long? = null,
    )

    private data class LaunchObservation(
        val index: Int,
        val packageName: String,
        val label: String,
        val categories: List<String>,
        val status: String?,
        val launchState: String?,
        val totalTimeMs: Long?,
        val waitTimeMs: Long?,
        val observed: Boolean,
    )

    private companion object {
        const val DEFAULT_ITERATIONS = 100
        const val APP_POOL_SIZE = 12
        const val MAX_EVENTS = 20_000
        const val COLLECTOR_ATTACH_TIMEOUT_MS = 10_000L
        const val COLLECTOR_STOP_TIMEOUT_MS = 12_000L
        private val NON_BLOCKING_STAGES = setOf("real_app_rotation", "maple_inference", "ablation_analysis")
    }
}
