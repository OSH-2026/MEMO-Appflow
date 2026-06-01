package com.memoos.perf

import android.content.Context
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
import com.memoos.maple.CompressedEbpfSignal
import com.memoos.maple.MapleAppTimelineEntry
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MaplePrediction
import com.memoos.maple.MapleScenario
import com.memoos.maple.MapleScenarioBuilder
import com.memoos.maple.MapleTimelineWindow
import com.memoos.state.SystemStateCollector
import com.memoos.state.SystemStateSnapshot
import com.memoos.store.FreeUsageSessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class FreeUsageSessionStart(
    val startedAtMs: Long,
    val rawTracePath: String,
    val appSamplesPath: String,
    val flagPath: String,
    val beforeStateJson: String,
)

data class FreeUsageSessionResult(
    val scenario: MapleScenario,
    val prediction: MaplePrediction,
    val recommendations: List<RecommendedApp>,
    val actions: List<ActionResult>,
    val latency: PipelineLatency,
    val usageReport: JSONObject,
)

class FreeUsageSessionRunner(private val context: Context) {
    fun start(maxDurationSec: Int = MAX_SESSION_SECONDS): FreeUsageSessionStart {
        val capability = EBPFCapabilityProbe.probe()
        require(capability.canRunRawCollector) {
            "raw eBPF collector is required for free usage session; ${capability.notes.joinToString("; ")}"
        }
        requireLocalMapleRuntime()
        DeviceCollectorDeployer(context).ensureRawCollectorExecutable()
        RootShell.run("mkdir -p '${DevicePaths.LOG_DIR}' '${DevicePaths.MEMO_PUBLIC_ROOT}/reports'", requireRoot = true, timeoutMs = 3_000L)

        val startedAtMs = System.currentTimeMillis()
        val rawTracePath = "${DevicePaths.LOG_DIR}/free_usage_${startedAtMs}.trace"
        val appSamplesPath = "${DevicePaths.LOG_DIR}/free_usage_${startedAtMs}_apps.tsv"
        val flagPath = "${DevicePaths.LOG_DIR}/free_usage_${startedAtMs}.active"
        val beforeStateJson = snapshotJson(SystemStateCollector(context).collect()).toString()
        val collector = capability.rawCollectorPath ?: DevicePaths.RAW_COLLECTOR

        RootShell.run(
            "rm -f '$rawTracePath' '$appSamplesPath'; : > '$flagPath'; " +
                "($collector --duration-sec $maxDurationSec --max-events $MAX_EVENTS --output '$rawTracePath' 2>&1) >/dev/null 2>&1 & echo \$! > '${flagPath}.collector.pid'",
            requireRoot = true,
            timeoutMs = 4_000L,
        )
        waitForCollector(rawTracePath)
        installAndStartSampler(flagPath, appSamplesPath)
        return FreeUsageSessionStart(startedAtMs, rawTracePath, appSamplesPath, flagPath, beforeStateJson)
    }

