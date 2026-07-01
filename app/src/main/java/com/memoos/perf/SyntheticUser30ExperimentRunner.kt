package com.memoos.perf

import android.content.Context
import android.os.SystemClock
import com.memoos.action.ActionExecutor
import com.memoos.action.ActionResult
import com.memoos.action.AppIdMapping
import com.memoos.action.RecommendedApp
import com.memoos.device.DevicePaths
import com.memoos.device.EBPFCapabilityProbe
import com.memoos.device.RootShell
import com.memoos.ebpf.DeviceCollectorDeployer
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MaplePrediction
import com.memoos.maple.MapleScenario
import com.memoos.maple.MapleScenarioBuilder
import com.memoos.realtime.RealtimeWindow
import com.memoos.realtime.RealtimeWindowRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max

data class SyntheticUser30ExperimentResult(
    val report: JSONObject,
    val finalActions: List<ActionResult>,
)

class SyntheticUser30ExperimentRunner(private val context: Context) {
    fun run(): SyntheticUser30ExperimentResult {
        val startedAt = System.currentTimeMillis()
        val reportDir = File(context.getExternalFilesDir(null), "reports/synthetic_user_30").apply { mkdirs() }
        val timer = PipelineTimer("synthetic_user_30_real_ebpf")
        timer.measure("capability_probe") {
            val capability = EBPFCapabilityProbe.probe()
            require(capability.canRunRawCollector) {
                "raw eBPF collector is required for synthetic-user experiment; ${capability.notes.joinToString("; ")}"
            }
        }
        timer.measure("local_runtime_preflight") { requireLocalMapleRuntime() }
        timer.measure("deploy_collector") { DeviceCollectorDeployer(context).ensureRawCollectorExecutable() }

        val appPool = launchableApps()
        require(appPool.size >= 3) { "at least three launchable apps are required for prediction evaluation" }
        val samples = buildSamples(appPool)
        require(samples.size == SAMPLE_COUNT) { "experiment planner produced ${samples.size} samples, expected $SAMPLE_COUNT" }

        val sampleReports = JSONArray()
        val allActions = mutableListOf<ActionResult>()
        samples.forEachIndexed { index, sample ->
            val sampleReport = runSample(index + 1, sample, reportDir)
            sampleReports.put(sampleReport)
            val actionCount = sampleReport.optJSONArray("scheduler_actions")?.length() ?: 0
            allActions += ActionResult(
                "synthetic_user_30_sample",
                sample.id,
                "ok",
                "sample ${index + 1}/$SAMPLE_COUNT finished; persona=${sample.persona}; full_top3_hit=${sampleReport.optJSONObject("metrics_full")?.optBoolean("hit_top3")}; scheduler_actions=$actionCount",
            )
        }

        val summary = summarize(sampleReports)
        val report = JSONObject()
            .put("schema_version", "memo.synthetic_user_30.v1")
            .put("experiment_type", "3_personas_x_10_short_real_ebpf")
            .put("started_at_ms", startedAt)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("experiment_design", designJson())
            .put("personas", JSONArray(PERSONAS.map { it.toJson() }))
            .put("sample_count", samples.size)
            .put("summary", summary)
            .put("samples", sampleReports)
            .put(
                "report_files",
                JSONObject()
                    .put("device_report", "${DevicePaths.MEMO_PUBLIC_ROOT}/reports/synthetic_user_30/latest_synthetic_user_30_report.json")
                    .put("artifact_dir", "${DevicePaths.MEMO_PUBLIC_ROOT}/reports/synthetic_user_30"),
            )
            .put("interpretation", interpretation(summary))

        val reportFile = File(reportDir, "latest_synthetic_user_30_report.json")
        reportFile.writeText(report.toString(2))
        publishReport(reportDir, reportFile)
        allActions += ActionResult(
            "synthetic_user_30_experiment",
            "${DevicePaths.MEMO_PUBLIC_ROOT}/reports/synthetic_user_30/latest_synthetic_user_30_report.json",
            "ok",
            "finished 30 short real-eBPF prediction/action samples; full_top3_accuracy=${pct(summary.optJSONObject("full_memo")?.optDouble("hit_top3_rate", 0.0) ?: 0.0)}; scheduler_pressure_improvement=${pct(summary.optJSONObject("scheduler_ab")?.optDouble("pressure_score_improvement_pct", 0.0) ?: 0.0)}",
        )
        return SyntheticUser30ExperimentResult(report, allActions)
    }

