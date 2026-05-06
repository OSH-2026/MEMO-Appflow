package com.memoos.action

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.memoos.device.RootShell
import com.memoos.maple.MaplePrediction
import com.memoos.maple.MapleScenario
import com.memoos.perf.PipelineLatency
import com.memoos.state.SystemStateSnapshot
import com.memoos.widget.MemoWidgetProvider

data class ActionResult(
    val name: String,
    val target: String,
    val status: String,
    val detail: String,
    val durationMs: Long = 0L,
)

class ActionExecutor(private val context: Context) {
    fun execute(
        scenario: MapleScenario,
        prediction: MaplePrediction,
        recommendations: List<RecommendedApp>,
        state: SystemStateSnapshot,
        latencyBeforeActionsMs: Long = 0L,
        realtimeBudgetMs: Long = PipelineLatency.REALTIME_BUDGET_MS,
        allowVisibleWarmLaunch: Boolean = false,
        asyncPrediction: Boolean = true,
        publishWidget: Boolean = true,
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        val staleForRealtime = !asyncPrediction &&
            (latencyBeforeActionsMs > realtimeBudgetMs || prediction.error?.contains("timed out", ignoreCase = true) == true)

        results += timed {
            if (publishWidget) {
                MemoWidgetProvider.updateAll(context, recommendations)
                ActionResult("widget_update", "top3", "ok", "published ${recommendations.size} real app recommendations")
            } else {
                ActionResult("widget_update", "top3", "planned", "ablation metrics mode; product run would publish ${recommendations.size} real app recommendations")
            }
        }
        results += latencyPolicyAction(latencyBeforeActionsMs, realtimeBudgetMs, staleForRealtime, asyncPrediction)

        val memory = effectiveMemoryPressure(state, scenario)
        val thermal = state.battery.thermalRisk
        val hasRoot = RootShell.hasRoot()

        results += memoryPressureActions(memory, hasRoot, recommendations)
        results += thermalActions(thermal, hasRoot)
        results += networkActions(scenario, recommendations, hasRoot)
        results += cameraMediaActions(scenario, recommendations, memory, thermal)
        results += displayActions(scenario, memory)
        results += serviceActions(scenario, hasRoot)

        val warmLimit = when {
            !allowVisibleWarmLaunch -> 0
            staleForRealtime -> 0
            memory == "critical" || thermal == "critical" -> 0
            memory == "elevated" || thermal == "elevated" -> 1
            scenario.topCategories.any { it == "Display Composition" || it == "Media Codec" } -> 1
            else -> 2
        }
        if (warmLimit == 0) {
            val detail = if (staleForRealtime) {
                "prediction latency ${PipelineLatency.formatMs(latencyBeforeActionsMs)} exceeded realtime budget ${PipelineLatency.formatMs(realtimeBudgetMs)}; skip stale prewarm"
            } else if (!allowVisibleWarmLaunch) {
                "non-intrusive background mode; do not switch visible apps while the user continues using the phone"
            } else {
                "memory=$memory thermal=$thermal; preloading would add pressure"
            }
            results += ActionResult("warm_launch", "top_apps", "skipped", detail)
        } else {
            recommendations.take(warmLimit).forEach { app ->
                results += warmLaunch(app)
            }
        }

        if (!prediction.available) {
            results += ActionResult("maple_backend", "prediction", "blocked", prediction.error ?: "MAPLE unavailable")
        }

        return results
    }

    private fun latencyPolicyAction(latencyBeforeActionsMs: Long, budgetMs: Long, stale: Boolean, asyncPrediction: Boolean): ActionResult {
        return if (asyncPrediction) {
            ActionResult(
                "latency_policy",
                "async_maple",
                "ok",
                "MAPLE prediction ran asynchronously; foreground use was not blocked, completion latency=${PipelineLatency.formatMs(latencyBeforeActionsMs)}",
            )
        } else if (stale) {
            ActionResult(
                "latency_policy",
                "realtime_budget",
                "blocked",
                "pipeline before actions=${PipelineLatency.formatMs(latencyBeforeActionsMs)}, budget=${PipelineLatency.formatMs(budgetMs)}; use result for analysis/widget, not aggressive scheduling",
            )
        } else {
            ActionResult(
                "latency_policy",
                "realtime_budget",
                "ok",
                "pipeline before actions=${PipelineLatency.formatMs(latencyBeforeActionsMs)}, budget=${PipelineLatency.formatMs(budgetMs)}",
            )
        }
    }

    private fun warmLaunch(app: RecommendedApp): ActionResult {
        return timed {
            warmLaunchMeasured(app)
        }
    }