    fun finish(active: FreeUsageSessionState): FreeUsageSessionResult {
        require(active.active) { "free usage session is not active" }
        val timer = PipelineTimer("free_usage_session")
        val endedAtMs = System.currentTimeMillis()
        timer.measure("stop_collectors") {
            RootShell.run("rm -f '${active.flagPath}'; pkill -TERM -f memo_libbpf_collector 2>/dev/null", requireRoot = true, timeoutMs = 3_000L)
            Thread.sleep(900L)
        }
        val rawLines = timer.measure("ebpf_trace_read") { readMemoLines(active.rawTracePath) }
        val events = timer.measure("ebpf_parse") { EBPFTraceParser.parseLines(rawLines.asSequence()) }
        require(events.isNotEmpty()) {
            "free usage session produced zero eBPF events; trace=${active.rawTracePath}"
        }
        val samples = timer.measure("app_samples_read") { readAppSamples(active.appSamplesPath) }
        val appTimeline = timer.measure("app_timeline_build") { buildAppTimeline(samples, active.startedAtMs, endedAtMs) }
        require(appTimeline.isNotEmpty()) {
            "free usage session did not observe non-MEMO foreground apps; use other apps before ending the session"
        }
        val timelineWindows = timer.measure("timeline_window_build") {
            buildTimelineWindows(appTimeline, events, active.startedAtMs, endedAtMs)
        }
        val compressedSignalCount = timelineWindows.sumOf { it.compressedEbpf.size }
        require(compressedSignalCount > 0) {
            "free usage session produced zero compressed eBPF signals; raw_events=${events.size}, app_segments=${appTimeline.size}"
        }
        val after = timer.measure("system_state_after") { SystemStateCollector(context).collect() }
        val scenario = timer.measure("scenario_build") {
            MapleScenarioBuilder(context).build(
                events = events,
                state = after,
                scenarioId = "free_usage_${active.startedAtMs}",
                description = "Free Android session: MEMO observed real app usage chosen outside MEMO, collected raw eBPF, then ran MAPLE and scheduling actions on device. Raw trace: ${active.rawTracePath}.",
                targetPackage = null,
                targetCategories = appTimeline.flatMap { it.categories }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(6),
                appTimeline = appTimeline,
                timelineWindows = timelineWindows,
            )
        }
        File(context.getExternalFilesDir(null), "latest_maple_scenario.json").writeText(scenario.scenarioJson)
        publishScenario(scenario)
        val prediction = timer.measure("maple_inference") { MapleInferenceOrchestrator(context).predict(scenario) }
        require(prediction.available) {
            "MAPLE prediction failed for free usage session: ${prediction.error ?: "unknown error"}"
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
        val latency = timer.snapshot(events.size, mapleTimedOut = prediction.error?.contains("timed out", ignoreCase = true) == true)
        val report = buildReport(
            active = active,
            endedAtMs = endedAtMs,
            samples = samples,
            appTimeline = appTimeline,
            windows = timelineWindows,
            events = events,
            beforeJson = JSONObject(active.beforeStateJson.ifBlank { "{}" }),
            after = after,
            scenario = scenario,
            prediction = prediction,
            recommendations = recommendations,
            actions = actions,
            latency = latency,
        )
        val reportFile = File(context.getExternalFilesDir(null), "latest_free_usage_report.json")
        reportFile.writeText(report.toString(2))
        File(context.getExternalFilesDir(null), "latest_pipeline_latency.json").writeText(latency.toJson().toString(2))
        publishReport(reportFile)
        return FreeUsageSessionResult(scenario, prediction, recommendations, actions, latency, report)
    }

    private fun installAndStartSampler(flagPath: String, appSamplesPath: String) {
        val scriptFile = File(context.filesDir, "free_usage_sampler_${System.currentTimeMillis()}.sh")
        scriptFile.writeText(samplerScript(flagPath, appSamplesPath))
        val result = RootShell.run(
            "chmod 0700 '${scriptFile.absolutePath}'; nohup sh '${scriptFile.absolutePath}' >/dev/null 2>&1 & echo \$! > '${flagPath}.sampler.pid'",
            requireRoot = true,
            timeoutMs = 4_000L,
        )
        require(result.ok) {
            "free usage foreground app sampler failed to start: ${result.stderr.ifBlank { result.stdout }.take(300)}"
        }
    }

    private fun samplerScript(flagPath: String, appSamplesPath: String): String {
        val script = """
            #!/system/bin/sh
            extract_pkg() {
              printf '%s\n' "$1" | sed -n 's/.* u[0-9][0-9]* \([^/ ]*\)\/.*/\1/p'
            }
            while [ -f '$flagPath' ]; do
              ts=${'$'}(date +%s%3N)
              line=${'$'}(dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity=')
              if [ -z "${'$'}line" ]; then line=${'$'}(dumpsys activity activities 2>/dev/null | grep -m 1 'ResumedActivity:'); fi
              if [ -z "${'$'}line" ]; then line=${'$'}(dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus='); fi
              if [ -z "${'$'}line" ]; then line=${'$'}(dumpsys window 2>/dev/null | grep -m 1 'mFocusedApp='); fi
              pkg=${'$'}(extract_pkg "${'$'}line")
              if [ -n "${'$'}pkg" ]; then printf '%s\t%s\t%s\n' "${'$'}ts" "${'$'}pkg" "${'$'}line" >> '$appSamplesPath'; fi
              sleep 1
            done
        """.trimIndent()
        return script + "\n"
    }

    private fun readMemoLines(tracePath: String): List<String> {
        return RootShell.run(
            "sed -n '/^MEMO/p' '$tracePath' 2>/dev/null | head -n $MAX_EVENTS",
            requireRoot = true,
            timeoutMs = 12_000L,
        ).stdout.lineSequence().filter { it.isNotBlank() }.toList()
    }

    private fun readAppSamples(path: String): List<AppSample> {
        return RootShell.run("cat '$path' 2>/dev/null | head -n $MAX_APP_SAMPLES", requireRoot = true, timeoutMs = 8_000L)
            .stdout
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                val timeMs = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val pkg = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AppSample(timeMs, pkg)
            }
            .filter { it.packageName != context.packageName }
            .toList()
    }

