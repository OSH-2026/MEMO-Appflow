package com.memoos.realtime

import android.content.Context
import com.memoos.action.ActionExecutor
import com.memoos.action.AppIdMapping
import com.memoos.device.DevicePaths
import com.memoos.device.RootShell
import com.memoos.maple.MapleInferenceOrchestrator
import com.memoos.maple.MapleScenario
import com.memoos.maple.MapleScenarioBuilder
import com.memoos.perf.PipelineTimer
import com.memoos.store.MemoStore
import com.memoos.widget.MemoWidgetProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class RealtimePreloadController(
    private val context: Context,
    private val mapleExecutor: ExecutorService,
) {
    @Volatile private var stopRequested = false
    private val inferenceLock = Any()
    private var mapleTask: Future<*>? = null
    private var inferenceRunning = false
    private var pendingInference: RealtimeInferenceJob? = null

    fun runForever(windowMs: Long = REALTIME_WINDOW_MS) {
        val store = MemoStore(context)
        val startedAt = System.currentTimeMillis()
        stopRequested = false
        store.saveRealtimeActive(startedAt, startedAt + windowMs)
        store.appendAction(
            com.memoos.action.ActionResult(
                "realtime_mode",
                "background_prediction",
                "ok",
                "realtime mode started; MEMO will collect app/eBPF/system evidence every 3 minutes, update bounded memory, run MAPLE, refresh Top-3 widget, and execute scheduler actions",
            ),
        )

        while (!stopRequested && store.load().realtime.active && !Thread.currentThread().isInterrupted) {
            val cycleStart = System.currentTimeMillis()
            try {
                val timer = PipelineTimer("realtime_preload_window")
                val window = timer.measure("realtime_window_collect") {
                    RealtimeWindowRunner(context).collectWindow(windowMs)
                }
                val memory = timer.measure("realtime_memory_update") {
                    RealtimeMemoryStore(context).update(window, window.afterState)
                }
                val scenario = timer.measure("realtime_scenario_build") {
                    MapleScenarioBuilder(context).build(
                        events = window.events,
                        state = window.afterState,
                        scenarioId = "realtime_${window.startedAtMs}",
                        description = "Realtime MEMO window: last 3 minutes of natural foreground app sequence plus raw eBPF/system evidence and bounded EMA memory.",
                        targetCategories = window.summary().topCategories,
                        appTimeline = window.appTimeline,
                        timelineWindows = window.timelineWindows,
                        realtimeMemory = memory.toJson(),
                        realtimeWindow = window.toJson(),
                    )
                }
                File(context.getExternalFilesDir(null), "latest_realtime_maple_scenario.json").writeText(scenario.scenarioJson)
                RootShell.run(
                    "mkdir -p '${DevicePaths.SCENARIO_DIR}' '${DevicePaths.MEMO_PUBLIC_ROOT}/realtime'; " +
                        "cp '${File(context.getExternalFilesDir(null), "latest_realtime_maple_scenario.json").absolutePath}' '${DevicePaths.SCENARIO_DIR}/latest_realtime_maple_scenario.json'; " +
                        "chmod 0644 '${DevicePaths.SCENARIO_DIR}/latest_realtime_maple_scenario.json'",
                    timeoutMs = 5_000L,
                )

                enqueueInference(
                    RealtimeInferenceJob(
                        timer = timer,
                        window = window,
                        scenario = scenario,
                        memory = memory,
                        nextWindowAtMs = cycleStart + windowMs,
                    ),
                )
            } catch (exc: InterruptedException) {
                Thread.currentThread().interrupt()
                stopRequested = true
            } catch (exc: Exception) {
                store.appendAction(
                    com.memoos.action.ActionResult(
                        "realtime_cycle",
                        "3min_window",
                        "blocked",
                        exc.message ?: exc.javaClass.simpleName,
                    ),
                )
            }
            store.saveRealtimeActive(startedAt, System.currentTimeMillis() + windowMs)
        }
    }

    private fun enqueueInference(job: RealtimeInferenceJob) {
        var shouldStart = false
        synchronized(inferenceLock) {
            if (inferenceRunning) {
                pendingInference = job
            } else {
                inferenceRunning = true
                pendingInference = job
                shouldStart = true
            }
        }
        if (shouldStart) {
            mapleTask = mapleExecutor.submit { drainInferenceQueue() }
        }
    }

    private fun drainInferenceQueue() {
        while (!stopRequested && !Thread.currentThread().isInterrupted) {
            val job = synchronized(inferenceLock) {
                val next = pendingInference
                pendingInference = null
                if (next == null) {
                    inferenceRunning = false
                }
                next
            } ?: return
            runInference(job)
        }
        synchronized(inferenceLock) {
            inferenceRunning = false
        }
    }

    private fun runInference(job: RealtimeInferenceJob) {
        val store = MemoStore(context)
        val prediction = job.timer.measure("maple_inference") {
            MapleInferenceOrchestrator(context).predict(job.scenario)
        }
        if (!prediction.available) {
            val latency = job.timer.snapshot(
                job.window.events.size,
                mapleTimedOut = prediction.error?.contains("timed out", ignoreCase = true) == true,
            )
            store.saveFailure("Realtime MAPLE inference failed: ${prediction.error ?: "unknown error"}", latency, job.scenario.scenarioJson)
            return
        }
        val recommendations = job.timer.measure("realtime_app_mapping") {
            AppIdMapping.resolveTopApps(
                context = context,
                predictedAppId = prediction.predictedAppId,
                stage1Categories = prediction.stage1.map { it.name },
                scenarioCategories = job.scenario.topCategories,
                foregroundPackage = job.window.afterState.process.foregroundPackage,
            )
        }
        val actions = job.timer.measure("realtime_action_execution") {
            ActionExecutor(context).executeRealtimePlan(
                plan = prediction.schedulerPlan,
                schedulerBits = prediction.schedulerBits,
                scenario = job.scenario,
                prediction = prediction,
                recommendations = recommendations,
                state = job.window.afterState,
                latencyBeforeActionsMs = job.timer.elapsedMs(),
            )
        }
        val latency = job.timer.snapshot(
            job.window.events.size,
            mapleTimedOut = prediction.error?.contains("timed out", ignoreCase = true) == true,
        )
        store.save(job.scenario, prediction, recommendations, actions, latency)
        store.saveRealtimeCycle(
            windowStartMs = job.window.startedAtMs,
            windowEndMs = job.window.endedAtMs,
            inferenceMs = System.currentTimeMillis(),
            nextWindowAtMs = job.nextWindowAtMs,
            windowCount = job.memory.windowCount,
            memoryJson = job.memory.toJson().toString(2),
            windowJson = job.window.toJson().toString(2),
            schedulerPlanJson = JSONObject()
                .put("scheduler_bits", prediction.schedulerBits)
                .put(
                    "scheduler_plan",
                    JSONArray(prediction.schedulerPlan.map {
                        JSONObject()
                            .put("action_id", it.actionId)
                            .put("target", it.target)
                            .put("target_rank", it.targetRank)
                            .put("execute", it.execute)
                            .put("reason", it.reason)
                    }),
                )
                .put("queue_policy", "single MAPLE worker; at most one latest pending realtime window")
                .put("current_window_policy", "MAPLE input is dominated by the latest 3-minute app/eBPF/system window; bounded EMA memory is supplemental context")
                .toString(2),
        )
        MemoWidgetProvider.updateAll(context, recommendations)
    }

    fun stop() {
        stopRequested = true
        mapleTask?.cancel(true)
        synchronized(inferenceLock) {
            pendingInference = null
            inferenceRunning = false
        }
        RootShell.run("pkill -f memo_libbpf_collector 2>/dev/null; pkill -f maple_demo 2>/dev/null", timeoutMs = 3_000L)
        MemoStore(context).saveRealtimeStopped()
    }

    private data class RealtimeInferenceJob(
        val timer: PipelineTimer,
        val window: RealtimeWindow,
        val scenario: MapleScenario,
        val memory: RealtimeMemory,
        val nextWindowAtMs: Long,
    )

    companion object {
        const val REALTIME_WINDOW_MS = 180_000L
    }
}
