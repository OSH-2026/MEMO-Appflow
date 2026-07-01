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
import com.memoos.ebpf.EBPFTraceParser
import com.memoos.ebpf.RealUserExperimentPlan
import com.memoos.ebpf.RealUserExperimentPlanner
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MaplePrediction
import com.memoos.maple.MapleScenario
import com.memoos.maple.MapleScenarioBuilder
import com.memoos.state.SystemStateCollector
import com.memoos.state.SystemStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WorkloadPressureResult(
    val mode: String,
    val condition: String,
    val planId: String,
    val title: String,
    val targetPackage: String,
    val targetLabel: String,
    val launch: LaunchMetrics,
    val gfx: GfxMetrics,
    val before: PressureSnapshot,
    val after: PressureSnapshot,
    val delta: PressureDelta,
    val commandOk: Boolean,
    val commandSummary: String,
    val memoPreparation: JSONObject?,
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
            .put("mode", mode)
            .put("condition", condition)
            .put("plan_id", planId)
            .put("title", title)
            .put("target_package", targetPackage)
            .put("target_label", targetLabel)
            .put("launch", launch.toJson())
            .put("gfx", gfx.toJson())
            .put("before", before.toJson())
            .put("after", after.toJson())
            .put("delta", delta.toJson())
            .put("command_ok", commandOk)
            .put("command_summary", commandSummary)
        obj.put("memo_preparation", memoPreparation ?: JSONObject.NULL)
        return obj
    }
}

class PressureExperimentRunner(private val context: Context) {
    private val metrics = DevicePressureMetrics(context)

    fun run(): JSONObject {
        val startedAt = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()
        writeProgress("start", "system scheduler performance experiment started")
        hardPreflight()

        val contextPlan = choosePlans().first()
        val condition = CONDITION_NORMAL

        writeProgress("maple_target_selection", "collect real eBPF and run MAPLE once to select the shared target app")
        prepareCondition(condition, contextPlan.targetPackage)
        val setup = runMapleSetup(contextPlan, condition)
        val top1 = setup.recommendations.firstOrNull()
            ?: error("MAPLE produced no launchable Top-1 recommendation")
        val measuredPlan = measuredPlanFor(top1, contextPlan)
        writeProgress("target_selected", "target=${top1.label}/${top1.packageName}; scheduler_bits=${setup.prediction.schedulerBits}")

        writeProgress("baseline_measure", "measure selected target without Stage 3 scheduler actions")
        prepareCondition(condition, measuredPlan.targetPackage)
        val baseline = measureWorkload(
            plan = measuredPlan,
            condition = condition,
            mode = MODE_BASELINE,
            repeatIndex = 1,
            memoPreparation = baselinePreparation(measuredPlan, condition, setup),
            forceStopBeforeLaunch = true,
        )
        resetToHome()

        writeProgress("stage3_prepare", "force-stop target, execute scheduler_bits, then measure same target")
        prepareCondition(condition, measuredPlan.targetPackage)
        forceStop(measuredPlan.targetPackage)
        val actions = executeSchedulerActions(setup)
        val schedulerPreparation = setup.report
            .put("type", "same_maple_target_with_stage3_scheduler_actions")
            .put("scheduler_executed", true)
            .put("actions", JSONArray(actions.map { it.toJson() }))
            .put("stage3_target_package", measuredPlan.targetPackage)
            .put("stage3_target_label", measuredPlan.targetLabel)
        writeProgress("scheduler_done", "actions=${actions.size}; target=${measuredPlan.targetPackage}")

        val scheduler = measureWorkload(
            plan = measuredPlan,
            condition = condition,
            mode = MODE_STAGE123_SCHEDULER,
            repeatIndex = 1,
            memoPreparation = schedulerPreparation,
            forceStopBeforeLaunch = false,
        )
        resetToHome()

        val workloads = JSONArray()
            .put(baseline.toJson())
            .put(scheduler.toJson())
        val comparisons = JSONArray()
            .put(compare(baseline, scheduler, "stage3_scheduler_vs_no_scheduler"))

        val report = JSONObject()
            .put("schema_version", "memo.system_scheduler_performance.v2")
            .put("experiment_type", "system_scheduler_performance")
            .put("started_at_ms", startedAt)
            .put("duration_ms", SystemClock.elapsedRealtime() - startElapsed)
            .put("device", deviceInfo())
            .put("metric_definitions", metricDefinitions())
            .put("workload_count", 1)
            .put("repeats_per_workload", 1)
            .put("conditions", JSONArray(listOf(condition)))
            .put("design", experimentDesign(contextPlan, measuredPlan))
            .put("workloads", workloads)
            .put("comparisons", comparisons)
            .put("summary", summarize(comparisons))

        persist(report)
        writeProgress("done", "report=${DevicePaths.MEMO_PUBLIC_ROOT}/pressure/latest_pressure_experiment.json")
        return report
    }