    private fun buildAppTimeline(samples: List<AppSample>, startedAtMs: Long, endedAtMs: Long): List<MapleAppTimelineEntry> {
        if (samples.isEmpty()) return emptyList()
        val profiles = AppIdMapping.scanInstalledApps(context).associateBy { it.packageName }
        val segments = mutableListOf<AppSegment>()
        samples.sortedBy { it.timeMs }.forEach { sample ->
            val last = segments.lastOrNull()
            if (last?.packageName == sample.packageName) {
                last.endMs = sample.timeMs
            } else {
                if (last != null && last.endMs <= last.startMs) last.endMs = sample.timeMs
                segments += AppSegment(sample.packageName, sample.timeMs, sample.timeMs)
            }
        }
        segments.lastOrNull()?.let { if (it.endMs <= it.startMs) it.endMs = endedAtMs }
        return segments.mapIndexed { index, segment ->
            val profile = profiles[segment.packageName]
            val label = profile?.label ?: segment.packageName.substringAfterLast('.')
            val categories = profile?.inferredCategories?.toList()?.ifEmpty { listOf(AppIdMapping.categoryForPackage(segment.packageName)) }
                ?: listOf(AppIdMapping.categoryForPackage(segment.packageName))
            MapleAppTimelineEntry(
                index = index + 1,
                packageName = segment.packageName,
                label = label,
                categories = categories,
                startWallTimeMs = segment.startMs.coerceAtLeast(startedAtMs),
                endWallTimeMs = segment.endMs.coerceAtLeast(segment.startMs + 1L),
                launchState = "OBSERVED",
                totalTimeMs = null,
                waitTimeMs = null,
                observed = true,
            )
        }
    }

    private fun buildTimelineWindows(
        appTimeline: List<MapleAppTimelineEntry>,
        events: List<EBPFEvent>,
        startedAtMs: Long,
        endedAtMs: Long,
    ): List<MapleTimelineWindow> {
        val chunks = appTimeline.chunked(TIMELINE_WINDOW_APPS).ifEmpty { listOf(emptyList()) }
        val alignmentStart = appTimeline.mapNotNull { it.startWallTimeMs }.minOrNull() ?: startedAtMs
        val alignmentEnd = appTimeline.mapNotNull { it.endWallTimeMs }.maxOrNull() ?: endedAtMs
        val timedEvents = events.mapIndexed { eventIndex, event ->
            val rawWallTimeMs = event.wallTimeMs()
            TimedEvent(
                event = event,
                wallTimeMs = if (rawWallTimeMs != null && rawWallTimeMs in alignmentStart..alignmentEnd) {
                    rawWallTimeMs
                } else {
                    estimateEventTimeMs(eventIndex, events.size, alignmentStart, alignmentEnd)
                },
            )
        }
        return chunks.mapIndexed { index, apps ->
            val start = apps.mapNotNull { it.startWallTimeMs }.minOrNull() ?: startedAtMs
            val end = apps.mapNotNull { it.endWallTimeMs }.maxOrNull() ?: endedAtMs
            val windowEvents = timedEvents
                .filter { it.wallTimeMs in start..end }
                .map { it.event }
            val counts = windowEvents.groupingBy { it.eventType }.eachCount()
            val totals = counterTotals(windowEvents)
            MapleTimelineWindow(
                index = index + 1,
                startWallTimeMs = start,
                endWallTimeMs = end.coerceAtLeast(start + 1L),
                apps = apps,
                ebpfEventCounts = counts,
                ebpfCounterTotals = totals,
                compressedEbpf = compressEbpfSignals(start, end.coerceAtLeast(start + 1L), counts, totals),
            )
        }
    }

    private fun estimateEventTimeMs(index: Int, total: Int, startedAtMs: Long, endedAtMs: Long): Long {
        if (total <= 1) return startedAtMs
        val span = (endedAtMs - startedAtMs).coerceAtLeast(1L)
        val offset = ((span.toDouble() * index.toDouble()) / (total - 1).toDouble()).toLong()
        return startedAtMs + offset.coerceIn(0L, span)
    }

