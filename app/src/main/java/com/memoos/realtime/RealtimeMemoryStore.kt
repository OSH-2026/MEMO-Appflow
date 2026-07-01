package com.memoos.realtime

import android.content.Context
import com.memoos.action.AppIdMapping
import com.memoos.maple.MapleAppTimelineEntry
import com.memoos.maple.MapleTimelineWindow
import com.memoos.state.SystemStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

data class RealtimeMemory(
    val updatedAtMs: Long,
    val windowCount: Long,
    val halfLifeMinutes: Double,
    val appCategoryEma: Map<String, Double>,
    val appTransitionEma: Map<String, Double>,
    val resourcePressureEma: Map<String, Double>,
    val recentWindows: List<RealtimeWindowSummary>,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("updated_at_ms", updatedAtMs)
            .put("window_count", windowCount)
            .put("ema_half_life_min", halfLifeMinutes)
            .put("app_category_ema", JSONObject(appCategoryEma))
            .put("app_transition_ema", JSONObject(appTransitionEma))
            .put("resource_pressure_ema", JSONObject(resourcePressureEma))
            .put("recent_windows", JSONArray(recentWindows.map { it.toJson() }))
            .put("memory_policy", "bounded EMA plus a fixed-size recent-window ring buffer; raw history is not appended to MAPLE input")
    }
}

data class RealtimeWindowSummary(
    val startMs: Long,
    val endMs: Long,
    val appLabels: List<String>,
    val topEbpfSignals: List<String>,
    val topCategories: List<String>,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("start_ms", startMs)
            .put("end_ms", endMs)
            .put("app_labels", JSONArray(appLabels))
            .put("top_ebpf_signals", JSONArray(topEbpfSignals))
            .put("top_categories", JSONArray(topCategories))
    }
}

class RealtimeMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("memo_realtime_memory", Context.MODE_PRIVATE)

    fun load(): RealtimeMemory {
        val raw = prefs.getString("memory", "") ?: ""
        if (raw.isBlank()) return emptyMemory()
        return try {
            fromJson(JSONObject(raw))
        } catch (_: Exception) {
            emptyMemory()
        }
    }

    fun update(window: RealtimeWindow, state: SystemStateSnapshot): RealtimeMemory {
        val previous = load()
        val alpha = 1.0 - 2.0.pow(-WINDOW_MINUTES / HALF_LIFE_MINUTES)
        val next = RealtimeMemory(
            updatedAtMs = System.currentTimeMillis(),
            windowCount = previous.windowCount + 1,
            halfLifeMinutes = HALF_LIFE_MINUTES,
            appCategoryEma = mergeEma(previous.appCategoryEma, categoryFeatures(window), alpha),
            appTransitionEma = mergeEma(previous.appTransitionEma, transitionFeatures(window.appTimeline), alpha),
            resourcePressureEma = mergeEma(previous.resourcePressureEma, resourceFeatures(window, state), alpha),
            recentWindows = (previous.recentWindows + window.summary()).takeLast(MAX_RECENT_WINDOWS),
        )
        prefs.edit().putString("memory", next.toJson().toString()).apply()
        return next
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun categoryFeatures(window: RealtimeWindow): Map<String, Double> {
        val total = window.appTimeline.size.coerceAtLeast(1).toDouble()
        return window.appTimeline
            .flatMap { app -> app.categories.ifEmpty { listOf(AppIdMapping.categoryForPackage(app.packageName)) }.take(3) }
            .groupingBy { it }
            .eachCount()
            .mapValues { it.value / total }
    }

    private fun transitionFeatures(apps: List<MapleAppTimelineEntry>): Map<String, Double> {
        if (apps.size < 2) return emptyMap()
        val transitions = apps.zipWithNext().map { (a, b) ->
            "${a.categories.firstOrNull() ?: AppIdMapping.categoryForPackage(a.packageName)}->${b.categories.firstOrNull() ?: AppIdMapping.categoryForPackage(b.packageName)}"
        }
        val total = transitions.size.coerceAtLeast(1).toDouble()
        return transitions.groupingBy { it }.eachCount().mapValues { it.value / total }
    }

    private fun resourceFeatures(window: RealtimeWindow, state: SystemStateSnapshot): Map<String, Double> {
        val counts = window.events.groupingBy { it.eventType }.eachCount()
        val maxCount = counts.values.maxOrNull()?.coerceAtLeast(1)?.toDouble() ?: 1.0
        val features = linkedMapOf<String, Double>()
        features["network"] = ((counts["MEMO_SENDTO"] ?: 0) + (counts["MEMO_RECVFROM"] ?: 0)) / maxCount
        features["binder_service"] = (counts["MEMO_BINDER"] ?: 0) / maxCount
        features["display_ui"] = (counts["MEMO_SCHED"] ?: 0) / maxCount
        features["process_runtime"] = ((counts["MEMO_PROCESS_EXEC"] ?: 0) + (counts["MEMO_PROCESS_FORK"] ?: 0)) / maxCount
        features["memory"] = when (state.memory.pressureLevel) {
            "critical" -> 1.0
            "elevated" -> 0.6
            else -> (counts["MEMO_MEMORY"] ?: 0) / maxCount
        }
        features["thermal"] = when (state.battery.thermalRisk) {
            "critical" -> 1.0
            "elevated" -> 0.6
            else -> 0.0
        }
        return features
    }

    private fun mergeEma(previous: Map<String, Double>, current: Map<String, Double>, alpha: Double): Map<String, Double> {
        val keys = previous.keys + current.keys
        return keys.associateWith { key ->
            ((previous[key] ?: 0.0) * (1.0 - alpha)) + ((current[key] ?: 0.0) * alpha)
        }
            .filterValues { it >= MIN_RETAINED_VALUE }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_EMA_KEYS)
            .associate { it.key to it.value }
    }

    private fun emptyMemory(): RealtimeMemory {
        return RealtimeMemory(
            updatedAtMs = 0L,
            windowCount = 0L,
            halfLifeMinutes = HALF_LIFE_MINUTES,
            appCategoryEma = emptyMap(),
            appTransitionEma = emptyMap(),
            resourcePressureEma = emptyMap(),
            recentWindows = emptyList(),
        )
    }

    private fun fromJson(obj: JSONObject): RealtimeMemory {
        val windows = obj.optJSONArray("recent_windows")
        return RealtimeMemory(
            updatedAtMs = obj.optLong("updated_at_ms", 0L),
            windowCount = obj.optLong("window_count", 0L),
            halfLifeMinutes = obj.optDouble("ema_half_life_min", HALF_LIFE_MINUTES),
            appCategoryEma = readDoubleMap(obj.optJSONObject("app_category_ema")),
            appTransitionEma = readDoubleMap(obj.optJSONObject("app_transition_ema")),
            resourcePressureEma = readDoubleMap(obj.optJSONObject("resource_pressure_ema")),
            recentWindows = if (windows == null) emptyList() else (0 until windows.length()).mapNotNull { idx ->
                val item = windows.optJSONObject(idx) ?: return@mapNotNull null
                RealtimeWindowSummary(
                    startMs = item.optLong("start_ms"),
                    endMs = item.optLong("end_ms"),
                    appLabels = item.optJSONArray("app_labels")?.toStringList().orEmpty(),
                    topEbpfSignals = item.optJSONArray("top_ebpf_signals")?.toStringList().orEmpty(),
                    topCategories = item.optJSONArray("top_categories")?.toStringList().orEmpty(),
                )
            },
        )
    }

    private fun readDoubleMap(obj: JSONObject?): Map<String, Double> {
        if (obj == null) return emptyMap()
        return obj.keys().asSequence().associateWith { key -> obj.optDouble(key, 0.0) }
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }
    }

    private companion object {
        const val WINDOW_MINUTES = 3.0
        const val HALF_LIFE_MINUTES = 30.0
        const val MAX_EMA_KEYS = 32
        const val MAX_RECENT_WINDOWS = 8
        const val MIN_RETAINED_VALUE = 0.001
    }
}