    private fun runSample(index: Int, sample: SampleSpec, reportDir: File): JSONObject {
        val timer = PipelineTimer("synthetic_user_30_sample_${sample.id}")
        val driver = startSampleDriver(sample)
        val window = try {
            timer.measure("real_ebpf_window") { RealtimeWindowRunner(context).collectWindow(SAMPLE_WINDOW_MS) }
        } finally {
            stopDriver(driver)
        }
        val scenario = timer.measure("scenario_build") {
            MapleScenarioBuilder(context).build(
                events = window.events,
                state = window.afterState,
                scenarioId = "synthetic_user_30_${sample.id}_${window.startedAtMs}",
                description = "Short real-device experiment sample $index. Input app sequence is visible to MAPLE; future ground-truth apps are masked and used only for evaluation. Persona=${sample.persona}.",
                appTimeline = window.appTimeline,
                timelineWindows = window.timelineWindows,
                targetCategories = emptyList(),
                targetPackage = null,
                realtimeWindow = window.toJson(),
            )
        }
        val mapleStart = System.currentTimeMillis()
        val prediction = timer.measure("maple_inference") { MapleInferenceOrchestrator(context).predict(scenario) }
        val mapleMs = System.currentTimeMillis() - mapleStart
        require(prediction.available) {
            "MAPLE failed for ${sample.id}: ${prediction.error ?: "unknown"}"
        }
        val fullRecommendations = timer.measure("full_app_mapping") {
            AppIdMapping.resolveTopApps(
                context = context,
                predictedAppId = prediction.predictedAppId,
                stage1Categories = prediction.stage1.map { it.name },
                scenarioCategories = scenario.topCategories,
                foregroundPackage = window.afterState.process.foregroundPackage,
            )
        }
        val appOnlyRecommendations = baselineRecommendations(sample.inputApps.flatMap { it.categories }, window.afterState.process.foregroundPackage)
        val appOsRecommendations = baselineRecommendations(
            sample.inputApps.flatMap { it.categories } + categoriesFromState(window),
            window.afterState.process.foregroundPackage,
        )
        val actionStart = SystemClock.elapsedRealtime()
        val actions = timer.measure("scheduler_actions") {
            ActionExecutor(context).executeRealtimePlan(
                plan = prediction.schedulerPlan,
                schedulerBits = prediction.schedulerBits,
                scenario = scenario,
                prediction = prediction,
                recommendations = fullRecommendations,
                state = window.afterState,
                latencyBeforeActionsMs = mapleMs,
            )
        }
        val actionMs = SystemClock.elapsedRealtime() - actionStart
        val schedulerAb = timer.measure("scheduler_ab_measurement") {
            measureSchedulerEffect(sample.groundTruthApps.first(), fullRecommendations, scenario, prediction, window, actions)
        }
        val artifacts = writeSampleArtifacts(index, sample, reportDir, scenario, prediction, window)
        return JSONObject()
            .put("index", index)
            .put("sample_id", sample.id)
            .put("persona", sample.persona)
            .put("input_sequence", JSONArray(sample.inputApps.map { it.toJson() }))
            .put("ground_truth_top3", JSONArray(sample.groundTruthApps.map { it.toJson() }))
            .put("window", window.toJson())
            .put("raw_trace_path", window.rawTracePath)
            .put("app_samples_path", window.appSamplesPath)
            .put("observed_app_labels", JSONArray(window.appTimeline.map { it.label }.distinct()))
            .put("scenario_top_categories", JSONArray(scenario.topCategories))
            .put("maple_duration_ms", mapleMs)
            .put("maple_backend", prediction.backend)
            .put("maple_predicted_app_id", prediction.predictedAppId)
            .put("maple_stage1", JSONArray(prediction.stage1.map { "${it.name}:${it.probability}" }))
            .put("scheduler_bits", prediction.schedulerBits)
            .put("full_memo_top3", JSONArray(fullRecommendations.map { it.toJson() }))
            .put("app_os_top3", JSONArray(appOsRecommendations.map { it.toJson() }))
            .put("app_only_top3", JSONArray(appOnlyRecommendations.map { it.toJson() }))
            .put("metrics_full", metrics(fullRecommendations, sample.groundTruthApps).toJson())
            .put("metrics_app_os", metrics(appOsRecommendations, sample.groundTruthApps).toJson())
            .put("metrics_app_only", metrics(appOnlyRecommendations, sample.groundTruthApps).toJson())
            .put("scheduler_actions", JSONArray(actions.map { it.toJson() }))
            .put("scheduler_action_duration_ms", actionMs)
            .put("scheduler_ab", schedulerAb)
            .put("latency", timer.snapshot(window.events.size, mapleTimedOut = false).toJson())
            .put("artifacts", artifacts)
    }