    private fun buildReport(
        active: FreeUsageSessionState,
        endedAtMs: Long,
        samples: List<AppSample>,
        appTimeline: List<MapleAppTimelineEntry>,
        windows: List<MapleTimelineWindow>,
        events: List<EBPFEvent>,
        beforeJson: JSONObject,
        after: SystemStateSnapshot,
        scenario: MapleScenario,
        prediction: MaplePrediction,
        recommendations: List<RecommendedApp>,
        actions: List<ActionResult>,
        latency: PipelineLatency,
    ): JSONObject {
        val appCounts = appTimeline.groupingBy { it.packageName }.eachCount()
        val labelByPackage = appTimeline.associate { it.packageName to it.label }
        val dwellTimes = appTimeline.mapNotNull { app ->
            val start = app.startWallTimeMs
            val end = app.endWallTimeMs
            if (start != null && end != null && end >= start) end - start else null
        }
        val eventCounts = events.groupingBy { it.eventType }.eachCount()
        val categoryCounts = appCategoryCounts(appTimeline)
        val afterJson = snapshotJson(after)
        val optimization = optimizationSummary(actions)
        val compressedSignalCount = windows.sumOf { it.compressedEbpf.size }
        return JSONObject()
            .put("schema_version", "memo.free_usage_session.v1")
            .put("experiment_type", "free_usage_session")
            .put("session_mode", "free_usage")
            .put("processing_pipeline", processingPipeline("manual_foreground_app_sampler"))
            .put("started_at_ms", active.startedAtMs)
            .put("ended_at_ms", endedAtMs)
            .put("duration_ms", endedAtMs - active.startedAtMs)
            .put("interaction_count_requested", appTimeline.size)
            .put("interaction_count_observed", appTimeline.size)
            .put("app_sample_count", samples.size)
            .put("unique_apps_opened", appCounts.size)
            .put("interaction_command_ok", true)
            .put("raw_trace_path", active.rawTracePath)
            .put("app_samples_path", active.appSamplesPath)
            .put("scenario_id", scenario.scenarioId)
            .put("report_files", JSONObject()
                .put("usage_report", "${DevicePaths.MEMO_PUBLIC_ROOT}/reports/latest_free_usage_report.json")
                .put("scenario", "${DevicePaths.SCENARIO_DIR}/latest_maple_scenario.json"))
            .put("app_usage_top", JSONArray(appCounts.entries.sortedByDescending { it.value }.map { (pkg, count) ->
                JSONObject()
                    .put("package_name", pkg)
                    .put("label", labelByPackage[pkg] ?: pkg)
                    .put("count", count)
            }))
            .put("category_usage", JSONObject(categoryCounts.mapValues { it.value }))
            .put("launch_metrics", JSONObject()
                .put("avg_total_time_ms", JSONObject.NULL)
                .put("p50_total_time_ms", JSONObject.NULL)
                .put("avg_wait_time_ms", JSONObject.NULL)
                .put("avg_dwell_time_ms", dwellTimes.averageOrNull())
                .put("p50_dwell_time_ms", dwellTimes.percentileOrNull(50))
                .put("launch_state_counts", JSONObject().put("OBSERVED", appTimeline.size)))
            .put("app_sequence", JSONArray(appTimeline.map { it.toJsonForReport() }))
            .put("timeline_windows", JSONArray(windows.map { it.toJsonForReport() }))
            .put("compression", JSONObject()
                .put("strategy", "windowed_count_aggregation")
                .put("raw_ebpf_rows_for_audit", events.size)
                .put("observed_app_sequence_rows", appTimeline.size)
                .put("timeline_windows", windows.size)
                .put("compressed_ebpf_signal_rows", compressedSignalCount)
                .put(
                    "model_input_note",
                    "MAPLE receives per-window event_type/detail/count/rate_per_sec compressed signals; raw trace remains available only for audit.",
                ))
            .put("ebpf", JSONObject()
                .put("parsed_events", events.size)
                .put("compressed_signal_rows_for_maple", compressedSignalCount)
                .put("event_counts", JSONObject(eventCounts.mapValues { it.value }))
                .put("top_event_types", JSONArray(eventCounts.entries.sortedByDescending { it.value }.take(8).map { "${it.key}:${it.value}" })))
            .put("system_before", beforeJson)
            .put("system_after", afterJson)
            .put("system_delta", systemDelta(beforeJson, afterJson))
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
            .put("optimization_effect", optimization)
            .put("latency", latency.toJson())
    }

