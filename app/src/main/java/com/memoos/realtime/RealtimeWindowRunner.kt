package com.memoos.realtime

import android.content.Context
import com.memoos.action.AppIdMapping
import com.memoos.device.DevicePaths
import com.memoos.device.EBPFCapabilityProbe
import com.memoos.device.RootShell
import com.memoos.ebpf.DeviceCollectorDeployer
import com.memoos.ebpf.EBPFEvent
import com.memoos.ebpf.EBPFTraceParser
import com.memoos.maple.CompressedEbpfSignal
import com.memoos.maple.MapleAppTimelineEntry
import com.memoos.maple.MapleTimelineWindow
import com.memoos.state.SystemStateCollector
import com.memoos.state.SystemStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class RealtimeWindow(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val rawTracePath: String,
    val appSamplesPath: String,
    val beforeState: SystemStateSnapshot,
    val afterState: SystemStateSnapshot,
    val events: List<EBPFEvent>,
    val appTimeline: List<MapleAppTimelineEntry>,
    val timelineWindows: List<MapleTimelineWindow>,
) {
    fun summary(): RealtimeWindowSummary {
        val categories = appTimeline
            .flatMap { it.categories.ifEmpty { listOf(AppIdMapping.categoryForPackage(it.packageName)) } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(6)
        val topSignals = timelineWindows.flatMap { it.compressedEbpf }
            .sortedByDescending { it.count }
            .take(8)
            .map { "${it.eventType}:${it.count}" }
        return RealtimeWindowSummary(
            startMs = startedAtMs,
            endMs = endedAtMs,
            appLabels = appTimeline.map { it.label }.distinct().take(12),
            topEbpfSignals = topSignals,
            topCategories = categories,
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("started_at_ms", startedAtMs)
            .put("ended_at_ms", endedAtMs)
            .put("duration_ms", endedAtMs - startedAtMs)
            .put("raw_trace_path", rawTracePath)
            .put("app_samples_path", appSamplesPath)
            .put("raw_ebpf_events", events.size)
            .put("app_segments", appTimeline.size)
            .put("timeline_windows", timelineWindows.size)
            .put("compressed_ebpf_signals", timelineWindows.sumOf { it.compressedEbpf.size })
            .put("summary", summary().toJson())
    }
}

class RealtimeWindowRunner(private val context: Context) {
    fun collectWindow(durationMs: Long): RealtimeWindow {
        val capability = EBPFCapabilityProbe.probe()
        require(capability.canRunRawCollector) {
            "raw eBPF collector is required for realtime mode; ${capability.notes.joinToString("; ")}"
        }
        DeviceCollectorDeployer(context).ensureRawCollectorExecutable()
        RootShell.run("mkdir -p '${DevicePaths.LOG_DIR}' '${DevicePaths.MEMO_PUBLIC_ROOT}/realtime'", requireRoot = true, timeoutMs = 3_000L)

        val startedAtMs = System.currentTimeMillis()
        val rawTracePath = "${DevicePaths.LOG_DIR}/realtime_${startedAtMs}.trace"
        val appSamplesPath = "${DevicePaths.LOG_DIR}/realtime_${startedAtMs}_apps.tsv"
        val flagPath = "${DevicePaths.LOG_DIR}/realtime_${startedAtMs}.active"
        val before = SystemStateCollector(context).collect()
        val collector = capability.rawCollectorPath ?: DevicePaths.RAW_COLLECTOR
        val durationSec = ((durationMs + 999L) / 1000L).coerceAtLeast(10L)

        RootShell.run(
            "rm -f '$rawTracePath' '$appSamplesPath'; : > '$flagPath'; " +
                "($collector --duration-sec ${durationSec + COLLECTOR_SLACK_SEC} --max-events $MAX_EVENTS --output '$rawTracePath' 2>&1) >/dev/null 2>&1 & echo \$! > '${flagPath}.collector.pid'",
            requireRoot = true,
            timeoutMs = 4_000L,
        )
        waitForCollector(rawTracePath)
        installAndStartSampler(flagPath, appSamplesPath)
        try {
            Thread.sleep(durationMs)
        } finally {
            RootShell.run("rm -f '$flagPath'; pkill -TERM -f memo_libbpf_collector 2>/dev/null", requireRoot = true, timeoutMs = 3_000L)
            Thread.sleep(900L)
        }

        val endedAtMs = System.currentTimeMillis()
        val rawLines = readMemoLines(rawTracePath)
        val events = EBPFTraceParser.parseLines(rawLines.asSequence()).take(MAX_EVENTS).toList()
        require(events.isNotEmpty()) {
            "realtime window produced zero eBPF events; trace=$rawTracePath"
        }
        val samples = readAppSamples(appSamplesPath)
        val appTimeline = buildAppTimeline(samples, startedAtMs, endedAtMs)
        val timeline = buildTimelineWindows(appTimeline, events, startedAtMs, endedAtMs)
        val after = SystemStateCollector(context).collect()

        cleanupOldRealtimeFiles()
        return RealtimeWindow(startedAtMs, endedAtMs, rawTracePath, appSamplesPath, before, after, events, appTimeline, timeline)
    }

    private fun installAndStartSampler(flagPath: String, appSamplesPath: String) {
        val scriptFile = File(context.filesDir, "realtime_sampler_${System.currentTimeMillis()}.sh")
        scriptFile.writeText(samplerScript(flagPath, appSamplesPath))
        val result = RootShell.run(
            "chmod 0700 '${scriptFile.absolutePath}'; nohup sh '${scriptFile.absolutePath}' >/dev/null 2>&1 & echo \$! > '${flagPath}.sampler.pid'",
            requireRoot = true,
            timeoutMs = 4_000L,
        )
        require(result.ok) {
            "realtime foreground app sampler failed to start: ${result.stderr.ifBlank { result.stdout }.take(300)}"
        }
    }

    private fun samplerScript(flagPath: String, appSamplesPath: String): String {
        return """
            #!/system/bin/sh
            extract_pkg() {
              printf '%s\n' "${'$'}1" | sed -n 's/.* u[0-9][0-9]* \([^/ ]*\)\/.*/\1/p'
            }
            while [ -f '$flagPath' ]; do
              ts=${'$'}(date +%s%3N)
              line=${'$'}(dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity=')
              if [ -z "${'$'}line" ]; then line=${'$'}(dumpsys activity activities 2>/dev/null | grep -m 1 'ResumedActivity:'); fi
              if [ -z "${'$'}line" ]; then line=${'$'}(dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus='); fi
              if [ -z "${'$'}line" ]; then line=${'$'}(dumpsys window 2>/dev/null | grep -m 1 'mFocusedApp='); fi
              pkg=${'$'}(extract_pkg "${'$'}line")
              if [ -n "${'$'}pkg" ]; then printf '%s\t%s\t%s\n' "${'$'}ts" "${'$'}pkg" "${'$'}line" >> '$appSamplesPath'; fi
              sleep 1
            done
        """.trimIndent() + "\n"
    }

    private fun readMemoLines(tracePath: String): List<String> {
        return RootShell.run(
            "sed -n '/^MEMO/p' '$tracePath' 2>/dev/null | head -n $MAX_EVENTS",
            requireRoot = true,
            timeoutMs = 12_000L,
        ).stdout.lineSequence().filter { it.isNotBlank() }.toList()
    }

    private fun readAppSamples(path: String): List<AppSample> {
        return RootShell.run("cat '$path' 2>/dev/null | head -n $MAX_APP_SAMPLES", requireRoot = true, timeoutMs = 8_000L)
            .stdout
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                val timeMs = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val pkg = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AppSample(timeMs, pkg)
            }
            .filter { it.packageName != context.packageName }
            .toList()
    }

    private fun buildAppTimeline(samples: List<AppSample>, startedAtMs: Long, endedAtMs: Long): List<MapleAppTimelineEntry> {
        if (samples.isEmpty()) return emptyList()
        val profiles = AppIdMapping.scanInstalledApps(context).associateBy { it.packageName }
        val segments = mutableListOf<AppSegment>()
        samples.sortedBy { it.timeMs }.forEach { sample ->
            val last = segments.lastOrNull()
            if (last?.packageName == sample.packageName) {
                last.endMs = sample.timeMs
            } else {
                if (last != null && last.endMs <= last.startMs) last.endMs = sample.timeMs
                segments += AppSegment(sample.packageName, sample.timeMs, sample.timeMs)
            }
        }
        segments.lastOrNull()?.let { if (it.endMs <= it.startMs) it.endMs = endedAtMs }
        return segments.mapIndexed { index, segment ->
            val profile = profiles[segment.packageName]
            val label = profile?.label ?: segment.packageName.substringAfterLast('.')
            val categories = profile?.inferredCategories?.toList()?.ifEmpty { listOf(AppIdMapping.categoryForPackage(segment.packageName)) }
                ?: listOf(AppIdMapping.categoryForPackage(segment.packageName))
            MapleAppTimelineEntry(
                index = index + 1,
                packageName = segment.packageName,
                label = label,
                categories = categories,
                startWallTimeMs = segment.startMs.coerceAtLeast(startedAtMs),
                endWallTimeMs = segment.endMs.coerceAtLeast(segment.startMs + 1L),
                launchState = "OBSERVED_REALTIME",
                totalTimeMs = null,
                waitTimeMs = null,
                observed = true,
            )
        }
    }

    private fun buildTimelineWindows(
        appTimeline: List<MapleAppTimelineEntry>,
        events: List<EBPFEvent>,
        startedAtMs: Long,
        endedAtMs: Long,
    ): List<MapleTimelineWindow> {
        val chunks = appTimeline.chunked(TIMELINE_WINDOW_APPS).ifEmpty { listOf(emptyList()) }
        val alignmentStart = appTimeline.mapNotNull { it.startWallTimeMs }.minOrNull() ?: startedAtMs
        val alignmentEnd = appTimeline.mapNotNull { it.endWallTimeMs }.maxOrNull() ?: endedAtMs
        val timedEvents = events.mapIndexed { eventIndex, event ->
            val rawWallTimeMs = event.wallTimeMs()
            TimedEvent(
                event = event,
                wallTimeMs = if (rawWallTimeMs != null && rawWallTimeMs in alignmentStart..alignmentEnd) {
                    rawWallTimeMs
                } else {
                    estimateEventTimeMs(eventIndex, events.size, alignmentStart, alignmentEnd)
                },
            )
        }
        return chunks.mapIndexed { index, apps ->
            val start = apps.mapNotNull { it.startWallTimeMs }.minOrNull() ?: startedAtMs
            val end = apps.mapNotNull { it.endWallTimeMs }.maxOrNull() ?: endedAtMs
            val windowEvents = timedEvents.filter { it.wallTimeMs in start..end }.map { it.event }
            val counts = windowEvents.groupingBy { it.eventType }.eachCount()
            val totals = counterTotals(windowEvents)
            MapleTimelineWindow(
                index = index + 1,
                startWallTimeMs = start,
                endWallTimeMs = end.coerceAtLeast(start + 1L),
                apps = apps,
                ebpfEventCounts = counts,
                ebpfCounterTotals = totals,
                compressedEbpf = compressEbpfSignals(start, end.coerceAtLeast(start + 1L), counts, totals),
            )
        }
    }

    private fun waitForCollector(tracePath: String) {
        val deadline = System.currentTimeMillis() + COLLECTOR_ATTACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (RootShell.run("grep -q 'collector_started' '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 1_000L).ok) return
            Thread.sleep(250L)
        }
        val head = RootShell.run("head -n 20 '$tracePath' 2>/dev/null", requireRoot = true, timeoutMs = 3_000L).stdout
        error("raw eBPF collector did not attach for realtime mode; trace=$tracePath; head=${head.take(300)}")
    }

    private fun cleanupOldRealtimeFiles() {
        RootShell.run(
            "ls -1t '${DevicePaths.LOG_DIR}'/realtime_* 2>/dev/null | tail -n +$MAX_RETAINED_FILES | xargs -r rm -f",
            requireRoot = true,
            timeoutMs = 3_000L,
        )
    }

    private fun EBPFEvent.wallTimeMs(): Long? {
        timestampNs?.let {
            if (it >= EPOCH_NS_THRESHOLD) return it / 1_000_000L
        }
        timestampS?.let {
            if (it >= EPOCH_SECONDS_THRESHOLD) return (it * 1000.0).toLong()
        }
        return null
    }

    private fun estimateEventTimeMs(index: Int, total: Int, startedAtMs: Long, endedAtMs: Long): Long {
        if (total <= 1) return startedAtMs
        val span = (endedAtMs - startedAtMs).coerceAtLeast(1L)
        val offset = ((span.toDouble() * index.toDouble()) / (total - 1).toDouble()).toLong()
        return startedAtMs + offset.coerceIn(0L, span)
    }

    private fun counterTotals(events: List<EBPFEvent>): Map<String, Long> {
        val totals = linkedMapOf<String, Long>()
        events.forEach { event ->
            val count = event.extra["arg1"]?.toLongOrNull() ?: return@forEach
            totals[event.eventType] = maxOf(totals[event.eventType] ?: 0L, count)
        }
        return totals
    }

    private fun compressEbpfSignals(
        startWallTimeMs: Long,
        endWallTimeMs: Long,
        eventCounts: Map<String, Int>,
        counterTotals: Map<String, Long>,
    ): List<CompressedEbpfSignal> {
        val durationSec = ((endWallTimeMs - startWallTimeMs).coerceAtLeast(1L)).toDouble() / 1000.0
        val keys = (counterTotals.keys + eventCounts.keys).filter { it != "MEMO_STATUS" }.distinct()
        return keys.mapNotNull { eventType ->
            val count = counterTotals[eventType] ?: eventCounts[eventType]?.toLong() ?: return@mapNotNull null
            if (count <= 0L) return@mapNotNull null
            CompressedEbpfSignal(eventType, eventDetail(eventType), count, count / durationSec)
        }.sortedWith(compareByDescending<CompressedEbpfSignal> { it.count }.thenBy { it.eventType })
    }

    private fun eventDetail(eventType: String): String {
        return when (eventType) {
            "MEMO_BINDER" -> "binder_transaction"
            "MEMO_OPENAT" -> "openat"
            "MEMO_SENDTO" -> "sendto"
            "MEMO_RECVFROM" -> "recvfrom"
            "MEMO_SCHED" -> "sched_switch_or_wakeup"
            "MEMO_PROCESS_FORK" -> "process_fork"
            "MEMO_PROCESS_EXIT" -> "process_exit"
            "MEMO_PROCESS_EXEC" -> "process_exec"
            "MEMO_MEMORY" -> "reclaim_or_kswapd"
            "MEMO_INPUT" -> "input_event"
            else -> eventType.removePrefix("MEMO_").lowercase(Locale.US)
        }
    }

    private data class TimedEvent(val event: EBPFEvent, val wallTimeMs: Long)
    private data class AppSample(val timeMs: Long, val packageName: String)
    private data class AppSegment(val packageName: String, val startMs: Long, var endMs: Long)

    private companion object {
        const val MAX_EVENTS = 24_000
        const val MAX_APP_SAMPLES = 18_000
        const val TIMELINE_WINDOW_APPS = 5
        const val COLLECTOR_SLACK_SEC = 8L
        const val COLLECTOR_ATTACH_TIMEOUT_MS = 10_000L
        const val EPOCH_SECONDS_THRESHOLD = 946684800.0
        const val EPOCH_NS_THRESHOLD = 946684800_000_000_000L
        const val MAX_RETAINED_FILES = 41
    }
}