    private fun measureSchedulerEffect(
        groundTruthTop1: ExperimentApp,
        recommendations: List<RecommendedApp>,
        scenario: MapleScenario,
        prediction: MaplePrediction,
        window: RealtimeWindow,
        alreadyExecutedActions: List<ActionResult>,
    ): JSONObject {
        val metrics = DevicePressureMetrics(context)
        val baseline = measureLaunchWorkload("scheduler_off", groundTruthTop1, recommendations, metrics, executeActions = null)
        val optimized = measureLaunchWorkload(
            "scheduler_on",
            groundTruthTop1,
            recommendations,
            metrics,
            executeActions = {
                ActionExecutor(context).executeRealtimePlan(
                    plan = prediction.schedulerPlan,
                    schedulerBits = prediction.schedulerBits,
                    scenario = scenario,
                    prediction = prediction,
                    recommendations = recommendations,
                    state = window.afterState,
                    latencyBeforeActionsMs = 0L,
                )
            },
        )
        return JSONObject()
            .put("design", "A/B within the same sample. OFF force-stops candidates and launches the ground-truth next app without MEMO actions. ON force-stops candidates, runs MAPLE-selected scheduler actions, then launches the same ground-truth next app.")
            .put("ground_truth_top1", groundTruthTop1.toJson())
            .put("already_executed_actions_before_ab", JSONArray(alreadyExecutedActions.map { it.toJson() }))
            .put("off", baseline)
            .put("on", optimized)
            .put("improvement", improvementJson(baseline, optimized))
    }

    private fun measureLaunchWorkload(
        mode: String,
        target: ExperimentApp,
        recommendations: List<RecommendedApp>,
        metrics: DevicePressureMetrics,
        executeActions: (() -> List<ActionResult>)?,
    ): JSONObject {
        val packagesToReset = (recommendations.map { it.packageName } + target.packageName).distinct()
        packagesToReset.forEach { RootShell.run("am force-stop '$it' >/dev/null 2>&1", requireRoot = true, timeoutMs = 3_000L) }
        RootShell.run("input keyevent KEYCODE_HOME >/dev/null 2>&1", requireRoot = true, timeoutMs = 2_000L)
        Thread.sleep(650L)
        val actionStart = SystemClock.elapsedRealtime()
        val actionResults = executeActions?.invoke().orEmpty()
        val actionDurationMs = SystemClock.elapsedRealtime() - actionStart
        Thread.sleep(450L)
        val before = metrics.snapshot()
        val launchText = RootShell.run(
            "am start -W -n '${target.component}'",
            requireRoot = true,
            timeoutMs = 10_000L,
        ).let { it.stdout + "\n" + it.stderr }
        Thread.sleep(1200L)
        val after = metrics.snapshot()
        RootShell.run("input keyevent KEYCODE_HOME >/dev/null 2>&1", requireRoot = true, timeoutMs = 2_000L)
        return JSONObject()
            .put("mode", mode)
            .put("target_package", target.packageName)
            .put("target_label", target.label)
            .put("action_duration_ms", actionDurationMs)
            .put("actions", JSONArray(actionResults.map { it.toJson() }))
            .put("launch", metrics.parseLaunchMetrics(launchText).toJson())
            .put("pressure_before", before.toJson())
            .put("pressure_after", after.toJson())
            .put("pressure_delta", metrics.delta(before, after).toJson())
    }

    private fun improvementJson(off: JSONObject, on: JSONObject): JSONObject {
        fun launchMs(root: JSONObject, key: String): Long? {
            val value = root.optJSONObject("launch")?.opt(key)
            return when (value) {
                is Number -> value.toLong()
                else -> null
            }
        }
        fun pressure(root: JSONObject): Double? {
            val value = root.optJSONObject("pressure_delta")?.opt("pressure_score_lower_is_better")
            return when (value) {
                is Number -> value.toDouble()
                else -> null
            }
        }
        return JSONObject()
            .putNullable("total_time_improvement_pct", improvementPct(launchMs(off, "total_time_ms"), launchMs(on, "total_time_ms")))
            .putNullable("wait_time_improvement_pct", improvementPct(launchMs(off, "wait_time_ms"), launchMs(on, "wait_time_ms")))
            .putNullable("pressure_score_improvement_pct", improvementPct(pressure(off), pressure(on)))
            .put("lower_is_better", JSONArray(listOf("total_time_ms", "wait_time_ms", "pressure_score_lower_is_better")))
    }