    private fun choosePlans(): List<RealUserExperimentPlan> {
        val appProfiles = AppIdMapping.scanInstalledApps(context)
        val kinds = listOf(
            RealUserExperimentPlanner.KIND_SCROLL,
            RealUserExperimentPlanner.KIND_MEDIA,
            RealUserExperimentPlanner.KIND_CAMERA,
            RealUserExperimentPlanner.KIND_COMMUNICATION,
        )
        val categoryPlans = kinds
            .map { RealUserExperimentPlanner.plan(context, it) }
            .filter { !it.targetPackage.isNullOrBlank() }
            .filter { plan -> plan.id == RealUserExperimentPlanner.KIND_SCROLL || targetMatchesDesiredCategory(plan, appProfiles) }
            .distinctBy { it.targetPackage }
        val genericPlans = appProfiles
            .filter { app -> app.packageName !in categoryPlans.mapNotNull { it.targetPackage } }
            .sortedWith(compareByDescending<AppIdMapping.InstalledAppProfile> { it.isUserInstalled }.thenBy { it.label.lowercase() })
            .take((MAX_WORKLOADS - categoryPlans.size).coerceAtLeast(0))
            .map { app -> genericRealAppPlan(app) }
        val plans = (categoryPlans + genericPlans).take(MAX_WORKLOADS)
        require(plans.isNotEmpty()) {
            "no launchable target app was found for a system scheduler performance experiment"
        }
        return plans
    }

    private fun targetMatchesDesiredCategory(
        plan: RealUserExperimentPlan,
        apps: List<AppIdMapping.InstalledAppProfile>,
    ): Boolean {
        val profile = apps.firstOrNull { it.packageName == plan.targetPackage } ?: return false
        val actual = profile.roleCategories + profile.intentCapabilities + profile.inferredCategories + profile.lexicalHints
        if (plan.id == RealUserExperimentPlanner.KIND_CAMERA) {
            return actual.any { it == "Camera Service" }
        }
        return plan.desiredCategories.any { desired -> desired in actual }
    }

    private fun genericRealAppPlan(app: AppIdMapping.InstalledAppProfile): RealUserExperimentPlan {
        return RealUserExperimentPlan(
            id = "generic_${app.packageName.replace('.', '_')}",
            title = "Real launchable app workload: ${app.label}",
            description = "Launch a real installed app selected from PackageManager and measure pressure.",
            targetPackage = app.packageName,
            targetLabel = app.label,
            desiredCategories = app.inferredCategories.take(3).ifEmpty { listOf("App Process Runtime") },
            interactionCommands = listOf(
                "sleep 1",
                "input tap 540 960 >/dev/null 2>&1",
                "sleep 1",
                "input swipe 540 1500 540 600 600 >/dev/null 2>&1",
                "sleep 1",
                "input keyevent KEYCODE_BACK >/dev/null 2>&1",
            ),
        )
    }