    private fun optimizationSummary(actions: List<ActionResult>): JSONObject {
        val ok = actions.count { it.status == "ok" }
        val planned = actions.count { it.status == "planned" }
        val skipped = actions.count { it.status == "skipped" }
        val domains = actions.map { friendlyActionDomain(it.name) }.filter { it.isNotBlank() }.distinct()
        return JSONObject()
            .put("action_count", actions.size)
            .put("executed_count", ok)
            .put("prepared_count", planned)
            .put("skipped_count", skipped)
            .put("domains", JSONArray(domains))
            .put(
                "how_performance_can_improve",
                "MEMO used the observed app sequence and eBPF evidence to update Top-3 recommendations, refresh network/service state, adjust memory/display policy, and prepare non-intrusive warm-launch candidates. This free session has no fixed off-baseline, so percentage improvement is reported by the separate A/B experiment.",
            )
    }

    private fun appCategoryCounts(appTimeline: List<MapleAppTimelineEntry>): LinkedHashMap<String, Int> {
        val counts = linkedMapOf<String, Int>()
        appTimeline.forEach { app ->
            app.categories.ifEmpty { listOf(AppIdMapping.categoryForPackage(app.packageName)) }
                .take(3)
                .forEach { category -> counts[category] = (counts[category] ?: 0) + 1 }
        }
        return counts.entries.sortedByDescending { it.value }.associate { it.key to it.value }.toMap(LinkedHashMap())
    }

    private fun processingPipeline(appSource: String): JSONArray {
        return JSONArray(listOf(
            "app_sequence_capture:$appSource",
            "raw_ebpf_trace:libbpf_core_collector",
            "timeline_alignment:wall_time_windows",
            "ebpf_compression:windowed_count_rate_signals",
            "maple_inference:compressed_timeline_scenario",
            "top3_mapping:installed_launchable_apps",
            "action_execution:non_intrusive_scheduler",
            "report_widget_update:device_side_outputs",
        ))
    }

    private fun friendlyActionDomain(name: String): String {
        return when {
            "memory" in name || "cache" in name -> "memory"
            "network" in name -> "network"
            "camera" in name || "media" in name -> "camera_media"
            "display" in name -> "display_ui"
            "binder" in name || "service" in name -> "system_service"
            "warm" in name -> "warm_launch"
            "widget" in name -> "recommendation_display"
            else -> ""
        }
    }