    private fun improvementPct(off: Long?, on: Long?): Double? = if (off != null && on != null && off > 0) (off - on) * 100.0 / off else null
    private fun improvementPct(off: Double?, on: Double?): Double? = if (off != null && on != null && off > 0.0) (off - on) * 100.0 / off else null

    private fun baselineRecommendations(categories: List<String>, foregroundPackage: String?): List<RecommendedApp> {
        val distinct = categories.filter { it.isNotBlank() }.distinct()
        return AppIdMapping.resolveTopApps(
            context = context,
            predictedAppId = -1,
            stage1Categories = distinct,
            scenarioCategories = distinct,
            foregroundPackage = foregroundPackage,
        )
    }

    private fun categoriesFromState(window: RealtimeWindow): List<String> {
        val categories = linkedSetOf<String>()
        if ((window.afterState.network.udpInDatagrams ?: 0L) + (window.afterState.network.udpOutDatagrams ?: 0L) > 0L) categories += "Network IO"
        if (window.afterState.mediaDisplay.cameraServerPid != null) categories += "Camera Service"
        if (window.afterState.mediaDisplay.mediaCodecPid != null) categories += "Media Codec"
        if (window.afterState.mediaDisplay.surfaceFlingerPid != null || window.afterState.mediaDisplay.renderThreadObserved) categories += "Display Composition"
        if (window.afterState.memory.pressureLevel != "normal") categories += "Memory Management"
        if (window.afterState.battery.thermalRisk != "normal") categories += "Power/Thermal Management"
        window.timelineWindows.flatMap { it.compressedEbpf }.take(4).forEach { signal ->
            when (signal.eventType) {
                "MEMO_SENDTO", "MEMO_RECVFROM" -> categories += "Network IO"
                "MEMO_BINDER" -> categories += "Android Service IPC"
                "MEMO_INPUT" -> categories += "Input Interaction"
                "MEMO_MEMORY", "MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "MEMO_KSWAPD_WAKE" -> categories += "Memory Management"
            }
        }
        return categories.toList()
    }

    private fun metrics(predicted: List<RecommendedApp>, truth: List<ExperimentApp>): PredictionMetrics {
        val predPkgs = predicted.map { it.packageName }.distinct().take(3)
        val truthPkgs = truth.map { it.packageName }.distinct().take(3)
        val intersection = predPkgs.intersect(truthPkgs.toSet()).size
        val precision = intersection / max(1.0, predPkgs.size.toDouble())
        val recall = intersection / max(1.0, truthPkgs.size.toDouble())
        val f1 = if (precision + recall > 0.0) 2.0 * precision * recall / (precision + recall) else 0.0
        val rank = truthPkgs.firstOrNull()?.let { gt -> predPkgs.indexOf(gt).takeIf { it >= 0 }?.plus(1) }
        return PredictionMetrics(
            hitTop1 = predPkgs.firstOrNull() == truthPkgs.firstOrNull(),
            hitTop3 = truthPkgs.firstOrNull()?.let { it in predPkgs } ?: false,
            precisionAt3 = precision,
            recallAt3 = recall,
            f1At3 = f1,
            mrr = rank?.let { 1.0 / it } ?: 0.0,
            exactOrderTop3 = predPkgs == truthPkgs,
        )
    }

