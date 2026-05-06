package com.memoos.maple

import android.content.Context
import com.memoos.action.AppIdMapping
import com.memoos.ebpf.EBPFEvent
import com.memoos.state.SystemStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class MapleScenario(
    val scenarioId: String,
    val description: String,
    val contextJson: String,
    val scenarioJson: String,
    val evidenceLines: List<String>,
    val topCategories: List<String>,
    val memoryPressure: String,
)

class MapleScenarioBuilder(private val context: Context) {
    fun build(
        events: List<EBPFEvent>,
        state: SystemStateSnapshot,
        scenarioId: String = "device_window",
        description: String = "Android device-side eBPF and system-state window",
        targetPackage: String? = null,
        targetCategories: List<String> = emptyList(),
    ): MapleScenario {
        val eventCounts = events.groupingBy { it.eventType }.eachCount()
        val categoryCounts = mutableMapOf<String, Int>()
        fun addCategory(category: String, count: Int = 1) {
            val cap = categoryCap(category)
            categoryCounts[category] = ((categoryCounts[category] ?: 0) + count).coerceAtMost(cap)
        }
        events.forEach { event ->
            addCategory(eventCategory(event), eventWeight(event))
            processCategory(event.comm ?: event.traceTask)?.let { addCategory(it, processWeight(it)) }
        }
        state.process.foregroundPackage?.let { addCategory(AppIdMapping.categoryForPackage(it), 4) }
        targetPackage?.let { addCategory(AppIdMapping.categoryForPackage(it), 480) }
        targetCategories.forEach { addCategory(it, 180) }
        if ((state.network.udpOutDatagrams ?: 0) + (state.network.udpInDatagrams ?: 0) > 0) {
            addCategory("Network IO", 3)
        }
        if (state.mediaDisplay.cameraServerPid != null) addCategory("Camera Service", 3)
        if (state.mediaDisplay.mediaCodecPid != null) addCategory("Media Codec", 3)
        if (state.mediaDisplay.surfaceFlingerPid != null || state.mediaDisplay.renderThreadObserved) addCategory("Display Composition", 3)
        if (state.memory.pressureLevel != "normal") addCategory("Memory Management", 4)
        if (state.battery.thermalRisk != "normal") addCategory("Power/Thermal Management", 4)

        val topCategories = categoryCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .ifEmpty { listOf("Android Service IPC") }
            .take(6)
        val evidenceLines = buildEvidenceLines(events, state, eventCounts, categoryCounts, targetPackage, targetCategories)
        val installedApps = AppIdMapping.installedAppsForMaple(context, topCategories)
        val historicalIds = topCategories.map { AppIdMapping.categoryId(it) }

        val contextObj = JSONObject()
            .put("historical_app_categories", JSONArray(topCategories))
            .put("historical_app_ids", JSONArray(historicalIds))
            .put("prediction_time", state.wallTime.ifBlank { ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) })
            .put("points_of_interest", JSONArray(pointsOfInterest(state, topCategories)))
            .put("installed_apps", installedApps)
            .put("system_evidence", JSONArray(compactEvidenceForMaple(evidenceLines)))
            .put("memory_pressure", memoryPressure(events, state))
            .put(
                "scheduler_goal",
                "Use device-side eBPF and system-state evidence to infer near-future Android app/resource demand, then choose safe warm-launch, cache, memory, network, camera/media, and UI scheduling actions.",
            )

        val summary = JSONObject()
            .put("event_counts", JSONObject(eventCounts.mapValues { it.value }))
            .put("category_counts", JSONObject(categoryCounts.mapValues { it.value }))
            .put("memory_pressure", contextObj.getString("memory_pressure"))
            .put("foreground_package", state.process.foregroundPackage ?: JSONObject.NULL)

        val scenarioObj = JSONObject()
            .put("id", scenarioId)
            .put("description", description)
            .put("source", "android_device_side")
            .put("context", contextObj)
            .put("ebpf_summary", summary)

        val root = JSONObject()
            .put("schema_version", "memo.maple_scenarios.v1")
            .put("generated_at", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("scenarios", JSONArray().put(scenarioObj))

        return MapleScenario(
            scenarioId = scenarioId,
            description = description,
            contextJson = contextObj.toString(),
            scenarioJson = root.toString(2),
            evidenceLines = evidenceLines,
            topCategories = topCategories,
            memoryPressure = contextObj.getString("memory_pressure"),
        )
    }