    private fun runMapleSetup(plan: RealUserExperimentPlan, condition: String): MemoPreparationResult {
        val stageStarted = SystemClock.elapsedRealtime()
        val capability = EBPFCapabilityProbe.probe()
        require(capability.canRunRawCollector) {
            "raw eBPF collector is required; ${capability.notes.joinToString("; ")}"
        }
        DeviceCollectorDeployer(context).ensureRawCollectorExecutable()

        RootShell.run("mkdir -p '${DevicePaths.LOG_DIR}' '${DevicePaths.SCENARIO_DIR}'", requireRoot = true, timeoutMs = 3_000L)
        val tracePath = "${DevicePaths.LOG_DIR}/pressure_${plan.id}_${System.currentTimeMillis()}.trace"
        val collector = capability.rawCollectorPath ?: DevicePaths.RAW_COLLECTOR
        RootShell.run(
            "rm -f '$tracePath'; ($collector --duration-sec $PRESSURE_EVIDENCE_SECONDS --max-events $MAX_EVENTS --output '$tracePath' 2>&1) & echo \$!",
            requireRoot = true,
            timeoutMs = 3_000L,
        )
        waitForCollector(tracePath)
        val interactionResult = plan.execute(context)
        Thread.sleep(2_000L)
        RootShell.run("pkill -f memo_libbpf_collector 2>/dev/null", requireRoot = true, timeoutMs = 3_000L)

        val raw = RootShell.run("sed -n '/^MEMO/p' '$tracePath' 2>/dev/null | head -n $MAX_EVENTS", requireRoot = true, timeoutMs = 8_000L).stdout
        val events = EBPFTraceParser.parseLines(raw.lineSequence()).toList()
        require(events.isNotEmpty()) {
            val head = RootShell.run("head -n 20 '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 3_000L).stdout
            "pressure setup produced zero eBPF events; trace=$tracePath; head=${head.take(300)}"
        }

        val state = SystemStateCollector(context).collect()
        val scenario = MapleScenarioBuilder(context).build(
            events = events,
            state = state,
            scenarioId = "pressure_${plan.id}",
            description = "System scheduler performance setup: MEMO observes a real Android app action, runs MAPLE Stage 1/2/3, selects a real Top-1 app, and then compares no-scheduler vs Stage 3 scheduler actions on that same app.",
            targetPackage = plan.targetPackage,
            targetCategories = plan.desiredCategories,
        )
        val scenarioFile = File(context.getExternalFilesDir(null), "latest_pressure_scenario.json")
        scenarioFile.writeText(scenario.scenarioJson)
        RootShell.run(
            "mkdir -p '${DevicePaths.SCENARIO_DIR}'; cp '${scenarioFile.absolutePath}' '${DevicePaths.SCENARIO_DIR}/latest_pressure_maple_scenario.json'; cp '${scenarioFile.absolutePath}' '${DevicePaths.SCENARIO_DIR}/latest_maple_scenario.json'; chmod 0644 '${DevicePaths.SCENARIO_DIR}/latest_pressure_maple_scenario.json' '${DevicePaths.SCENARIO_DIR}/latest_maple_scenario.json'",
            requireRoot = true,
            timeoutMs = 5_000L,
        )

        writeProgress("maple_inference", "scenario_events=${events.size}; scenario=${scenarioFile.absolutePath}")
        val prediction = MapleInferenceOrchestrator(context).predict(scenario)
        require(prediction.available) {
            "MAPLE prediction failed during scheduler performance setup: ${prediction.error ?: "unknown error"}"
        }
        val recommendations = AppIdMapping.resolveTopApps(
            context = context,
            predictedAppId = prediction.predictedAppId,
            stage1Categories = prediction.stage1.map { it.name },
            scenarioCategories = scenario.topCategories,
            foregroundPackage = state.process.foregroundPackage,
        )
        require(recommendations.isNotEmpty()) {
            "MAPLE prediction did not map to any real launchable app on this device"
        }

        val report = JSONObject()
            .put("raw_trace_path", tracePath)
            .put("type", "shared_maple_target_selection")
            .put("condition", condition)
            .put("scheduler_executed", false)
            .put("interaction_result", interactionResult)
            .put("parsed_events", events.size)
            .put("scenario_id", scenario.scenarioId)
            .put("scenario_file", scenarioFile.absolutePath)
            .put("top_categories", JSONArray(scenario.topCategories))
            .put("maple_backend", prediction.backend)
            .put("predicted_app_id", prediction.predictedAppId)
            .put("scheduler_bits", prediction.schedulerBits)
            .put("stage1", JSONArray(prediction.stage1.map { "${it.name}:${it.probability}" }))
            .put(
                "recommendations",
                JSONArray(recommendations.map { app ->
                    JSONObject()
                        .put("package_name", app.packageName)
                        .put("label", app.label)
                        .put("category", app.category)
                        .put("confidence", app.confidence)
                }),
            )
            .put("actions", JSONArray())
            .put("duration_ms", SystemClock.elapsedRealtime() - stageStarted)

        return MemoPreparationResult(
            report = report,
            scenario = scenario,
            prediction = prediction,
            recommendations = recommendations,
            state = state,
            latencyBeforeActionsMs = SystemClock.elapsedRealtime() - stageStarted,
        )
    }

    private fun executeSchedulerActions(setup: MemoPreparationResult): List<ActionResult> {
        writeProgress("stage3_execute", "scheduler_bits=${setup.prediction.schedulerBits}; top1=${setup.recommendations.firstOrNull()?.packageName}")
        return ActionExecutor(context).executeRealtimePlan(
            plan = setup.prediction.schedulerPlan,
            schedulerBits = setup.prediction.schedulerBits,
            scenario = setup.scenario,
            prediction = setup.prediction,
            recommendations = setup.recommendations,
            state = setup.state,
            latencyBeforeActionsMs = setup.latencyBeforeActionsMs,
        )
    }

    private fun baselinePreparation(plan: RealUserExperimentPlan, condition: String, setup: MemoPreparationResult): JSONObject {
        return JSONObject()
            .put("type", "same_maple_target_without_stage3_scheduler_actions")
            .put("condition", condition)
            .put("selected_target_package", plan.targetPackage)
            .put("selected_target_label", plan.targetLabel)
            .put("target_selection", setup.report)
            .put("reason", "The same MAPLE-selected Top-1 app is cold-started without executing scheduler_bits.")
    }

    private fun measureWorkload(
        plan: RealUserExperimentPlan,
        condition: String,
        mode: String,
        repeatIndex: Int,
        memoPreparation: JSONObject?,
        forceStopBeforeLaunch: Boolean,
    ): WorkloadPressureResult {
        val packageName = requireNotNull(plan.targetPackage) { "pressure workload requires a real launchable app" }
        val label = plan.targetLabel ?: packageName
        val component = launchComponent(packageName)
            ?: error("launchable component disappeared for $packageName")
        val command = buildString {
            append("dumpsys gfxinfo '$packageName' reset >/dev/null 2>&1; ")
            if (forceStopBeforeLaunch) {
                append("am force-stop '$packageName' >/dev/null 2>&1; ")
            }
            append("sleep 1; ")
            append("am start -W -n '$component' 2>&1; ")
            append(oneMinuteActionSequence(plan))
        }
        val before = metrics.snapshot()
        val output = RootShell.run(command, requireRoot = true, timeoutMs = WORKLOAD_TIMEOUT_MS)
        val after = metrics.snapshot()
        val gfxRaw = RootShell.run("dumpsys gfxinfo '$packageName' 2>/dev/null | head -n 220", timeoutMs = 5_000L).stdout
        return WorkloadPressureResult(
            mode = mode,
            condition = condition,
            planId = plan.id,
            title = plan.title,
            targetPackage = packageName,
            targetLabel = label,
            launch = metrics.parseLaunchMetrics(output.stdout + "\n" + output.stderr),
            gfx = metrics.parseGfxMetrics(gfxRaw),
            before = before,
            after = after,
            delta = metrics.delta(before, after),
            commandOk = output.ok,
            commandSummary = output.stderr.ifBlank { output.stdout }.take(500),
            memoPreparation = (memoPreparation ?: JSONObject())
                .put("repeat_index", repeatIndex)
                .put("force_stop_before_launch", forceStopBeforeLaunch),
        )
    }

    private fun compare(reference: WorkloadPressureResult, candidate: WorkloadPressureResult, comparisonType: String): JSONObject {
        return JSONObject()
            .put("comparison_type", comparisonType)
            .put("plan_id", reference.planId)
            .put("condition", reference.condition)
            .put("target_package", reference.targetPackage)
            .put("target_label", reference.targetLabel)
            .put("reference_mode", reference.mode)
            .put("candidate_mode", candidate.mode)
            .put("interpretation", "Positive percentages mean the Stage 3 scheduler reduced pressure or latency against the same selected app without scheduler actions.")
            .put("launch_total_time_improvement_pct", improvementPct(reference.launch.totalTimeMs?.toDouble(), candidate.launch.totalTimeMs?.toDouble()))
            .put("wait_time_improvement_pct", improvementPct(reference.launch.waitTimeMs?.toDouble(), candidate.launch.waitTimeMs?.toDouble()))
            .put("pressure_score_improvement_pct", improvementPct(reference.delta.pressureScore, candidate.delta.pressureScore))
            .put("cpu_busy_improvement_pct", improvementPct(reference.delta.cpuBusyPct, candidate.delta.cpuBusyPct))
            .put("iowait_improvement_pct", improvementPct(reference.delta.iowaitPct, candidate.delta.iowaitPct))
            .put("memory_drop_improvement_pct", improvementPct(reference.delta.memAvailableDropKb?.toDouble(), candidate.delta.memAvailableDropKb?.toDouble()))
            .put("reclaim_improvement_pct", improvementPct(reference.delta.reclaimDelta?.toDouble(), candidate.delta.reclaimDelta?.toDouble()))
            .put("jank_rate_improvement_pct", improvementPct(reference.gfx.jankRatePct, candidate.gfx.jankRatePct))
            .put("reference_pressure_score", reference.delta.pressureScore)
            .put("candidate_pressure_score", candidate.delta.pressureScore)
            .putNullable("reference_launch_total_ms", reference.launch.totalTimeMs)
            .putNullable("candidate_launch_total_ms", candidate.launch.totalTimeMs)
            .putNullable("reference_jank_rate_pct", reference.gfx.jankRatePct)
            .putNullable("candidate_jank_rate_pct", candidate.gfx.jankRatePct)
    }

    private fun prepareCondition(condition: String, targetPackage: String?) {
        resetToHome()
        if (condition != CONDITION_CROWDED) return
        val apps = AppIdMapping.scanInstalledApps(context)
            .filter { it.packageName != targetPackage }
            .filter { it.packageName != context.packageName }
            .sortedWith(compareByDescending<AppIdMapping.InstalledAppProfile> { it.isUserInstalled }.thenBy { it.label.lowercase() })
            .take(CROWDED_BACKGROUND_APP_COUNT)
        val commands = apps.mapNotNull { app ->
            val component = launchComponent(app.packageName) ?: return@mapNotNull null
            "am start -W -n '$component' >/dev/null 2>&1; sleep 1; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1"
        }
        if (commands.isNotEmpty()) {
            RootShell.run(commands.joinToString("; "), requireRoot = true, timeoutMs = 40_000L)
        }
    }

    private fun summarize(comparisons: JSONArray): JSONObject {
        fun average(key: String, type: String? = null): Double? {
            val values = (0 until comparisons.length()).mapNotNull { idx ->
                val obj = comparisons.optJSONObject(idx) ?: return@mapNotNull null
                if (type != null && obj.optString("comparison_type") != type) return@mapNotNull null
                when (val value = obj.opt(key)) {
                    is Number -> value.toDouble()
                    else -> null
                }
            }
            return values.takeIf { it.isNotEmpty() }?.average()
        }
        fun summaryFor(type: String): JSONObject {
            return JSONObject()
                .put("comparison_type", type)
                .putNullable("avg_launch_total_time_improvement_pct", average("launch_total_time_improvement_pct", type))
                .putNullable("avg_wait_time_improvement_pct", average("wait_time_improvement_pct", type))
                .putNullable("avg_pressure_score_improvement_pct", average("pressure_score_improvement_pct", type))
                .putNullable("avg_cpu_busy_improvement_pct", average("cpu_busy_improvement_pct", type))
                .putNullable("avg_iowait_improvement_pct", average("iowait_improvement_pct", type))
                .putNullable("avg_memory_drop_improvement_pct", average("memory_drop_improvement_pct", type))
                .putNullable("avg_reclaim_improvement_pct", average("reclaim_improvement_pct", type))
                .put("claim_scope", "This comparison uses only system metrics. It isolates the value of executing Stage 3 scheduler_bits on the same MAPLE-selected app.")
        }
        val primaryType = "stage3_scheduler_vs_no_scheduler"
        return JSONObject()
            .put("workloads_compared", comparisons.length())
            .putNullable("avg_launch_total_time_improvement_pct", average("launch_total_time_improvement_pct", primaryType))
            .putNullable("avg_wait_time_improvement_pct", average("wait_time_improvement_pct", primaryType))
            .putNullable("avg_pressure_score_improvement_pct", average("pressure_score_improvement_pct", primaryType))
            .putNullable("avg_cpu_busy_improvement_pct", average("cpu_busy_improvement_pct", primaryType))
            .putNullable("avg_iowait_improvement_pct", average("iowait_improvement_pct", primaryType))
            .putNullable("avg_memory_drop_improvement_pct", average("memory_drop_improvement_pct", primaryType))
            .putNullable("avg_reclaim_improvement_pct", average("reclaim_improvement_pct", primaryType))
            .put("primary_comparison", summaryFor(primaryType))
            .put("claim_scope", "The experiment first selects a real Top-1 app using eBPF+MAPLE, then compares cold start without Stage 3 against the same target after Stage 3 scheduler actions. Only system metrics are reported.")
    }

    private fun persist(report: JSONObject) {
        val external = File(context.getExternalFilesDir(null), "latest_pressure_experiment.json")
        external.writeText(report.toString(2))
        RootShell.run(
            "mkdir -p '${DevicePaths.MEMO_PUBLIC_ROOT}/pressure'; cp '${external.absolutePath}' '${DevicePaths.MEMO_PUBLIC_ROOT}/pressure/latest_pressure_experiment.json'; chmod 0644 '${DevicePaths.MEMO_PUBLIC_ROOT}/pressure/latest_pressure_experiment.json'",
            requireRoot = true,
            timeoutMs = 5_000L,
        )
    }

    private fun hardPreflight() {
        RootShell.run("mkdir -p '${DevicePaths.MEMO_PUBLIC_ROOT}/pressure'", requireRoot = true, timeoutMs = 3_000L)
        DeviceCollectorDeployer(context).ensureRawCollectorExecutable()
        RootShell.run(
            "chmod 0755 '${DevicePaths.MEMO_ROOT}' '${DevicePaths.MODEL_DIR}' 2>/dev/null; " +
                "chmod 0755 '${DevicePaths.MAPLE_DEMO}' '${DevicePaths.RAW_COLLECTOR}' 2>/dev/null; " +
                "chmod 0644 '${DevicePaths.MAPLE_ENGINE_SO}' '${DevicePaths.MAPLE_CXX_SHARED}' '${DevicePaths.DEFAULT_MODEL}' '${DevicePaths.BPF_OBJECT}' 2>/dev/null",
            requireRoot = true,
            timeoutMs = 5_000L,
        )
        val checks = listOf(
            "test -x '${DevicePaths.RAW_COLLECTOR}'" to "raw eBPF collector is not executable at ${DevicePaths.RAW_COLLECTOR}",
            "test -r '${DevicePaths.BPF_OBJECT}'" to "BPF object is not readable at ${DevicePaths.BPF_OBJECT}",
            "test -x '${DevicePaths.MAPLE_DEMO}'" to "MAPLE executable is not executable at ${DevicePaths.MAPLE_DEMO}",
            "test -r '${DevicePaths.MAPLE_ENGINE_SO}'" to "MAPLE engine library is not readable at ${DevicePaths.MAPLE_ENGINE_SO}",
            "test -r '${DevicePaths.MAPLE_CXX_SHARED}'" to "C++ runtime is not readable at ${DevicePaths.MAPLE_CXX_SHARED}",
            "test -r '${DevicePaths.DEFAULT_MODEL}'" to "MAPLE GGUF model is not readable at ${DevicePaths.DEFAULT_MODEL}",
        )
        val failures = checks.mapNotNull { (cmd, message) ->
            val result = RootShell.run(cmd, requireRoot = true, timeoutMs = 4_000L)
            if (result.ok) null else "$message; shell=${result.stderr.ifBlank { result.stdout }.take(160)}"
        }
        require(failures.isEmpty()) { failures.joinToString(" | ") }
        writeProgress("preflight_ok", "collector, BPF object, MAPLE binary, libraries and GGUF model are readable on phone")
    }

    private fun launchComponent(packageName: String): String? {
        return context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToShortString()
    }

    private fun resetToHome() {
        RootShell.run("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1", requireRoot = true, timeoutMs = 4_000L)
    }

    private fun forceStop(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        RootShell.run("am force-stop '$packageName' >/dev/null 2>&1; sleep 1", requireRoot = true, timeoutMs = 5_000L)
    }

    private fun waitForCollector(tracePath: String) {
        val deadline = SystemClock.elapsedRealtime() + COLLECTOR_ATTACH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RootShell.run("grep -q 'collector_started' '$tracePath'", requireRoot = true, timeoutMs = 1_000L).ok) return
            Thread.sleep(250L)
        }
        val head = RootShell.run("head -n 20 '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 3_000L).stdout
        error("raw eBPF collector did not attach for pressure experiment; trace=$tracePath; head=${head.take(300)}")
    }