    private fun summarize(samples: JSONArray): JSONObject {
        val full = mutableListOf<PredictionMetrics>()
        val appOs = mutableListOf<PredictionMetrics>()
        val appOnly = mutableListOf<PredictionMetrics>()
        val scheduler = mutableListOf<JSONObject>()
        val mapleDurations = mutableListOf<Long>()
        for (i in 0 until samples.length()) {
            val obj = samples.optJSONObject(i) ?: continue
            full += PredictionMetrics.fromJson(obj.optJSONObject("metrics_full"))
            appOs += PredictionMetrics.fromJson(obj.optJSONObject("metrics_app_os"))
            appOnly += PredictionMetrics.fromJson(obj.optJSONObject("metrics_app_only"))
            scheduler += obj.optJSONObject("scheduler_ab")?.optJSONObject("improvement") ?: JSONObject()
            mapleDurations += obj.optLong("maple_duration_ms", 0L)
        }
        return JSONObject()
            .put("full_memo", aggregateMetrics(full))
            .put("app_plus_os_baseline", aggregateMetrics(appOs))
            .put("app_only_baseline", aggregateMetrics(appOnly))
            .put("ablation_lift", JSONObject()
                .put("full_vs_app_only_f1_pct_points", pctPoints(avg(full) { it.f1At3 } - avg(appOnly) { it.f1At3 }))
                .put("full_vs_app_os_f1_pct_points", pctPoints(avg(full) { it.f1At3 } - avg(appOs) { it.f1At3 }))
                .put("full_vs_app_only_hit3_pct_points", pctPoints(avg(full) { if (it.hitTop3) 1.0 else 0.0 } - avg(appOnly) { if (it.hitTop3) 1.0 else 0.0 }))
                .put("full_vs_app_os_hit3_pct_points", pctPoints(avg(full) { if (it.hitTop3) 1.0 else 0.0 } - avg(appOs) { if (it.hitTop3) 1.0 else 0.0 })))
            .put("scheduler_ab", aggregateScheduler(scheduler))
            .put("latency", JSONObject()
                .put("avg_maple_ms", averageLongs(mapleDurations.filter { it > 0L }))
                .put("p50_maple_ms", percentile(mapleDurations, 50.0))
                .put("p90_maple_ms", percentile(mapleDurations, 90.0)))
    }

    private fun aggregateMetrics(values: List<PredictionMetrics>): JSONObject {
        return JSONObject()
            .put("n", values.size)
            .put("top1_accuracy", avg(values) { if (it.hitTop1) 1.0 else 0.0 })
            .put("hit_top3_rate", avg(values) { if (it.hitTop3) 1.0 else 0.0 })
            .put("precision_at_3", avg(values) { it.precisionAt3 })
            .put("recall_at_3", avg(values) { it.recallAt3 })
            .put("f1_at_3", avg(values) { it.f1At3 })
            .put("mrr", avg(values) { it.mrr })
            .put("exact_order_top3_rate", avg(values) { if (it.exactOrderTop3) 1.0 else 0.0 })
    }

    private fun aggregateScheduler(values: List<JSONObject>): JSONObject {
        fun avgKey(key: String): Double {
            val nums = values.mapNotNull { obj ->
                val value = obj.opt(key)
                when (value) {
                    is Number -> value.toDouble()
                    else -> null
                }
            }
            return averageDoubles(nums)
        }
        return JSONObject()
            .put("n", values.size)
            .put("total_time_improvement_pct", avgKey("total_time_improvement_pct"))
            .put("wait_time_improvement_pct", avgKey("wait_time_improvement_pct"))
            .put("pressure_score_improvement_pct", avgKey("pressure_score_improvement_pct"))
    }

    private fun writeSampleArtifacts(
        index: Int,
        sample: SampleSpec,
        reportDir: File,
        scenario: MapleScenario,
        prediction: MaplePrediction,
        window: RealtimeWindow,
    ): JSONObject {
        val prefix = "${index.toString().padStart(2, '0')}_${sample.id}"
        val scenarioFile = File(reportDir, "${prefix}_scenario.json")
        val contextFile = File(reportDir, "${prefix}_context.json")
        val mapleFile = File(reportDir, "${prefix}_maple_output.txt")
        val windowFile = File(reportDir, "${prefix}_window.json")
        scenarioFile.writeText(scenario.scenarioJson)
        contextFile.writeText(scenario.contextJson)
        windowFile.writeText(window.toJson().toString(2))
        mapleFile.writeText(
            buildString {
                appendLine("sample=${sample.id}")
                appendLine("persona=${sample.persona}")
                appendLine("backend=${prediction.backend}")
                appendLine("available=${prediction.available}")
                appendLine("predicted_app_id=${prediction.predictedAppId}")
                appendLine("scheduler_bits=${prediction.schedulerBits}")
                appendLine("stage1=${prediction.stage1.joinToString { "${it.name}:${it.probability}" }}")
                appendLine("raw_stage1:")
                appendLine(prediction.rawStage1)
                appendLine("raw_stage2:")
                appendLine(prediction.rawStage2)
            },
        )
        return JSONObject()
            .put("scenario_prompt_input", "$PUBLIC_ARTIFACT_DIR/${scenarioFile.name}")
            .put("context_json", "$PUBLIC_ARTIFACT_DIR/${contextFile.name}")
            .put("maple_output", "$PUBLIC_ARTIFACT_DIR/${mapleFile.name}")
            .put("window_summary", "$PUBLIC_ARTIFACT_DIR/${windowFile.name}")
    }

