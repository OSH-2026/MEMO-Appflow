package com.memoos.ebpf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.memoos.R
import com.memoos.ablation.RealEbpfAblationRunner
import com.memoos.action.ActionResult
import com.memoos.action.ActionExecutor
import com.memoos.action.AppIdMapping
import com.memoos.device.DevicePaths
import com.memoos.device.EBPFCapabilityProbe
import com.memoos.device.EBPFCapabilityReport
import com.memoos.device.RootShell
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MapleScenario
import com.memoos.maple.MapleScenarioBuilder
import com.memoos.perf.PipelineTimer
import com.memoos.state.SystemStateCollector
import com.memoos.state.SystemStateSnapshot
import com.memoos.store.MemoStore
import com.memoos.widget.MemoWidgetProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

class EBPFCollectorService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mapleExecutor = Executors.newSingleThreadExecutor()
    private var runningTask: Future<*>? = null
    private var mapleTask: Future<*>? = null

    override fun onCreate() {
        super.onCreate()
        RootShell.configureBridge(this)
        MemoStore(this).clearSyntheticDemoState()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("MEMO pipeline is running"))
        when (intent?.action) {
            ACTION_STOP -> stopPipeline()
            ACTION_RUN_ONCE, null -> runPipelineOnce()
            ACTION_RECORD_CURRENT_USAGE -> runRealUserExperiment(RealUserExperimentPlanner.KIND_CURRENT)
            ACTION_EXPERIMENT_COMMUNICATION -> runRealUserExperiment(RealUserExperimentPlanner.KIND_COMMUNICATION)
            ACTION_EXPERIMENT_CAMERA -> runRealUserExperiment(RealUserExperimentPlanner.KIND_CAMERA)
            ACTION_EXPERIMENT_MEDIA -> runRealUserExperiment(RealUserExperimentPlanner.KIND_MEDIA)
            ACTION_EXPERIMENT_PAYMENT -> runRealUserExperiment(RealUserExperimentPlanner.KIND_PAYMENT)
            ACTION_EXPERIMENT_SCROLL -> runRealUserExperiment(RealUserExperimentPlanner.KIND_SCROLL)
            ACTION_REAL_ABLATION_LATEST -> runRealAblationOnLatestScenario()
            else -> runPipelineOnce()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPipeline()
        executor.shutdownNow()
        mapleExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun runPipelineOnce() {
        runningTask?.cancel(true)
        mapleTask?.cancel(true)
        runningTask = executor.submit {
            val timer = PipelineTimer("device_pipeline")
            try {
                val report = timer.measure("capability_probe") { EBPFCapabilityProbe.probe().requireUsableEbpf() }
                val events = timer.measure("ebpf_capture_parse") { collectEbpfEvents(report, WINDOW_MS) }
                val state = timer.measure("system_state") { SystemStateCollector(this).collect() }
                val scenario = timer.measure("scenario_build") { MapleScenarioBuilder(this).build(events, state) }
                File(getExternalFilesDir(null), "latest_maple_scenario.json").writeText(scenario.scenarioJson)
                publishEvidenceThenRunMaple(timer, scenario, state, events.size)
            } catch (exc: Exception) {
                stopDeviceCollectors()
                val latency = timer.snapshot(0, mapleTimedOut = false)
                File(getExternalFilesDir(null), "latest_pipeline_latency.json").writeText(latency.toJson().toString(2))
                MemoStore(this).saveFailure("Strict eBPF pipeline failed: ${exc.message ?: exc.javaClass.simpleName}", latency)
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    private fun runRealUserExperiment(kind: String) {
        runningTask?.cancel(true)
        mapleTask?.cancel(true)
        runningTask = executor.submit {
            val timer = PipelineTimer("real_user_ebpf_experiment:$kind")
            try {
                val report = timer.measure("capability_probe") { EBPFCapabilityProbe.probe().requireUsableEbpf() }
                val plan = timer.measure("experiment_plan") { RealUserExperimentPlanner.plan(this, kind) }
                val collected = timer.measure("ebpf_capture_parse") {
                    collectEbpfEventsDuringRealInteraction(report, EXPERIMENT_WINDOW_MS) {
                        plan.execute(this)
                    }
                }
                val state = timer.measure("system_state") { SystemStateCollector(this).collect() }
                val scenario = timer.measure("scenario_build") {
                    MapleScenarioBuilder(this).build(
                        events = collected.events,
                        state = state,
                        scenarioId = "real_user_${plan.id}",
                        description = buildString {
                            append(plan.description)
                            plan.targetLabel?.let { append(" Target app: ").append(it).append(" (").append(plan.targetPackage).append(").") }
                            append(" Raw trace: ").append(collected.rawTracePath).append(".")
                        },
                        targetPackage = plan.targetPackage,
                        targetCategories = plan.desiredCategories,
                    )
                }
                File(getExternalFilesDir(null), "latest_maple_scenario.json").writeText(scenario.scenarioJson)
                File(getExternalFilesDir(null), "latest_real_user_experiment.txt").writeText(
                    buildString {
                        appendLine("id=${plan.id}")
                        appendLine("title=${plan.title}")
                        appendLine("target_package=${plan.targetPackage ?: "current_app"}")
                        appendLine("target_label=${plan.targetLabel ?: "manual/current"}")
                        appendLine("desired_categories=${plan.desiredCategories.joinToString()}")
                        appendLine("raw_trace_path=${collected.rawTracePath}")
                        appendLine("interaction_result=${collected.interactionResult}")
                        appendLine("parsed_events=${collected.events.size}")
                        appendLine("description=${scenario.description}")
                    },
                )
                publishEvidenceThenRunMaple(timer, scenario, state, collected.events.size)
            } catch (exc: Exception) {
                stopDeviceCollectors()
                val latency = timer.snapshot(0, mapleTimedOut = false)
                File(getExternalFilesDir(null), "latest_pipeline_latency.json").writeText(latency.toJson().toString(2))
                MemoStore(this).saveFailure("Strict real eBPF experiment failed: ${exc.message ?: exc.javaClass.simpleName}", latency)
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    private fun runRealAblationOnLatestScenario() {
        runningTask?.cancel(true)
        mapleTask?.cancel(true)
        runningTask = executor.submit {
            try {
                val scenarioFile = File(getExternalFilesDir(null), "latest_maple_scenario.json")
                require(scenarioFile.isFile) { "latest real MAPLE scenario is missing; run a real eBPF experiment first" }
                val report = RealEbpfAblationRunner(this).run(scenarioFile.readText())
                val resultCount = report.getJSONArray("results").length()
                MemoStore(this).appendAction(
                    ActionResult(
                        "real_ebpf_ablation",
                        "${DevicePaths.MEMO_PUBLIC_ROOT}/ablations/latest_real_ablation.json",
                        "ok",
                        "ran $resultCount MAPLE ablations on the latest Android-side real eBPF scenario",
                    ),
                )
            } catch (exc: Exception) {
                MemoStore(this).appendAction(
                    ActionResult(
                        "real_ebpf_ablation",
                        "latest_real_scenario",
                        "blocked",
                        exc.message ?: exc.javaClass.simpleName,
                    ),
                )
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    private fun publishEvidenceThenRunMaple(
        timer: PipelineTimer,
        scenario: MapleScenario,
        state: SystemStateSnapshot,
        eventCount: Int,
    ) {
        val pendingLatency = timer.snapshot(eventCount, mapleTimedOut = false)
        File(getExternalFilesDir(null), "latest_pipeline_latency.json").writeText(pendingLatency.toJson().toString(2))
        MemoStore(this).savePending(
            scenario = scenario,
            message = "MAPLE inference is running in the background. No Top-3 prediction, widget recommendation, or scheduling action is published until MAPLE returns.",
            latency = pendingLatency,
        )
        MemoWidgetProvider.updateAll(this, emptyList())

        mapleTask = mapleExecutor.submit {
            val prediction = timer.measure("maple_inference") { MapleInferenceOrchestrator(this).predict(scenario) }
            if (!prediction.available) {
                val latency = timer.snapshot(eventCount, prediction.error?.contains("timed out", ignoreCase = true) == true)
                File(getExternalFilesDir(null), "latest_pipeline_latency.json").writeText(latency.toJson().toString(2))
                MemoStore(this).saveFailure(
                    message = "Strict MAPLE inference failed: ${prediction.error ?: "no MAPLE prediction"}",
                    latency = latency,
                    scenarioJson = scenario.scenarioJson,
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                return@submit
            }
            val categories = prediction.stage1.map { it.name }
            val recommendations = timer.measure("maple_app_mapping") {
                AppIdMapping.resolveTopApps(
                    context = this,
                    predictedAppId = prediction.predictedAppId,
                    stage1Categories = categories,
                    scenarioCategories = scenario.topCategories,
                    foregroundPackage = state.process.foregroundPackage,
                )
            }
            val beforeActionsMs = timer.elapsedMs()
            val baseActions = timer.measure("maple_action_update") {
                ActionExecutor(this).execute(
                    scenario = scenario,
                    prediction = prediction,
                    recommendations = recommendations,
                    state = state,
                    latencyBeforeActionsMs = beforeActionsMs,
                    allowVisibleWarmLaunch = false,
                )
            }
            val latency = timer.snapshot(eventCount, prediction.error?.contains("timed out", ignoreCase = true) == true)
            val completion = ActionResult(
                "maple_background",
                "deep_reasoning",
                "completed",
                "MAPLE result published after asynchronous inference; foreground budget status=${latency.realtimeStatus}",
            )
            val actions = baseActions + completion + ActionResult("latency_summary", "pipeline", latency.realtimeStatus, latency.summaryLine())
            File(getExternalFilesDir(null), "latest_pipeline_latency.json").writeText(latency.toJson().toString(2))
            MemoStore(this).save(scenario, prediction, recommendations, actions, latency)
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun stopPipeline() {
        runningTask?.cancel(true)
        mapleTask?.cancel(true)
        runningTask = null
        mapleTask = null
        stopDeviceCollectors(killMaple = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopDeviceCollectors(killMaple: Boolean = false) {
        val maplePart = if (killMaple) "; pkill -f maple_demo 2>/dev/null" else ""
        RootShell.run("pkill -f memo_appflow_generated.bt 2>/dev/null; pkill -f bpftrace 2>/dev/null$maplePart", timeoutMs = 3_000L)
    }

    private fun collectEbpfEvents(report: EBPFCapabilityReport, windowMs: Long): List<EBPFEvent> {
        val script = BpftraceProgramBuilder.build(report)
        DeviceCollectorDeployer(this).deployGeneratedBpftrace(script)
        val bpftrace = report.bpftracePath ?: DevicePaths.BPFTRACE
        val seconds = ((windowMs + 999L) / 1000L + BPFTRACE_STARTUP_SLACK_SECONDS).coerceAtLeast(2L)
        val command = "timeout $seconds $bpftrace -B line '${DevicePaths.GENERATED_TRACE_SCRIPT}' 2>&1 | sed -n '/^MEMO/p' | head -n $REALTIME_PARSE_LINES"
        val result = RootShell.run(command, timeoutMs = (seconds + 5L) * 1_000L)
        if (!result.ok && result.stdout.isBlank()) {
            error("eBPF capture failed: ${result.stderr.ifBlank { result.stdout }.take(300)}")
        }
        val events = EBPFTraceParser.parseLines(result.stdout.lineSequence())
            .take(MAX_LINES)
            .toList()
        if (events.isEmpty()) {
            error("eBPF capture produced zero MEMO events; raw output=${result.stdout.ifBlank { result.stderr }.take(300)}")
        }
        return events
    }

    private fun collectEbpfEventsDuringRealInteraction(
        report: EBPFCapabilityReport,
        windowMs: Long,
        interaction: () -> String,
    ): CollectedEvents {
        val script = BpftraceProgramBuilder.build(report)
        DeviceCollectorDeployer(this).deployGeneratedBpftrace(script)
        RootShell.run("mkdir -p ${DevicePaths.LOG_DIR}", timeoutMs = 3_000L)

        val bpftrace = report.bpftracePath ?: DevicePaths.BPFTRACE
        val seconds = ((windowMs + 999L) / 1000L + BPFTRACE_STARTUP_SLACK_SECONDS).coerceAtLeast(8L)
        val rawTracePath = "${DevicePaths.LOG_DIR}/real_user_${System.currentTimeMillis()}.trace"
        RootShell.run(
            "rm -f '$rawTracePath'; (timeout $seconds $bpftrace -B line '${DevicePaths.GENERATED_TRACE_SCRIPT}' > '$rawTracePath' 2>&1) & echo \$!",
            timeoutMs = 3_000L,
        )
        waitForCollectorStarted(rawTracePath)
        val startAt = System.currentTimeMillis()
        val interactionResult = interaction()
        val remaining = windowMs - (System.currentTimeMillis() - startAt)
        if (remaining > 0) Thread.sleep(remaining)
        RootShell.run("pkill -f memo_appflow_generated.bt 2>/dev/null; pkill -f bpftrace 2>/dev/null", timeoutMs = 3_000L)
        val raw = RootShell.run("sed -n '/^MEMO/p' '$rawTracePath' 2>/dev/null | head -n $REALTIME_PARSE_LINES", timeoutMs = 8_000L).stdout
        val events = EBPFTraceParser.parseLines(raw.lineSequence())
            .take(MAX_LINES)
            .toList()
        if (events.isEmpty()) {
            val head = RootShell.run("head -n 20 '$rawTracePath' 2>/dev/null", timeoutMs = 3_000L).stdout
            error("real eBPF capture produced zero MEMO events; raw_trace_path=$rawTracePath; head=${head.take(500)}")
        }
        return CollectedEvents(events, rawTracePath, interactionResult)
    }

    private fun waitForCollectorStarted(rawTracePath: String) {
        val deadline = System.currentTimeMillis() + BPFTRACE_ATTACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val ready = RootShell.run("grep -q 'collector_started' '$rawTracePath'", timeoutMs = 1_000L)
            if (ready.ok) return
            Thread.sleep(250L)
        }
        val head = RootShell.run("head -n 20 '$rawTracePath' 2>/dev/null", timeoutMs = 3_000L).stdout
        error("bpftrace did not attach before user interaction; raw_trace_path=$rawTracePath; head=${head.take(500)}")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "MEMO Pipeline", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MEMO-Appflow")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MEMO-Appflow")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        }
    }

    companion object {
        const val ACTION_RUN_ONCE = "com.memoos.action.RUN_ONCE"
        const val ACTION_STOP = "com.memoos.action.STOP"
        const val ACTION_RECORD_CURRENT_USAGE = "com.memoos.action.RECORD_CURRENT_USAGE"
        const val ACTION_EXPERIMENT_COMMUNICATION = "com.memoos.action.REAL_EXPERIMENT_COMMUNICATION"
        const val ACTION_EXPERIMENT_CAMERA = "com.memoos.action.REAL_EXPERIMENT_CAMERA"
        const val ACTION_EXPERIMENT_MEDIA = "com.memoos.action.REAL_EXPERIMENT_MEDIA"
        const val ACTION_EXPERIMENT_PAYMENT = "com.memoos.action.REAL_EXPERIMENT_PAYMENT"
        const val ACTION_EXPERIMENT_SCROLL = "com.memoos.action.REAL_EXPERIMENT_SCROLL"
        const val ACTION_REAL_ABLATION_LATEST = "com.memoos.action.REAL_EBPF_ABLATION_LATEST"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "memo_pipeline"
        private const val WINDOW_MS = 8_000L
        private const val EXPERIMENT_WINDOW_MS = 14_000L
        private const val BPFTRACE_STARTUP_SLACK_SECONDS = 70
        private const val BPFTRACE_ATTACH_TIMEOUT_MS = 60_000L
        private const val MAX_LINES = 12_000
        private const val REALTIME_PARSE_LINES = 4_000
    }

    private data class CollectedEvents(
        val events: List<EBPFEvent>,
        val rawTracePath: String,
        val interactionResult: String,
    )
}

private fun com.memoos.device.EBPFCapabilityReport.requireUsableEbpf(): com.memoos.device.EBPFCapabilityReport {
    require(canRunBpftrace) {
        "bpftrace is required; report=${notes.joinToString("; ")}"
    }
    require(availableEvents.isNotEmpty()) {
        "no tracepoints are available for eBPF collection"
    }
    return this
}