    private fun warmLaunchMeasured(app: RecommendedApp): ActionResult {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        val component = launchIntent?.component?.flattenToShortString()
        if (component == null) {
            return ActionResult("warm_launch", app.packageName, "skipped", "no launchable activity")
        }
        val rootCmd = "am start -W -n '$component' >/dev/null 2>&1; sleep 1; " +
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1"
        val rootResult = RootShell.run(rootCmd, requireRoot = true, timeoutMs = 8_000L)
        if (rootResult.ok) {
            return ActionResult("warm_launch", app.packageName, "ok", "root am start + HOME; label=${app.label}")
        }
        val requested = try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            context.startActivity(launchIntent)
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(home)
            true
        } catch (exc: Exception) {
            false
        }
        return if (requested) {
            ActionResult(
                "warm_launch",
                app.packageName,
                "requested",
                "framework startActivity requested after root warm launch failed; Android may block background starts",
            )
        } else {
            ActionResult("warm_launch", app.packageName, "failed", rootResult.stderr.ifBlank { rootResult.stdout }.take(180))
        }
    }

    private fun effectiveMemoryPressure(state: SystemStateSnapshot, scenario: MapleScenario): String {
        if (state.memory.pressureLevel == "critical" || scenario.memoryPressure.startsWith("critical")) return "critical"
        if (state.memory.pressureLevel == "elevated" || scenario.memoryPressure.startsWith("elevated")) return "elevated"
        return "normal"
    }

    private fun memoryPressureActions(memory: String, hasRoot: Boolean, recommendations: List<RecommendedApp>): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        if (memory == "normal") {
            results += ActionResult("memory_policy", "preload_budget", "ok", "normal memory; warm launch budget allowed")
            return results
        }
        results += ActionResult("memory_policy", "preload_budget", "ok", "memory=$memory; reducing warm launch aggressiveness")
        if (hasRoot) {
            results += timed {
                val idle = RootShell.run("cmd activity idle-maintenance >/dev/null 2>&1", timeoutMs = 6_000L)
                ActionResult("memory_idle_maintenance", "activity_manager", if (idle.ok) "ok" else "failed", idle.stderr.ifBlank { idle.stdout }.take(180))
            }
            recommendations.drop(1).forEach { app ->
                results += timed {
                    val trim = RootShell.run("am send-trim-memory '${app.packageName}' RUNNING_LOW >/dev/null 2>&1", timeoutMs = 4_000L)
                    ActionResult("memory_trim", app.packageName, if (trim.ok) "ok" else "failed", "requested RUNNING_LOW trim for non-primary candidate")
                }
            }
            if (memory == "critical") {
                results += timed {
                    val drop = RootShell.run("sync; echo 1 > /proc/sys/vm/drop_caches 2>/dev/null", timeoutMs = 5_000L)
                    ActionResult("cache_pressure_response", "drop_caches", if (drop.ok) "ok" else "failed", "critical memory response; page-cache only")
                }
            }
        } else {
            results += ActionResult("memory_root_actions", "device", "skipped", "root unavailable")
        }
        return results
    }

    private fun thermalActions(thermal: String, hasRoot: Boolean): List<ActionResult> {
        if (thermal == "normal") return listOf(ActionResult("thermal_policy", "preload_budget", "ok", "normal battery thermal state"))
        val results = mutableListOf(ActionResult("thermal_policy", "preload_budget", "ok", "thermal=$thermal; deferring aggressive warm launch"))
        if (hasRoot) {
            results += timed {
                val fixedPerfOff = RootShell.run("cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1", timeoutMs = 3_000L)
                ActionResult("thermal_power_mode", "power_manager", if (fixedPerfOff.ok) "ok" else "unsupported", "disable fixed performance mode if supported")
            }
        }
        return results
    }

    private fun networkActions(scenario: MapleScenario, recommendations: List<RecommendedApp>, hasRoot: Boolean): List<ActionResult> {
        if ("Network IO" !in scenario.topCategories) return emptyList()
        val results = mutableListOf<ActionResult>()
        val networkApps = recommendations.filter { it.category in setOf("Network IO", "Communication", "Media Codec") }
        results += ActionResult("network_candidate_priority", networkApps.joinToString { it.packageName }, "ok", "UDP sendto/recvfrom evidence prioritizes network-capable apps")
        if (hasRoot) {
            results += timed {
                val poll = RootShell.run("dumpsys netstats >/dev/null 2>&1", timeoutMs = 4_000L)
                ActionResult("network_stats_refresh", "netstats", if (poll.ok) "ok" else "failed", "refreshed network stats before recommendation")
            }
        }
        return results
    }

    private fun cameraMediaActions(
        scenario: MapleScenario,
        recommendations: List<RecommendedApp>,
        memory: String,
        thermal: String,
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        val camera = "Camera Service" in scenario.topCategories
        val media = "Media Codec" in scenario.topCategories
        if (!camera && !media) return results
        if (memory == "critical" || thermal == "critical") {
            results += ActionResult("camera_media_prewarm", "camera/media", "skipped", "critical pressure; avoid camera/media warmup")
            return results
        }
        val targets = recommendations.filter { it.category == "Camera Service" || it.category == "Media Codec" }.take(1)
        targets.forEach { app ->
            results += ActionResult("camera_media_candidate", app.packageName, "ok", "selected ${app.label} for camera/media follow-up warm launch")
        }
        return results
    }

    private fun displayActions(scenario: MapleScenario, memory: String): List<ActionResult> {
        if ("Display Composition" !in scenario.topCategories && "Input Interaction" !in scenario.topCategories) return emptyList()
        return listOf(
            ActionResult(
                "display_ui_policy",
                "warm_launch_budget",
                "ok",
                "SurfaceFlinger/RenderThread/input evidence; keep prewarm count low to avoid jank, memory=$memory",
            ),
        )
    }

    private fun serviceActions(scenario: MapleScenario, hasRoot: Boolean): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        if ("Android Service IPC" in scenario.topCategories || "Android System Services" in scenario.topCategories) {
            results += ActionResult("binder_service_policy", "system_services", "ok", "Binder/system-service evidence kept as MAPLE scheduling context")
            if (hasRoot) {
                results += timed {
                    val result = RootShell.run("service list >/dev/null 2>&1", timeoutMs = 3_000L)
                    ActionResult("binder_service_refresh", "service_manager", if (result.ok) "ok" else "failed", "queried service manager after high Binder activity")
                }
            }
        }
        return results
    }

    private inline fun timed(block: () -> ActionResult): ActionResult {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        return result.copy(durationMs = SystemClock.elapsedRealtime() - start)
    }
}