    private fun publishReport(reportDir: File, reportFile: File) {
        RootShell.run(
            "mkdir -p '$PUBLIC_ARTIFACT_DIR'; cp '${reportDir.absolutePath}'/* '$PUBLIC_ARTIFACT_DIR'/; chmod 0644 '$PUBLIC_ARTIFACT_DIR'/*",
            requireRoot = true,
            timeoutMs = 20_000L,
        )
        RootShell.run(
            "cp '${reportFile.absolutePath}' '${DevicePaths.MEMO_PUBLIC_ROOT}/reports/latest_synthetic_user_30_report.json'; chmod 0644 '${DevicePaths.MEMO_PUBLIC_ROOT}/reports/latest_synthetic_user_30_report.json'",
            requireRoot = true,
            timeoutMs = 5_000L,
        )
    }

    private fun startSampleDriver(sample: SampleSpec): DriverHandle {
        val flagPath = "${DevicePaths.LOG_DIR}/synthetic_user_30_${sample.id}_${System.currentTimeMillis()}.active"
        val script = File(context.filesDir, "synthetic_user_30_${sample.id}_${System.currentTimeMillis()}.sh")
        script.writeText(sampleScript(flagPath, sample))
        RootShell.run("mkdir -p '${DevicePaths.LOG_DIR}'; : > '$flagPath'; chmod 0700 '${script.absolutePath}'", requireRoot = true, timeoutMs = 3_000L)
        val start = RootShell.run("nohup sh '${script.absolutePath}' >/dev/null 2>&1 & echo \$!", requireRoot = true, timeoutMs = 3_000L)
        return DriverHandle(flagPath, start.stdout.trim())
    }

    private fun stopDriver(driver: DriverHandle) {
        RootShell.run("rm -f '${driver.flagPath}'; input keyevent KEYCODE_HOME >/dev/null 2>&1", requireRoot = true, timeoutMs = 3_000L)
    }

    private fun sampleScript(flagPath: String, sample: SampleSpec): String {
        val body = sample.inputApps.joinToString("\n") { app ->
            """
                if [ ! -f '$flagPath' ]; then exit 0; fi
                am start -W -n ${shellQuote(app.component)} >/dev/null 2>&1
                sleep 1
                input swipe 540 1600 540 560 350 >/dev/null 2>&1
                sleep 1
                input tap 540 980 >/dev/null 2>&1
                sleep 1
                input keyevent KEYCODE_HOME >/dev/null 2>&1
                sleep 1
            """.trimIndent()
        }
        return """
            #!/system/bin/sh
            sleep 3
            end=${'$'}(( ${'$'}(date +%s) + $SAMPLE_DURATION_SEC ))
            while [ -f '$flagPath' ] && [ ${'$'}(date +%s) -lt ${'$'}end ]; do
            $body
            done
            rm -f '$flagPath'
        """.trimIndent() + "\n"
    }

    private fun buildSamples(apps: List<ExperimentApp>): List<SampleSpec> {
        val byCategory = apps.groupBy { primaryCategory(it) }
        fun pick(categories: List<String>, count: Int): List<ExperimentApp> {
            val selected = categories.flatMap { byCategory[it].orEmpty() }.distinctBy { it.packageName }
            return selected.ifEmpty { apps }.cycleTake(count)
        }
        val socialInputs = pick(listOf("Communication", "Network IO"), 4)
        val socialTruth = pick(listOf("Camera Service", "Communication", "Network IO"), 3)
        val workInputs = pick(listOf("Network IO", "App Process Runtime", "Communication"), 4)
        val workTruth = pick(listOf("Network IO", "Communication", "Media Codec"), 3)
        val mediaInputs = pick(listOf("Media Codec", "Network IO", "Display Composition"), 4)
        val mediaTruth = pick(listOf("Communication", "Camera Service", "Media Codec"), 3)
        val specs = mutableListOf<SampleSpec>()
        for (i in 1..10) {
            specs += SampleSpec(
                id = "social_$i",
                persona = "social_sharing",
                inputApps = rotate(socialInputs, i).take(3),
                groundTruthApps = rotate(socialTruth, i + 1).take(3),
            )
            specs += SampleSpec(
                id = "productivity_$i",
                persona = "productivity_learning",
                inputApps = rotate(workInputs, i + 2).take(3),
                groundTruthApps = rotate(workTruth, i).take(3),
            )
            specs += SampleSpec(
                id = "media_$i",
                persona = "media_browsing",
                inputApps = rotate(mediaInputs, i + 3).take(3),
                groundTruthApps = rotate(mediaTruth, i + 2).take(3),
            )
        }
        return specs.sortedWith(compareBy<SampleSpec> { it.persona }.thenBy { it.id }).take(SAMPLE_COUNT)
    }