    private fun waitForCollector(tracePath: String) {
        val deadline = System.currentTimeMillis() + COLLECTOR_ATTACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (RootShell.run("grep -q 'collector_started' '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 1_000L).ok) return
            Thread.sleep(250L)
        }
        val head = RootShell.run("head -n 20 '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 3_000L).stdout
        error("raw eBPF collector did not attach for free usage session; trace=$tracePath; head=${head.take(300)}")
    }

    private fun requireLocalMapleRuntime() {
        val missing = listOf(
            DevicePaths.MAPLE_DEMO,
            DevicePaths.MAPLE_ENGINE_SO,
            DevicePaths.MAPLE_CXX_SHARED,
            DevicePaths.DEFAULT_MODEL,
        ).filterNot { RootShell.run("test -r '$it'", requireRoot = true, timeoutMs = 3_000L).ok }
        require(missing.isEmpty()) { "phone-local MAPLE runtime is incomplete: ${missing.joinToString()}" }
    }

    private fun publishReport(reportFile: File) {
        RootShell.run(
            "mkdir -p '${DevicePaths.MEMO_PUBLIC_ROOT}/reports'; cp '${reportFile.absolutePath}' '${DevicePaths.MEMO_PUBLIC_ROOT}/reports/latest_free_usage_report.json'; chmod 0644 '${DevicePaths.MEMO_PUBLIC_ROOT}/reports/latest_free_usage_report.json'",
            requireRoot = true,
            timeoutMs = 5_000L,
        )
    }

    private fun publishScenario(scenario: MapleScenario) {
        val scenarioFile = File(context.getExternalFilesDir(null), "latest_free_usage_maple_scenario.json")
        scenarioFile.writeText(scenario.scenarioJson)
        RootShell.run(
            "mkdir -p '${DevicePaths.SCENARIO_DIR}'; " +
                "cp '${scenarioFile.absolutePath}' '${DevicePaths.SCENARIO_DIR}/latest_free_usage_maple_scenario.json'; " +
                "cp '${scenarioFile.absolutePath}' '${DevicePaths.SCENARIO_DIR}/latest_maple_scenario.json'; " +
                "chmod 0644 '${DevicePaths.SCENARIO_DIR}/latest_free_usage_maple_scenario.json' '${DevicePaths.SCENARIO_DIR}/latest_maple_scenario.json'",
            requireRoot = true,
            timeoutMs = 5_000L,
        )
    }

    private fun EBPFEvent.wallTimeMs(): Long? {
        timestampNs?.let {
            if (it >= EPOCH_NS_THRESHOLD) return it / 1_000_000L
        }
        timestampS?.let {
            if (it >= EPOCH_SECONDS_THRESHOLD) return (it * 1000.0).toLong()
        }
        return null
    }

    private fun counterTotals(events: List<EBPFEvent>): Map<String, Long> {
        val totals = linkedMapOf<String, Long>()
        events.forEach { event ->
            val count = event.extra["arg1"]?.toLongOrNull() ?: return@forEach
            totals[event.eventType] = maxOf(totals[event.eventType] ?: 0L, count)
        }
        return totals
    }

    private fun compressEbpfSignals(
        startWallTimeMs: Long,
        endWallTimeMs: Long,
        eventCounts: Map<String, Int>,
        counterTotals: Map<String, Long>,
    ): List<CompressedEbpfSignal> {
        val durationSec = ((endWallTimeMs - startWallTimeMs).coerceAtLeast(1L)).toDouble() / 1000.0
        val keys = (counterTotals.keys + eventCounts.keys)
            .filter { it != "MEMO_STATUS" }
            .distinct()
        return keys.mapNotNull { eventType ->
            val count = counterTotals[eventType] ?: eventCounts[eventType]?.toLong() ?: return@mapNotNull null
            if (count <= 0L) return@mapNotNull null
            CompressedEbpfSignal(eventType, eventDetail(eventType), count, count / durationSec)
        }.sortedWith(compareByDescending<CompressedEbpfSignal> { it.count }.thenBy { it.eventType })
    }

    private fun eventDetail(eventType: String): String {
        return when (eventType) {
            "MEMO_BINDER" -> "binder_transaction"
            "MEMO_OPENAT" -> "openat"
            "MEMO_SENDTO" -> "sendto"
            "MEMO_RECVFROM" -> "recvfrom"
            "MEMO_SCHED" -> "sched_switch_or_wakeup"
            "MEMO_PROCESS_FORK" -> "process_fork"
            "MEMO_PROCESS_EXIT" -> "process_exit"
            "MEMO_PROCESS_EXEC" -> "process_exec"
            "MEMO_MEMORY" -> "reclaim_or_kswapd"
            "MEMO_INPUT" -> "input_event"
            else -> eventType.removePrefix("MEMO_").lowercase(Locale.US)
        }
    }

    private fun MapleAppTimelineEntry.toJsonForReport(): JSONObject {
        return JSONObject()
            .put("index", index)
            .put("package_name", packageName)
            .put("label", label)
            .put("categories", JSONArray(categories))
            .put("start_wall_time_ms", startWallTimeMs ?: JSONObject.NULL)
            .put("end_wall_time_ms", endWallTimeMs ?: JSONObject.NULL)
            .put("launch_state", launchState ?: JSONObject.NULL)
            .put("total_time_ms", totalTimeMs ?: JSONObject.NULL)
            .put("wait_time_ms", waitTimeMs ?: JSONObject.NULL)
            .put("observed", observed)
    }

    private fun MapleTimelineWindow.toJsonForReport(): JSONObject {
        return JSONObject()
            .put("index", index)
            .put("start_wall_time_ms", startWallTimeMs)
            .put("end_wall_time_ms", endWallTimeMs)
            .put("apps", JSONArray(apps.map { it.toJsonForReport() }))
            .put("ebpf_event_counts", JSONObject(ebpfEventCounts.mapValues { it.value }))
            .put("ebpf_counter_totals", JSONObject(ebpfCounterTotals.mapValues { it.value }))
            .put("compressed_ebpf", JSONArray(compressedEbpf.map { it.toJsonForReport() }))
    }

    private fun CompressedEbpfSignal.toJsonForReport(): JSONObject {
        return JSONObject()
            .put("event_type", eventType)
            .put("detail", detail)
            .put("count", count)
            .put("rate_per_sec", ratePerSec)
    }

    private fun ActionResult.toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("target", target)
            .put("status", status)
            .put("detail", detail)
            .put("duration_ms", durationMs)
    }

    private fun snapshotJson(state: SystemStateSnapshot): JSONObject {
        return JSONObject()
            .put("wall_time", state.wallTime)
            .put("mem_available_kb", state.memory.memAvailableKb ?: JSONObject.NULL)
            .put("mem_total_kb", state.memory.memTotalKb ?: JSONObject.NULL)
            .put("memory_pressure", state.memory.pressureLevel)
            .put("swap_free_kb", state.memory.swapFreeKb ?: JSONObject.NULL)
            .put("pgscan_direct", state.memory.pgscanDirect ?: JSONObject.NULL)
            .put("pgscan_kswapd", state.memory.pgscanKswapd ?: JSONObject.NULL)
            .put("battery_percent", state.battery.levelPercent ?: JSONObject.NULL)
            .put("temperature_c", state.battery.temperatureC ?: JSONObject.NULL)
            .put("thermal_risk", state.battery.thermalRisk)
            .put("udp_in_datagrams", state.network.udpInDatagrams ?: JSONObject.NULL)
            .put("udp_out_datagrams", state.network.udpOutDatagrams ?: JSONObject.NULL)
            .put("udp_socket_count", state.network.udpSocketCount ?: JSONObject.NULL)
            .put("foreground_package", state.process.foregroundPackage ?: JSONObject.NULL)
            .put("surfaceflinger_pid", state.mediaDisplay.surfaceFlingerPid ?: JSONObject.NULL)
            .put("cameraserver_pid", state.mediaDisplay.cameraServerPid ?: JSONObject.NULL)
            .put("mediacodec_pid", state.mediaDisplay.mediaCodecPid ?: JSONObject.NULL)
            .put("render_thread_observed", state.mediaDisplay.renderThreadObserved)
    }

    private fun systemDelta(before: JSONObject, after: JSONObject): JSONObject {
        fun delta(key: String): Any {
            if (!before.has(key) || !after.has(key) || before.isNull(key) || after.isNull(key)) return JSONObject.NULL
            return after.optLong(key) - before.optLong(key)
        }
        fun doubleDelta(key: String): Any {
            if (!before.has(key) || !after.has(key) || before.isNull(key) || after.isNull(key)) return JSONObject.NULL
            return after.optDouble(key) - before.optDouble(key)
        }
        return JSONObject()
            .put("mem_available_delta_kb", delta("mem_available_kb"))
            .put("udp_in_delta", delta("udp_in_datagrams"))
            .put("udp_out_delta", delta("udp_out_datagrams"))
            .put("pgscan_direct_delta", delta("pgscan_direct"))
            .put("pgscan_kswapd_delta", delta("pgscan_kswapd"))
            .put("battery_percent_delta", delta("battery_percent"))
            .put("temperature_c_delta", doubleDelta("temperature_c"))
    }

    private fun List<Long>.averageOrNull(): Any {
        return if (isEmpty()) JSONObject.NULL else average()
    }

    private fun List<Long>.percentileOrNull(percentile: Int): Any {
        if (isEmpty()) return JSONObject.NULL
        val sorted = sorted()
        val idx = (((percentile / 100.0) * (sorted.size - 1))).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private data class TimedEvent(val event: EBPFEvent, val wallTimeMs: Long)
    private data class AppSample(val timeMs: Long, val packageName: String)
    private data class AppSegment(val packageName: String, val startMs: Long, var endMs: Long)

    private companion object {
        const val MAX_SESSION_SECONDS = 3600
        const val MAX_EVENTS = 24_000
        const val MAX_APP_SAMPLES = 12_000
        const val TIMELINE_WINDOW_APPS = 5
        const val COLLECTOR_ATTACH_TIMEOUT_MS = 10_000L
        const val EPOCH_SECONDS_THRESHOLD = 946684800.0
        const val EPOCH_NS_THRESHOLD = 946684800_000_000_000L
    }
}
