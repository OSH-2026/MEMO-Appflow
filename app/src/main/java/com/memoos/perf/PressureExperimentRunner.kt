package com.memoos.perf

import android.content.Context
import android.os.SystemClock
import com.memoos.action.ActionExecutor
import com.memoos.action.ActionResult
import com.memoos.action.AppIdMapping
import com.memoos.device.DevicePaths
import com.memoos.device.EBPFCapabilityProbe
import com.memoos.device.RootShell
import com.memoos.ebpf.DeviceCollectorDeployer
import com.memoos.ebpf.EBPFTraceParser
import com.memoos.ebpf.RealUserExperimentPlan
import com.memoos.ebpf.RealUserExperimentPlanner
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MapleScenarioBuilder
import com.memoos.state.SystemStateCollector
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
        val plans = choosePlans()
        val conditions = listOf(CONDITION_NORMAL, CONDITION_CROWDED)
        val workloads = JSONArray()
        val comparisons = JSONArray()
        val startElapsed = SystemClock.elapsedRealtime()

        conditions.forEach { condition ->
            plans.forEach { plan ->
                prepareCondition(condition, plan.targetPackage)
                val baselinePreparation = runBaselinePreparation(plan, condition)
                val baseline = measureWorkload(plan, condition, MODE_BASELINE, memoPreparation = baselinePreparation)
                resetToHome()

                prepareCondition(condition, plan.targetPackage)
                val memoPreparation = runMemoPreparation(plan, condition)
                val memoOn = measureWorkload(plan, condition, MODE_MEMO_ON, memoPreparation = memoPreparation)
                resetToHome()

                workloads.put(baseline.toJson())
                workloads.put(memoOn.toJson())
                comparisons.put(compare(baseline, memoOn))
            }
        }

        val report = JSONObject()
            .put("schema_version", "memo.real_user_app_pressure.v1")
            .put("experiment_type", "real_user_app_pressure_ab")
            .put("started_at_ms", startedAt)
            .put("duration_ms", SystemClock.elapsedRealtime() - startElapsed)
            .put("device", deviceInfo())
            .put("metric_definitions", metricDefinitions())
            .put("workload_count", plans.size)
            .put("conditions", JSONArray(conditions))
            .put("workloads", workloads)
            .put("comparisons", comparisons)
            .put("summary", summarize(comparisons))

        persist(report)
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
            "no launchable target app was found for a real user app pressure experiment"
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
            description = "Launch a real installed app selected from PackageManager and measure user-visible workload pressure without pretending it is a camera/payment/category-specific case.",
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

    private fun measureWorkload(
        plan: RealUserExperimentPlan,
        condition: String,
        mode: String,
        memoPreparation: JSONObject?,
    ): WorkloadPressureResult {
        val packageName = requireNotNull(plan.targetPackage) { "pressure workload requires a real launchable app" }
        val label = plan.targetLabel ?: packageName
        val component = launchComponent(packageName)
            ?: error("launchable component disappeared for $packageName")
        val command = buildString {
            append("dumpsys gfxinfo '$packageName' reset >/dev/null 2>&1; ")
            append("am force-stop '$packageName' >/dev/null 2>&1; ")
            append("sleep 1; ")
            append("am start -W -n '$component' 2>&1; ")
            append(plan.interactionCommands.joinToString("; "))
            append("; sleep 1")
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
            memoPreparation = memoPreparation,
        )
    }

    private fun runBaselinePreparation(plan: RealUserExperimentPlan, condition: String): JSONObject {
        val start = SystemClock.elapsedRealtime()
        val interactionResult = plan.execute(context)
        return JSONObject()
            .put("type", "same_prior_real_user_action_without_memo")
            .put("condition", condition)
            .put("interaction_result", interactionResult)
            .put("duration_ms", SystemClock.elapsedRealtime() - start)
            .put("reason", "The baseline gets the same immediately previous real app action as MEMO-on, but no eBPF collection, MAPLE reasoning, or ActionExecutor scheduling.")
    }

    private fun runMemoPreparation(plan: RealUserExperimentPlan, condition: String): JSONObject {
        val stageStarted = SystemClock.elapsedRealtime()
        val capability = EBPFCapabilityProbe.probe()
        require(capability.canRunRawCollector) {
            "raw eBPF collector is required for MEMO-on pressure experiment; ${capability.notes.joinToString("; ")}"
        }
        DeviceCollectorDeployer(context).ensureRawCollectorExecutable()
        requireLocalMapleRuntime()

        RootShell.run("mkdir -p '${DevicePaths.LOG_DIR}' '${DevicePaths.SCENARIO_DIR}'", requireRoot = true, timeoutMs = 3_000L)
        val tracePath = "${DevicePaths.LOG_DIR}/pressure_${plan.id}_${System.currentTimeMillis()}.trace"
        val collector = capability.rawCollectorPath ?: DevicePaths.RAW_COLLECTOR
        val seconds = PRESSURE_EVIDENCE_SECONDS
        RootShell.run(
            "rm -f '$tracePath'; ($collector --duration-sec $seconds --max-events $MAX_EVENTS --output '$tracePath' 2>&1) & echo \$!",
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
            "MEMO-on pressure preparation produced zero eBPF events; trace=$tracePath; head=${head.take(300)}"
        }

        val state = SystemStateCollector(context).collect()
        val scenario = MapleScenarioBuilder(context).build(
            events = events,
            state = state,
            scenarioId = "pressure_${plan.id}",
            description = "Pressure A/B preparation: MEMO observes a real user app action, runs MAPLE, and applies non-intrusive scheduling before the measured follow-up workload.",
            targetPackage = plan.targetPackage,
            targetCategories = plan.desiredCategories,
        )
        val scenarioFile = File(context.getExternalFilesDir(null), "latest_pressure_scenario.json")
        scenarioFile.writeText(scenario.scenarioJson)

        val prediction = MapleInferenceOrchestrator(context).predict(scenario)
        require(prediction.available) {
            "MAPLE prediction failed during MEMO-on pressure preparation: ${prediction.error ?: "unknown error"}"
        }
        val recommendations = AppIdMapping.resolveTopApps(
            context = context,
            predictedAppId = prediction.predictedAppId,
            stage1Categories = prediction.stage1.map { it.name },
            scenarioCategories = scenario.topCategories,
            foregroundPackage = state.process.foregroundPackage,
        )
        val actions = ActionExecutor(context).execute(
            scenario = scenario,
            prediction = prediction,
            recommendations = recommendations,
            state = state,
            latencyBeforeActionsMs = SystemClock.elapsedRealtime() - stageStarted,
            allowVisibleWarmLaunch = false,
        )

        return JSONObject()
            .put("raw_trace_path", tracePath)
            .put("type", "same_prior_real_user_action_with_ebpf_maple_actions")
            .put("condition", condition)
            .put("interaction_result", interactionResult)
            .put("parsed_events", events.size)
            .put("scenario_id", scenario.scenarioId)
            .put("scenario_file", scenarioFile.absolutePath)
            .put("top_categories", JSONArray(scenario.topCategories))
            .put("maple_backend", prediction.backend)
            .put("predicted_app_id", prediction.predictedAppId)
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
            .put("actions", JSONArray(actions.map { it.toJson() }))
            .put("duration_ms", SystemClock.elapsedRealtime() - stageStarted)
    }

    private fun compare(baseline: WorkloadPressureResult, memoOn: WorkloadPressureResult): JSONObject {
        return JSONObject()
            .put("plan_id", baseline.planId)
            .put("condition", baseline.condition)
            .put("target_package", baseline.targetPackage)
            .put("target_label", baseline.targetLabel)
            .put("interpretation", "Positive percentages mean MEMO-on reduced pressure or latency for the same real app workload.")
            .put("launch_total_time_improvement_pct", improvementPct(baseline.launch.totalTimeMs?.toDouble(), memoOn.launch.totalTimeMs?.toDouble()))
            .put("wait_time_improvement_pct", improvementPct(baseline.launch.waitTimeMs?.toDouble(), memoOn.launch.waitTimeMs?.toDouble()))
            .put("pressure_score_improvement_pct", improvementPct(baseline.delta.pressureScore, memoOn.delta.pressureScore))
            .put("cpu_busy_improvement_pct", improvementPct(baseline.delta.cpuBusyPct, memoOn.delta.cpuBusyPct))
            .put("iowait_improvement_pct", improvementPct(baseline.delta.iowaitPct, memoOn.delta.iowaitPct))
            .put("memory_drop_improvement_pct", improvementPct(baseline.delta.memAvailableDropKb?.toDouble(), memoOn.delta.memAvailableDropKb?.toDouble()))
            .put("reclaim_improvement_pct", improvementPct(baseline.delta.reclaimDelta?.toDouble(), memoOn.delta.reclaimDelta?.toDouble()))
            .put("jank_rate_improvement_pct", improvementPct(baseline.gfx.jankRatePct, memoOn.gfx.jankRatePct))
            .put("baseline_pressure_score", baseline.delta.pressureScore)
            .put("memo_on_pressure_score", memoOn.delta.pressureScore)
            .putNullable("baseline_launch_total_ms", baseline.launch.totalTimeMs)
            .putNullable("memo_on_launch_total_ms", memoOn.launch.totalTimeMs)
            .putNullable("baseline_jank_rate_pct", baseline.gfx.jankRatePct)
            .putNullable("memo_on_jank_rate_pct", memoOn.gfx.jankRatePct)
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
        fun average(key: String): Double? {
            val values = (0 until comparisons.length()).mapNotNull { idx ->
                val value = comparisons.optJSONObject(idx)?.opt(key)
                when (value) {
                    is Number -> value.toDouble()
                    else -> null
                }
            }
            return values.takeIf { it.isNotEmpty() }?.average()
        }
        return JSONObject()
            .put("workloads_compared", comparisons.length())
            .putNullable("avg_launch_total_time_improvement_pct", average("launch_total_time_improvement_pct"))
            .putNullable("avg_wait_time_improvement_pct", average("wait_time_improvement_pct"))
            .putNullable("avg_pressure_score_improvement_pct", average("pressure_score_improvement_pct"))
            .putNullable("avg_cpu_busy_improvement_pct", average("cpu_busy_improvement_pct"))
            .putNullable("avg_iowait_improvement_pct", average("iowait_improvement_pct"))
            .putNullable("avg_memory_drop_improvement_pct", average("memory_drop_improvement_pct"))
            .putNullable("avg_reclaim_improvement_pct", average("reclaim_improvement_pct"))
            .put("claim_scope", "This measures user-facing app workload pressure on the rooted Android device. Pipeline latency is reported elsewhere and is not treated as the phone-performance metric.")
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

    private fun launchComponent(packageName: String): String? {
        return context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToShortString()
    }

    private fun resetToHome() {
        RootShell.run("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1", requireRoot = true, timeoutMs = 4_000L)
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
            .put("cpu_busy_pct", "CPU busy jiffy share during the user workload window; lower means less global CPU pressure.")
            .put("iowait_pct", "CPU iowait jiffy share during the workload; lower means less storage/blocking pressure.")
            .put("mem_available_drop_kb", "MemAvailable before minus after; lower means the workload consumed less available memory.")
            .put("reclaim_delta", "pgscan_direct plus pgscan_kswapd delta from /proc/vmstat; lower means less memory reclaim.")
            .put("psi_some_avg10_delta", "Change in memory PSI some avg10; lower means less memory pressure.")
            .put("jank_rate_pct", "dumpsys gfxinfo janky frames / total frames for the target app when available; lower is smoother.")
            .put("pressure_score_lower_is_better", "Composite score from memory drop, reclaim, PSI, CPU, iowait, thermal, and UDP error pressure. It is only for same-device A/B ranking.")
            .put("normal_condition", "No extra pressure preparation beyond returning to HOME.")
            .put("crowded_condition", "Before both baseline and MEMO-on, the runner opens several real installed launchable apps and returns HOME, simulating a phone with many cached/background apps.")
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

    companion object {
        private const val MODE_BASELINE = "memo_off_baseline"
        private const val MODE_MEMO_ON = "memo_on_after_real_ebpf_maple_actions"
        private const val CONDITION_NORMAL = "normal_recent_usage"
        private const val CONDITION_CROWDED = "crowded_cached_apps"
        private const val MAX_WORKLOADS = 3
        private const val CROWDED_BACKGROUND_APP_COUNT = 5
        private const val WORKLOAD_TIMEOUT_MS = 28_000L
        private const val PRESSURE_EVIDENCE_SECONDS = 14L
        private const val COLLECTOR_ATTACH_TIMEOUT_MS = 10_000L
        private const val MAX_EVENTS = 4_000
    }
}