    private fun launchableApps(): List<ExperimentApp> {
        return AppIdMapping.scanInstalledApps(context)
            .mapNotNull { profile ->
                val component = context.packageManager.getLaunchIntentForPackage(profile.packageName)
                    ?.component
                    ?.flattenToShortString()
                    ?: return@mapNotNull null
                ExperimentApp(
                    packageName = profile.packageName,
                    label = profile.label,
                    component = component,
                    categories = (profile.inferredCategories + AppIdMapping.categoryForPackage(profile.packageName)).distinct(),
                    userInstalled = profile.isUserInstalled,
                )
            }
            .sortedWith(
                compareByDescending<ExperimentApp> { it.userInstalled }
                    .thenBy { it.label.lowercase(Locale.US) },
            )
    }

    private fun requireLocalMapleRuntime() {
        val required = listOf(DevicePaths.MAPLE_DEMO, DevicePaths.MAPLE_ENGINE_SO, DevicePaths.MAPLE_CXX_SHARED, DevicePaths.DEFAULT_MODEL)
        val missing = required.filterNot { RootShell.run("test -r '$it'", requireRoot = true, timeoutMs = 3_000L).ok }
        require(missing.isEmpty()) { "phone-local MAPLE runtime is incomplete: ${missing.joinToString()}" }
    }

    private fun designJson(): JSONObject {
        return JSONObject()
            .put("goal", "Evaluate whether app+OS+eBPF evidence improves next Top-3 app prediction and whether MAPLE scheduler actions improve launch/pressure metrics.")
            .put("ground_truth", "Each sample has a hidden next-app Top-3 generated by the experiment persona. MAPLE only receives the observed input app sequence plus naturally collected eBPF/system evidence; ground truth is used only after inference for scoring.")
            .put("real_device_operation", "For every sample, MEMO actually launches the input apps on the rooted phone, collects raw libbpf eBPF and foreground app samples, compresses them into timeline windows, runs MAPLE, then executes scheduler_bits.")
            .put("prediction_metrics", JSONArray(listOf("top1_accuracy", "hit_top3_rate", "precision_at_3", "recall_at_3", "f1_at_3", "mrr", "exact_order_top3_rate")))
            .put("ablation_configs", JSONArray(listOf("app_only_baseline", "app_plus_os_baseline", "full_memo_app_plus_os_plus_ebpf")))
            .put("scheduler_metrics", JSONArray(listOf("TotalTime", "WaitTime", "pressure_score_lower_is_better", "MemAvailable delta", "reclaim delta", "CPU busy", "iowait")))
            .put("sample_window_ms", SAMPLE_WINDOW_MS)
            .put("sample_count", SAMPLE_COUNT)
    }

    private fun interpretation(summary: JSONObject): String {
        val full = summary.optJSONObject("full_memo")
        val appOnly = summary.optJSONObject("app_only_baseline")
        val sched = summary.optJSONObject("scheduler_ab")
        return "Full MEMO Top-3 hit rate=${pct(full?.optDouble("hit_top3_rate", 0.0) ?: 0.0)}, F1@3=${pct(full?.optDouble("f1_at_3", 0.0) ?: 0.0)}. App-only hit rate=${pct(appOnly?.optDouble("hit_top3_rate", 0.0) ?: 0.0)}. Scheduler A/B average pressure-score improvement=${pct((sched?.optDouble("pressure_score_improvement_pct", 0.0) ?: 0.0) / 100.0)}."
    }