    private fun deviceInfo(): JSONObject {
        return JSONObject()
            .put("model", RootShell.run("getprop ro.product.model", timeoutMs = 2_000L).stdout.trim())
            .put("device", RootShell.run("getprop ro.product.device", timeoutMs = 2_000L).stdout.trim())
            .put("release", RootShell.run("getprop ro.build.version.release", timeoutMs = 2_000L).stdout.trim())
            .put("kernel", RootShell.run("uname -a", timeoutMs = 2_000L).stdout.trim())
    }

    private fun metricDefinitions(): JSONObject {
        return JSONObject()
            .put("launch_total_time_ms", "Android am start -W TotalTime for the measured app launch; lower is better.")
            .put("wait_time_ms", "Android am start -W WaitTime; lower is better.")
            .put("cpu_busy_pct", "CPU busy jiffy share during the workload window; lower means less global CPU pressure.")
            .put("iowait_pct", "CPU iowait jiffy share during the workload; lower means less storage/blocking pressure.")
            .put("mem_available_drop_kb", "MemAvailable before minus after; lower means the workload consumed less available memory.")
            .put("reclaim_delta", "pgscan_direct plus pgscan_kswapd delta from /proc/vmstat; lower means less memory reclaim.")
            .put("psi_some_avg10_delta", "Change in memory PSI some avg10; lower means less memory pressure.")
            .put("jank_rate_pct", "dumpsys gfxinfo janky frames / total frames for the target app when available; lower is smoother.")
            .put("pressure_score_lower_is_better", "Composite score from memory drop, reclaim, PSI, CPU, iowait, thermal, and UDP error pressure. It is only for same-device A/B ranking.")
    }

