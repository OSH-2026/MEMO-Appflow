package com.memoos.perf

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

data class LatencyStage(
    val name: String,
    val durationMs: Long,
)

data class PipelineLatency(
    val mode: String,
    val startedAtMs: Long,
    val totalMs: Long,
    val foregroundMs: Long,
    val realtimeBudgetMs: Long,
    val parsedEvents: Int,
    val mapleTimedOut: Boolean,
    val stages: List<LatencyStage>,
) {
    val realtimeStatus: String
        get() = when {
            mapleTimedOut -> "timed_out"
            foregroundMs > realtimeBudgetMs -> "too_slow_for_realtime"
            foregroundMs > realtimeBudgetMs / 2 -> "degraded"
            else -> "ok"
        }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("mode", mode)
            .put("started_at_ms", startedAtMs)
            .put("total_ms", totalMs)
            .put("foreground_ms", foregroundMs)
            .put("realtime_budget_ms", realtimeBudgetMs)
            .put("parsed_events", parsedEvents)
            .put("maple_timed_out", mapleTimedOut)
            .put("realtime_status", realtimeStatus)
            .put(
                "stages",
                JSONArray(stages.map { stage ->
                    JSONObject()
                        .put("name", stage.name)
                        .put("duration_ms", stage.durationMs)
                }),
            )
    }

    fun summaryLine(): String {
        val mapleMs = stages.firstOrNull { it.name == "maple_inference" }?.durationMs
        val maplePart = mapleMs?.let { ", MAPLE=${formatMs(it)}" }.orEmpty()
        val totalPart = if (mapleMs != null) ", total=${formatMs(totalMs)}" else ""
        return "latency=$realtimeStatus foreground=${formatMs(foregroundMs)}$maplePart$totalPart budget=${formatMs(realtimeBudgetMs)}"
    }

    companion object {
        const val REALTIME_BUDGET_MS = 60_000L

        fun formatMs(value: Long): String {
            return if (value >= 1_000L) {
                val seconds = value / 1000.0
                String.format(java.util.Locale.US, "%.1fs", seconds)
            } else {
                "${value}ms"
            }
        }
    }
}

class PipelineTimer(private val mode: String) {
    private val startedElapsed = SystemClock.elapsedRealtime()
    private val startedWall = System.currentTimeMillis()
    private val stages = linkedMapOf<String, Long>()

    fun <T> measure(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            stages[name] = (stages[name] ?: 0L) + (SystemClock.elapsedRealtime() - start)
        }
    }

    fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedElapsed

    fun snapshot(parsedEvents: Int, mapleTimedOut: Boolean): PipelineLatency {
        val stageList = stages.map { LatencyStage(it.key, it.value) }
        val asyncMapleMs = stageList.firstOrNull { it.name == "maple_inference" }?.durationMs ?: 0L
        val total = elapsedMs()
        return PipelineLatency(
            mode = mode,
            startedAtMs = startedWall,
            totalMs = total,
            foregroundMs = (total - asyncMapleMs).coerceAtLeast(0L),
            realtimeBudgetMs = PipelineLatency.REALTIME_BUDGET_MS,
            parsedEvents = parsedEvents,
            mapleTimedOut = mapleTimedOut,
            stages = stageList,
        )
    }
}
