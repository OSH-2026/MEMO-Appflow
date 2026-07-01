package com.memoos.action

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.memoos.device.RootShell
import com.memoos.maple.MaplePrediction
import com.memoos.maple.MapleSchedulerDecision
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
    val timestampMs: Long = System.currentTimeMillis(),
)

class ActionExecutor(private val context: Context) {
    fun execute(
        scenario: MapleScenario,
        prediction: MaplePrediction,
        recommendations: List<RecommendedApp>,
        state: SystemStateSnapshot,
        latencyBeforeActionsMs: Long = 0L,
        allowVisibleWarmLaunch: Boolean = false,
        asyncPrediction: Boolean = true,
        publishWidget: Boolean = true,
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()

        results += timed {
            if (publishWidget) {
                MemoWidgetProvider.updateAll(context, recommendations)
                ActionResult("widget_update", "top3", "ok", "published ${recommendations.size} real app recommendations")
            } else {
                ActionResult("widget_update", "top3", "planned", "ablation metrics mode; product run would publish ${recommendations.size} real app recommendations")
            }
        }
        results += latencyPolicyAction(latencyBeforeActionsMs, asyncPrediction)

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
            memory == "critical" || thermal == "critical" -> 0
            memory == "elevated" || thermal == "elevated" -> 1
            scenario.topCategories.any { it == "Display Composition" || it == "Media Codec" } -> 1
            else -> 2
        }
        if (warmLimit == 0) {
            if (!allowVisibleWarmLaunch) {
                results += ActionResult(
                    "warm_launch_policy",
                    "top_apps",
                    "planned",
                    "background mode prepared warm-launch candidates but did not switch the screen; tap explicit prewarm to execute",
                )
            } else {
                results += ActionResult("warm_launch", "top_apps", "skipped", "memory=$memory thermal=$thermal; preloading would add pressure")
            }
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

    fun warmLaunchNow(app: RecommendedApp): ActionResult {
        return warmLaunch(app)
    }

    fun executeRealtimePlan(
        plan: List<MapleSchedulerDecision>,
        schedulerBits: String = "",
        scenario: MapleScenario,
        prediction: MaplePrediction,
        recommendations: List<RecommendedApp>,
        state: SystemStateSnapshot,
        latencyBeforeActionsMs: Long = 0L,
    ): List<ActionResult> {
        val effectivePlan = decisionsFromBits(schedulerBits).ifEmpty { plan }
        val results = mutableListOf<ActionResult>()
        results += timed {
            MemoWidgetProvider.updateAll(context, recommendations)
            ActionResult("widget_update", "top3", "ok", "realtime mode published ${recommendations.size} predicted apps to the desktop widget")
        }
        results += latencyPolicyAction(latencyBeforeActionsMs, asyncPrediction = true)
        results += ActionResult(
            "maple_scheduler_plan",
            "predefined_actions",
            if (effectivePlan.isEmpty()) "planned" else "ok",
            if (effectivePlan.isEmpty()) {
                "MAPLE did not return scheduler_bits or structured scheduler_plan; rule-based safety actions are used for this cycle"
            } else {
                val source = if (schedulerBits.isNotBlank()) "scheduler_bits=$schedulerBits" else "scheduler_plan"
                "MAPLE selected ${effectivePlan.count { it.execute }} executable scheduler actions from ${effectivePlan.size} predefined decisions; source=$source"
            },
        )

        val executed = effectivePlan.filter { it.execute }
        executed.forEach { decision ->
            results += executeDecision(decision, recommendations, state)
        }
        if (executed.isEmpty()) {
            results += execute(
                scenario = scenario,
                prediction = prediction,
                recommendations = recommendations,
                state = state,
                latencyBeforeActionsMs = latencyBeforeActionsMs,
                allowVisibleWarmLaunch = false,
                publishWidget = false,
            ).filterNot { it.name == "latency_policy" }
        }
        if (!prediction.available) {
            results += ActionResult("maple_backend", "prediction", "blocked", prediction.error ?: "MAPLE unavailable")
        }
        return results
    }

    private fun decisionsFromBits(bits: String): List<MapleSchedulerDecision> {
        val clean = bits.filter { it == '0' || it == '1' }
        if (clean.isBlank()) return emptyList()
        return SCHEDULER_ACTION_IDS.mapIndexed { index, actionId ->
            MapleSchedulerDecision(
                actionId = actionId,
                target = "",
                targetRank = index,
                execute = clean.getOrNull(index) == '1',
                reason = "MAPLE scheduler_bits[$index]=${clean.getOrNull(index) ?: '0'}",
            )
        }
    }

    private fun latencyPolicyAction(latencyBeforeActionsMs: Long, asyncPrediction: Boolean): ActionResult {
        return if (asyncPrediction) {
            ActionResult(
                "latency_policy",
                "async_maple",
                "ok",
                "MAPLE prediction ran asynchronously; completion latency=${PipelineLatency.formatMs(latencyBeforeActionsMs)}; slow runs still finish and still drive actions",
            )
        } else {
            ActionResult(
                "latency_policy",
                "completion_latency",
                "ok",
                "pipeline before actions=${PipelineLatency.formatMs(latencyBeforeActionsMs)}; no realtime cutoff is applied",
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
            results += ActionResult("memory_policy", "preload_intensity", "ok", "normal memory; lightweight warm launch remains enabled")
            return results
        }
        results += ActionResult("memory_policy", "preload_intensity", "ok", "memory=$memory; limiting visible warm launch and trimming lower priority candidates")
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
        if (thermal == "normal") return listOf(ActionResult("thermal_policy", "preload_intensity", "ok", "normal battery thermal state"))
        val results = mutableListOf(ActionResult("thermal_policy", "preload_intensity", "ok", "thermal=$thermal; limiting camera/media warm launch"))
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
                "warm_launch_intensity",
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

    private fun executeDecision(
        decision: MapleSchedulerDecision,
        recommendations: List<RecommendedApp>,
        state: SystemStateSnapshot,
    ): ActionResult {
        return when (decision.actionId) {
            "warm_launch_top1" -> {
                val app = recommendations.firstOrNull()
                if (app == null) {
                    ActionResult("maple_warm_launch_top1", "top1", "skipped", "MAPLE requested warm launch but no Top-1 app exists")
                } else if (state.memory.pressureLevel == "critical" || state.battery.thermalRisk == "critical") {
                    ActionResult("maple_warm_launch_top1", app.packageName, "skipped", "MAPLE requested warm launch, but critical memory or thermal pressure blocks visible preloading")
                } else {
                    warmLaunch(app).copy(
                        name = "maple_warm_launch_top1",
                        detail = "MAPLE bit requested Top-1 preload; ${app.label} was warm-launched and MEMO returned to HOME",
                    )
                }
            }
            "warm_launch_top2_if_idle" -> {
                val app = recommendations.getOrNull(1)
                if (app == null) {
                    ActionResult("maple_warm_launch_top2_if_idle", "top2", "skipped", "MAPLE requested Top-2 preload but no candidate exists")
                } else if (state.memory.pressureLevel != "normal" || state.battery.thermalRisk != "normal") {
                    ActionResult("maple_warm_launch_top2_if_idle", app.packageName, "skipped", "Top-2 preload requires normal memory and thermal state")
                } else if (state.process.foregroundPackage != null && state.process.foregroundPackage != "com.google.android.apps.nexuslauncher") {
                    ActionResult("maple_warm_launch_top2_if_idle", app.packageName, "planned", "foreground app is active; idle-only Top-2 preload is deferred")
                } else {
                    warmLaunch(app).copy(
                        name = "maple_warm_launch_top2_if_idle",
                        detail = "MAPLE bit requested idle Top-2 preload; ${app.label} was warm-launched and MEMO returned to HOME",
                    )
                }
            }
            "trim_memory_low_priority_apps" -> timed {
                val targets = recommendations.drop(1).take(2)
                if (!RootShell.hasRoot()) {
                    ActionResult("maple_trim_memory", "low_priority_apps", "skipped", "root unavailable")
                } else {
                    targets.forEach { app ->
                        RootShell.run("am send-trim-memory '${app.packageName}' RUNNING_LOW >/dev/null 2>&1", timeoutMs = 4_000L)
                    }
                    ActionResult("maple_trim_memory", targets.joinToString { it.packageName }, "ok", decision.reason.ifBlank { "MAPLE requested trim-memory for lower-priority candidates" })
                }
            }
            "kill_selected_background_package" -> timed {
                val target = safeKillTarget(decision.target, recommendations)
                    ?: safeBackgroundKillCandidate(recommendations, state)
                if (target == null) {
                    ActionResult("maple_kill_background", decision.target.ifBlank { "unspecified" }, "skipped", "no safe non-foreground, non-system, non-recommended package is eligible")
                } else {
                    val killed = RootShell.run("am force-stop '$target' >/dev/null 2>&1", requireRoot = true, timeoutMs = 4_000L)
                    ActionResult("maple_kill_background", target, if (killed.ok) "ok" else "failed", decision.reason.ifBlank { "MAPLE requested stopping a selected background package" })
                }
            }
            "drop_cache_if_critical_memory" -> timed {
                if (state.memory.pressureLevel != "critical") {
                    ActionResult("maple_drop_cache", "drop_caches", "skipped", "MAPLE requested cache drop but memory is not critical")
                } else {
                    val drop = RootShell.run("sync; echo 1 > /proc/sys/vm/drop_caches 2>/dev/null", requireRoot = true, timeoutMs = 5_000L)
                    ActionResult("maple_drop_cache", "drop_caches", if (drop.ok) "ok" else "failed", decision.reason.ifBlank { "critical memory response; page-cache only" })
                }
            }
            "refresh_network_stats" -> timed {
                val poll = RootShell.run("dumpsys netstats >/dev/null 2>&1", timeoutMs = 4_000L)
                ActionResult("maple_network_stats_refresh", "netstats", if (poll.ok) "ok" else "failed", decision.reason.ifBlank { "MAPLE requested network stats refresh" })
            }
            "refresh_service_manager" -> timed {
                val result = RootShell.run("service list >/dev/null 2>&1", timeoutMs = 3_000L)
                ActionResult("maple_service_manager_refresh", "service_manager", if (result.ok) "ok" else "failed", decision.reason.ifBlank { "MAPLE requested Binder/service refresh" })
            }
            "reduce_prewarm_when_display_busy" -> ActionResult(
                "maple_reduce_prewarm_display",
                "warm_launch_intensity",
                "ok",
                decision.reason.ifBlank { "MAPLE requested lower prewarm intensity because display/UI is busy" },
            )
            "skip_camera_prewarm_when_thermal_high" -> ActionResult(
                "maple_skip_camera_thermal",
                "camera/media",
                if (state.battery.thermalRisk == "normal") "planned" else "ok",
                decision.reason.ifBlank { "MAPLE requested avoiding camera/media warmup under thermal pressure" },
            )
            else -> ActionResult("maple_scheduler_unknown", decision.actionId, "skipped", "unknown scheduler action id from MAPLE")
        }
    }

    private fun safeKillTarget(target: String, recommendations: List<RecommendedApp>): String? {
        val value = target.trim()
        if (!value.matches(Regex("""[A-Za-z0-9_.]+"""))) return null
        if (value == context.packageName) return null
        if (value in recommendations.map { it.packageName }) return null
        if (value.startsWith("android") || value.startsWith("com.android") || value.startsWith("com.google.android")) return null
        return value
    }

    private fun safeBackgroundKillCandidate(
        recommendations: List<RecommendedApp>,
        state: SystemStateSnapshot,
    ): String? {
        if (state.memory.pressureLevel == "normal") return null
        val protectedPackages = recommendations.map { it.packageName }.toSet() + context.packageName +
            setOfNotNull(state.process.foregroundPackage)
        return AppIdMapping.scanInstalledApps(context)
            .asSequence()
            .filter { it.isUserInstalled }
            .map { it.packageName }
            .filter { it !in protectedPackages }
            .filterNot { it.startsWith("android") || it.startsWith("com.android") || it.startsWith("com.google.android") }
            .firstOrNull()
    }

    private inline fun timed(block: () -> ActionResult): ActionResult {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        return result.copy(durationMs = SystemClock.elapsedRealtime() - start)
    }

    private companion object {
        val SCHEDULER_ACTION_IDS = listOf(
            "warm_launch_top1",
            "warm_launch_top2_if_idle",
            "trim_memory_low_priority_apps",
            "kill_selected_background_package",
            "drop_cache_if_critical_memory",
            "refresh_network_stats",
            "refresh_service_manager",
            "reduce_prewarm_when_display_busy",
            "skip_camera_prewarm_when_thermal_high",
        )
    }
}