    private fun buildEvidenceLines(
        events: List<EBPFEvent>,
        state: SystemStateSnapshot,
        eventCounts: Map<String, Int>,
        categoryCounts: Map<String, Int>,
        targetPackage: String?,
        targetCategories: List<String>,
    ): List<String> {
        val lines = mutableListOf<String>()
        lines += "${events.size} device-side eBPF records in the observed Android window"
        targetPackage?.let {
            lines += "observed user action target app=$it categories=${targetCategories.joinToString()}"
        }
        eventCounts.entries.sortedByDescending { it.value }.take(8).forEach {
            lines += "event_type ${it.key}: count=${it.value}"
        }
        categoryCounts.entries.sortedByDescending { it.value }.take(8).forEach {
            lines += "MAPLE evidence/resource category ${it.key}: count=${it.value}"
        }
        events.mapNotNull { it.comm ?: it.traceTask }
            .filter {
                it !in setOf("cat", "grep", "sh", "su", "toybox", "bpftrace", "sleep", "pkill") &&
                    !it.startsWith("binder:") &&
                    !it.startsWith("swapper/")
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(6)
            .forEach { lines += "process ${it.key}: count=${it.value}" }
        lines += state.evidenceLines()
        return lines.distinct().take(24)
    }

    private fun eventCategory(event: EBPFEvent): String {
        return when (event.eventType) {
            "MEMO_BINDER" -> "Android Service IPC"
            "MEMO_OPENAT" -> fileCategory(event.evidenceCategory)
            "MEMO_SENDTO", "MEMO_RECVFROM" -> "Network IO"
            "MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "MEMO_KSWAPD_WAKE", "MEMO_MEMORY" -> "Memory Management"
            "MEMO_INPUT" -> "Input Interaction"
            "MEMO_SCHED", "MEMO_PROCESS_FORK", "MEMO_PROCESS_EXIT" -> "App Process Runtime"
            else -> "Other File Access"
        }
    }

    private fun eventWeight(event: EBPFEvent): Int {
        return when (event.eventType) {
            "MEMO_SENDTO", "MEMO_RECVFROM" -> 3
            "MEMO_BINDER" -> 2
            "MEMO_INPUT" -> 4
            "MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "MEMO_KSWAPD_WAKE", "MEMO_MEMORY" -> 8
            "MEMO_SCHED", "MEMO_PROCESS_FORK", "MEMO_PROCESS_EXIT" -> 1
            "MEMO_OPENAT" -> 1
            else -> 1
        }
    }

    private fun processWeight(category: String): Int {
        return when (category) {
            "Display Composition" -> 3
            "Camera Service", "Media Codec", "Network IO" -> 3
            "Android System Services" -> 2
            else -> 1
        }
    }

    private fun categoryCap(category: String): Int {
        return when (category) {
            "App Process Runtime" -> 180
            "Native Runtime Loading", "Framework Loading", "System Property Access", "APEX Runtime Loading" -> 180
            "Other File Access", "Process State Inspection", "Kernel Trace Setup" -> 120
            "Network IO" -> 720
            "Android Service IPC", "Android System Services" -> 360
            "Display Composition", "Input Interaction", "Camera Service", "Media Codec" -> 420
            "Memory Management", "Power/Thermal Management" -> 360
            else -> 240
        }
    }

    private fun compactEvidenceForMaple(lines: List<String>): List<String> {
        return lines
            .filterNot { it.startsWith("process swapper/") || it.startsWith("process sleep") || it.startsWith("process pkill") }
            .take(14)
    }

    private fun fileCategory(raw: String?): String {
        return when (raw) {
            "native_library" -> "Native Runtime Loading"
            "java_framework_or_classpath" -> "Framework Loading"
            "android_property_area" -> "System Property Access"
            "apex_runtime_asset" -> "APEX Runtime Loading"
            "sysfs_kernel_state" -> "Kernel Trace Setup"
            "procfs_process_state" -> "Process State Inspection"
            "device_or_ipc_node" -> "Device/IPC Node Access"
            "database" -> "Database"
            "dex_or_oat" -> "Dex/OAT Loading"
            "cache" -> "Cache/File Cache"
            else -> "Other File Access"
        }
    }

    private fun processCategory(name: String?): String? {
        val value = name?.lowercase() ?: return null
        return when {
            value.contains("surfaceflinger") || value.contains("renderthread") || value.contains("gralloc") -> "Display Composition"
            value.contains("camera") -> "Camera Service"
            value.contains("media") || value.contains("codec") || value.contains("audio") -> "Media Codec"
            value.contains("netd") || value.contains("dns") -> "Network IO"
            value.contains("lmkd") || value.contains("kswapd") -> "Memory Management"
            value.contains("system_server") || value.contains("servicemanager") -> "Android System Services"
            value.contains("zygote") || value.contains("app_process") -> "App Process Runtime"
            value.contains("input") -> "Input Interaction"
            else -> null
        }
    }

    private fun memoryPressure(events: List<EBPFEvent>, state: SystemStateSnapshot): String {
        val reclaim = events.count { it.eventType in setOf("MEMO_RECLAIM_BEGIN", "MEMO_RECLAIM_END", "MEMO_KSWAPD_WAKE") }
        return when {
            state.memory.pressureLevel == "critical" -> "critical: low available memory or memory PSI pressure observed; reclaim_events=$reclaim"
            state.memory.pressureLevel == "elevated" || reclaim > 0 -> "elevated: memory pressure or reclaim activity observed; reclaim_events=$reclaim"
            else -> "normal: no direct reclaim pressure was observed"
        }
    }

    private fun pointsOfInterest(state: SystemStateSnapshot, categories: List<String>): List<String> {
        val points = mutableListOf<String>()
        state.process.foregroundPackage?.let { points += "foreground app $it" }
        if ("Camera Service" in categories) points += "camera or photo workflow"
        if ("Media Codec" in categories) points += "video/audio playback workflow"
        if ("Network IO" in categories) points += "active network communication"
        if ("Display Composition" in categories) points += "active UI rendering or scrolling"
        if (state.battery.thermalRisk != "normal") points += "thermal-sensitive scheduling"
        if (state.memory.pressureLevel != "normal") points += "memory-pressure-aware scheduling"
        return points.take(6)
    }

}