    private fun experimentDesign(contextPlan: RealUserExperimentPlan, measuredPlan: RealUserExperimentPlan): JSONObject {
        return JSONObject()
            .put("name", "system_scheduler_performance")
            .put("goal", "Validate whether MAPLE Stage 3 scheduler_bits improve system metrics for the same MAPLE-selected Top-1 app.")
            .put("setup", "Collect real eBPF during a real context app action, run MAPLE Stage 1/2/3 once, and choose a real launchable Top-1 app. The chosen target is shared by both measured arms.")
            .put("baseline", "Force-stop the selected target and run the one-minute action sequence without executing scheduler_bits.")
            .put("memo_stage123_scheduler", "Force-stop the selected target, execute scheduler_bits through ActionExecutor, then run the same one-minute action sequence without force-stopping again so warm-launch/cache actions can affect the measurement.")
            .put("context_package", contextPlan.targetPackage)
            .put("context_label", contextPlan.targetLabel)
            .put("target_package", measuredPlan.targetPackage)
            .put("target_label", measuredPlan.targetLabel)
            .put("sequence_duration_sec", ONE_MINUTE_WORKLOAD_SECONDS)
            .put("metrics", JSONArray(listOf("TotalTime", "WaitTime", "pressure_score_lower_is_better", "cpu_busy_pct", "iowait_pct", "mem_available_drop_kb", "reclaim_delta", "jank_rate_pct")))
            .put("prediction_metrics_included", false)
    }