    private fun primaryCategory(app: ExperimentApp): String {
        val cats = app.categories
        return when {
            "Communication" in cats -> "Communication"
            "Camera Service" in cats -> "Camera Service"
            "Media Codec" in cats -> "Media Codec"
            "Network IO" in cats -> "Network IO"
            "Display Composition" in cats -> "Display Composition"
            else -> cats.firstOrNull() ?: "App Process Runtime"
        }
    }

    private fun <T> List<T>.cycleTake(count: Int): List<T> {
        if (isEmpty()) return emptyList()
        return (0 until count).map { this[it % size] }
    }

    private fun <T> rotate(values: List<T>, offset: Int): List<T> {
        if (values.isEmpty()) return emptyList()
        val shift = ((offset % values.size) + values.size) % values.size
        return values.drop(shift) + values.take(shift)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun pct(value: Double): String = String.format(Locale.US, "%.2f%%", value * 100.0)
    private fun pctPoints(value: Double): Double = value * 100.0

    private fun <T> avg(values: List<T>, selector: (T) -> Double): Double = averageDoubles(values.map(selector))
    private fun averageDoubles(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.average()
    private fun averageLongs(values: List<Long>): Double = if (values.isEmpty()) 0.0 else values.average()
    private fun percentile(values: List<Long>, p: Double): Long {
        val sorted = values.filter { it > 0L }.sorted()
        if (sorted.isEmpty()) return 0L
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun RecommendedApp.toJson(): JSONObject {
        return JSONObject()
            .put("package_name", packageName)
            .put("label", label)
            .put("category", category)
            .put("confidence", confidence)
            .put("reason", reason)
    }

    private fun ActionResult.toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("target", target)
            .put("status", status)
            .put("detail", detail)
            .put("duration_ms", durationMs)
            .put("timestamp_ms", timestampMs)
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        put(key, value ?: JSONObject.NULL)
        return this
    }

    private data class DriverHandle(val flagPath: String, val pid: String)

    private data class SampleSpec(
        val id: String,
        val persona: String,
        val inputApps: List<ExperimentApp>,
        val groundTruthApps: List<ExperimentApp>,
    )

    private data class ExperimentApp(
        val packageName: String,
        val label: String,
        val component: String,
        val categories: List<String>,
        val userInstalled: Boolean,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("package_name", packageName)
                .put("label", label)
                .put("component", component)
                .put("categories", JSONArray(categories))
                .put("user_installed", userInstalled)
        }
    }

    private data class PredictionMetrics(
        val hitTop1: Boolean,
        val hitTop3: Boolean,
        val precisionAt3: Double,
        val recallAt3: Double,
        val f1At3: Double,
        val mrr: Double,
        val exactOrderTop3: Boolean,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("hit_top1", hitTop1)
                .put("hit_top3", hitTop3)
                .put("precision_at_3", precisionAt3)
                .put("recall_at_3", recallAt3)
                .put("f1_at_3", f1At3)
                .put("mrr", mrr)
                .put("exact_order_top3", exactOrderTop3)
        }

        companion object {
            fun fromJson(obj: JSONObject?): PredictionMetrics {
                if (obj == null) return PredictionMetrics(false, false, 0.0, 0.0, 0.0, 0.0, false)
                return PredictionMetrics(
                    hitTop1 = obj.optBoolean("hit_top1", false),
                    hitTop3 = obj.optBoolean("hit_top3", false),
                    precisionAt3 = obj.optDouble("precision_at_3", 0.0),
                    recallAt3 = obj.optDouble("recall_at_3", 0.0),
                    f1At3 = obj.optDouble("f1_at_3", 0.0),
                    mrr = obj.optDouble("mrr", 0.0),
                    exactOrderTop3 = obj.optBoolean("exact_order_top3", false),
                )
            }
        }
    }

    private data class Persona(val id: String, val description: String) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("description", description)
    }

    private companion object {
        const val SAMPLE_COUNT = 30
        const val SAMPLE_WINDOW_MS = 14_000L
        const val SAMPLE_DURATION_SEC = 13
        const val PUBLIC_ARTIFACT_DIR = "${DevicePaths.MEMO_PUBLIC_ROOT}/reports/synthetic_user_30"
        val PERSONAS = listOf(
            Persona("social_sharing", "communication-heavy behavior; after chat/network apps, camera or sharing apps are plausible next actions"),
            Persona("productivity_learning", "browser/productivity behavior; after reading/searching, communication or media follow-up is plausible"),
            Persona("media_browsing", "media/display behavior; after browsing or playback, communication/camera/media follow-up is plausible"),
        )
    }
}