    private fun measuredPlanFor(app: RecommendedApp, contextPlan: RealUserExperimentPlan): RealUserExperimentPlan {
        return contextPlan.copy(
            id = "maple_top1_${app.packageName.replace('.', '_')}",
            title = "MAPLE-selected Top-1 app workload: ${app.label}",
            description = "Measure the real Top-1 app selected by MAPLE so baseline and Stage 3 compare the same target.",
            targetPackage = app.packageName,
            targetLabel = app.label,
            desiredCategories = listOf(app.category),
        )
    }

    private fun oneMinuteActionSequence(plan: RealUserExperimentPlan): String {
        val baseCommands = plan.interactionCommands.ifEmpty {
            listOf(
                "input tap 540 960 >/dev/null 2>&1",
                "input swipe 540 1500 540 600 650 >/dev/null 2>&1",
                "input swipe 540 600 540 1500 650 >/dev/null 2>&1",
            )
        }
        val commands = mutableListOf<String>()
        commands += "sleep 1"
        repeat(ONE_MINUTE_ACTION_LOOPS) {
            commands += baseCommands
            commands += "sleep 1"
        }
        commands += "sleep 1"
        return commands.joinToString("; ")
    }

    private fun writeProgress(step: String, detail: String) {
        val line = "${System.currentTimeMillis()}\t$step\t$detail\n"
        RootShell.run(
            "mkdir -p '${DevicePaths.MEMO_PUBLIC_ROOT}/pressure'; printf %s ${shellQuote(line)} >> '${DevicePaths.MEMO_PUBLIC_ROOT}/pressure/system_scheduler_progress.tsv'",
            requireRoot = true,
            timeoutMs = 3_000L,
        )
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun improvementPct(baseline: Double?, memoOn: Double?): Any {
        if (baseline == null || memoOn == null || baseline <= 0.0) return JSONObject.NULL
        return (baseline - memoOn) * 100.0 / baseline
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

    private data class MemoPreparationResult(
        val report: JSONObject,
        val scenario: MapleScenario,
        val prediction: MaplePrediction,
        val recommendations: List<RecommendedApp>,
        val state: SystemStateSnapshot,
        val latencyBeforeActionsMs: Long,
    )

    companion object {
        private const val MODE_BASELINE = "same_target_no_scheduler"
        private const val MODE_STAGE123_SCHEDULER = "same_target_stage3_scheduler"
        private const val CONDITION_NORMAL = "normal_recent_usage"
        private const val CONDITION_CROWDED = "crowded_cached_apps"
        private const val MAX_WORKLOADS = 1
        private const val CROWDED_BACKGROUND_APP_COUNT = 5
        private const val WORKLOAD_TIMEOUT_MS = 90_000L
        private const val ONE_MINUTE_WORKLOAD_SECONDS = 60
        private const val ONE_MINUTE_ACTION_LOOPS = 8
        private const val PRESSURE_EVIDENCE_SECONDS = 8L
        private const val COLLECTOR_ATTACH_TIMEOUT_MS = 10_000L
        private const val MAX_EVENTS = 2_000
    }
}
