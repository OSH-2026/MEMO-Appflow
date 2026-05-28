package com.memoos.perf

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.memoos.device.RootShell
import org.json.JSONObject
import kotlin.math.max

data class CpuSnapshot(
    val totalJiffies: Long,
    val busyJiffies: Long,
    val idleJiffies: Long,
    val iowaitJiffies: Long,
    val procsRunning: Int?,
    val procsBlocked: Int?,
)

data class PressureSnapshot(
    val wallTimeMs: Long,
    val elapsedMs: Long,
    val memAvailableKb: Long?,
    val swapFreeKb: Long?,
    val pgscanDirect: Long?,
    val pgscanKswapd: Long?,
    val compactStall: Long?,
    val psiSomeAvg10: Double?,
    val psiFullAvg10: Double?,
    val udpInDatagrams: Long?,
    val udpOutDatagrams: Long?,
    val udpInErrors: Long?,
    val batteryLevelPct: Double?,
    val batteryTempC: Double?,
    val batteryCurrentUa: Long?,
    val cpu: CpuSnapshot?,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("wall_time_ms", wallTimeMs)
            .put("elapsed_ms", elapsedMs)
            .putNullable("mem_available_kb", memAvailableKb)
            .putNullable("swap_free_kb", swapFreeKb)
            .putNullable("pgscan_direct", pgscanDirect)
            .putNullable("pgscan_kswapd", pgscanKswapd)
            .putNullable("compact_stall", compactStall)
            .putNullable("psi_some_avg10", psiSomeAvg10)
            .putNullable("psi_full_avg10", psiFullAvg10)
            .putNullable("udp_in_datagrams", udpInDatagrams)
            .putNullable("udp_out_datagrams", udpOutDatagrams)
            .putNullable("udp_in_errors", udpInErrors)
            .putNullable("battery_level_pct", batteryLevelPct)
            .putNullable("battery_temp_c", batteryTempC)
            .putNullable("battery_current_ua", batteryCurrentUa)
            .put(
                "cpu",
                cpu?.let {
                    JSONObject()
                        .put("total_jiffies", it.totalJiffies)
                        .put("busy_jiffies", it.busyJiffies)
                        .put("idle_jiffies", it.idleJiffies)
                        .put("iowait_jiffies", it.iowaitJiffies)
                        .putNullable("procs_running", it.procsRunning)
                        .putNullable("procs_blocked", it.procsBlocked)
                } ?: JSONObject.NULL,
            )
    }
}

data class PressureDelta(
    val durationMs: Long,
    val memAvailableDropKb: Long?,
    val swapFreeDropKb: Long?,
    val pgscanDirectDelta: Long?,
    val pgscanKswapdDelta: Long?,
    val compactStallDelta: Long?,
    val psiSomeAvg10Delta: Double?,
    val psiFullAvg10Delta: Double?,
    val udpInDelta: Long?,
    val udpOutDelta: Long?,
    val udpErrorDelta: Long?,
    val batteryTempDeltaC: Double?,
    val cpuBusyPct: Double?,
    val iowaitPct: Double?,
) {
    val reclaimDelta: Long?
        get() = listOfNotNull(pgscanDirectDelta, pgscanKswapdDelta).takeIf { it.isNotEmpty() }?.sum()

    val pressureScore: Double
        get() {
            var score = 0.0
            memAvailableDropKb?.let { score += max(0.0, it / 1024.0) * 0.06 }
            reclaimDelta?.let { score += max(0.0, it.toDouble()) * 0.04 }
            compactStallDelta?.let { score += max(0.0, it.toDouble()) * 0.5 }
            psiSomeAvg10Delta?.let { score += max(0.0, it) * 8.0 }
            psiFullAvg10Delta?.let { score += max(0.0, it) * 14.0 }
            cpuBusyPct?.let { score += max(0.0, it) * 0.35 }
            iowaitPct?.let { score += max(0.0, it) * 1.2 }
            batteryTempDeltaC?.let { score += max(0.0, it) * 8.0 }
            udpErrorDelta?.let { score += max(0.0, it.toDouble()) * 0.2 }
            return score
        }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("duration_ms", durationMs)
            .putNullable("mem_available_drop_kb", memAvailableDropKb)
            .putNullable("swap_free_drop_kb", swapFreeDropKb)
            .putNullable("pgscan_direct_delta", pgscanDirectDelta)
            .putNullable("pgscan_kswapd_delta", pgscanKswapdDelta)
            .putNullable("reclaim_delta", reclaimDelta)
            .putNullable("compact_stall_delta", compactStallDelta)
            .putNullable("psi_some_avg10_delta", psiSomeAvg10Delta)
            .putNullable("psi_full_avg10_delta", psiFullAvg10Delta)
            .putNullable("udp_in_delta", udpInDelta)
            .putNullable("udp_out_delta", udpOutDelta)
            .putNullable("udp_error_delta", udpErrorDelta)
            .putNullable("battery_temp_delta_c", batteryTempDeltaC)
            .putNullable("cpu_busy_pct", cpuBusyPct)
            .putNullable("iowait_pct", iowaitPct)
            .put("pressure_score_lower_is_better", pressureScore)
    }
}

data class LaunchMetrics(
    val totalTimeMs: Long?,
    val waitTimeMs: Long?,
    val thisTimeMs: Long?,
    val status: String?,
    val launchState: String?,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .putNullable("total_time_ms", totalTimeMs)
            .putNullable("wait_time_ms", waitTimeMs)
            .putNullable("this_time_ms", thisTimeMs)
            .putNullable("status", status)
            .putNullable("launch_state", launchState)
    }
}

data class GfxMetrics(
    val totalFrames: Long?,
    val jankyFrames: Long?,
    val p90Ms: Long?,
    val p95Ms: Long?,
    val p99Ms: Long?,
) {
    val jankRatePct: Double?
        get() = if (totalFrames != null && totalFrames > 0 && jankyFrames != null) {
            jankyFrames * 100.0 / totalFrames
        } else {
            null
        }

    fun toJson(): JSONObject {
        return JSONObject()
            .putNullable("total_frames", totalFrames)
            .putNullable("janky_frames", jankyFrames)
            .putNullable("jank_rate_pct", jankRatePct)
            .putNullable("p90_ms", p90Ms)
            .putNullable("p95_ms", p95Ms)
            .putNullable("p99_ms", p99Ms)
    }
}

class DevicePressureMetrics(private val context: Context) {
    fun snapshot(): PressureSnapshot {
        val meminfo = RootShell.run("cat /proc/meminfo", timeoutMs = 3_000L).stdout
        val vmstat = RootShell.run("cat /proc/vmstat", timeoutMs = 3_000L).stdout
        val psi = RootShell.run("cat /proc/pressure/memory 2>/dev/null", requireRoot = true, timeoutMs = 3_000L).stdout
        val snmp = RootShell.run("cat /proc/net/snmp", requireRoot = true, timeoutMs = 3_000L).stdout
        val procStat = RootShell.run("cat /proc/stat", timeoutMs = 3_000L).stdout
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }
        val udp = parseUdp(snmp)
        return PressureSnapshot(
            wallTimeMs = System.currentTimeMillis(),
            elapsedMs = SystemClock.elapsedRealtime(),
            memAvailableKb = meminfoValue(meminfo, "MemAvailable"),
            swapFreeKb = meminfoValue(meminfo, "SwapFree"),
            pgscanDirect = vmstatValue(vmstat, "pgscan_direct"),
            pgscanKswapd = vmstatValue(vmstat, "pgscan_kswapd"),
            compactStall = vmstatValue(vmstat, "compact_stall"),
            psiSomeAvg10 = psiAvg10(psi, "some"),
            psiFullAvg10 = psiAvg10(psi, "full"),
            udpInDatagrams = udp["InDatagrams"],
            udpOutDatagrams = udp["OutDatagrams"],
            udpInErrors = udp["InErrors"],
            batteryLevelPct = if (level != null && scale != null) level * 100.0 / scale else null,
            batteryTempC = tempTenths?.let { it / 10.0 },
            batteryCurrentUa = readLongFile("/sys/class/power_supply/battery/current_now"),
            cpu = parseCpu(procStat),
        )
    }

    fun delta(before: PressureSnapshot, after: PressureSnapshot): PressureDelta {
        val cpu = if (before.cpu != null && after.cpu != null) {
            cpuDelta(before.cpu, after.cpu)
        } else {
            null
        }
        return PressureDelta(
            durationMs = after.elapsedMs - before.elapsedMs,
            memAvailableDropKb = diffDrop(before.memAvailableKb, after.memAvailableKb),
            swapFreeDropKb = diffDrop(before.swapFreeKb, after.swapFreeKb),
            pgscanDirectDelta = diff(after.pgscanDirect, before.pgscanDirect),
            pgscanKswapdDelta = diff(after.pgscanKswapd, before.pgscanKswapd),
            compactStallDelta = diff(after.compactStall, before.compactStall),
            psiSomeAvg10Delta = diffDouble(after.psiSomeAvg10, before.psiSomeAvg10),
            psiFullAvg10Delta = diffDouble(after.psiFullAvg10, before.psiFullAvg10),
            udpInDelta = diff(after.udpInDatagrams, before.udpInDatagrams),
            udpOutDelta = diff(after.udpOutDatagrams, before.udpOutDatagrams),
            udpErrorDelta = diff(after.udpInErrors, before.udpInErrors),
            batteryTempDeltaC = diffDouble(after.batteryTempC, before.batteryTempC),
            cpuBusyPct = cpu?.first,
            iowaitPct = cpu?.second,
        )
    }

    fun parseLaunchMetrics(text: String): LaunchMetrics {
        return LaunchMetrics(
            totalTimeMs = longAfter(text, "TotalTime"),
            waitTimeMs = longAfter(text, "WaitTime"),
            thisTimeMs = longAfter(text, "ThisTime"),
            status = valueAfter(text, "Status"),
            launchState = valueAfter(text, "LaunchState"),
        )
    }

    fun parseGfxMetrics(text: String): GfxMetrics {
        return GfxMetrics(
            totalFrames = Regex("""Total frames rendered:\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull(),
            jankyFrames = Regex("""Janky frames:\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull(),
            p90Ms = Regex("""90th percentile:\s*(\d+)ms""").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull(),
            p95Ms = Regex("""95th percentile:\s*(\d+)ms""").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull(),
            p99Ms = Regex("""99th percentile:\s*(\d+)ms""").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull(),
        )
    }

    private fun cpuDelta(before: CpuSnapshot, after: CpuSnapshot): Pair<Double, Double>? {
        val total = after.totalJiffies - before.totalJiffies
        if (total <= 0L) return null
        val busy = after.busyJiffies - before.busyJiffies
        val iowait = after.iowaitJiffies - before.iowaitJiffies
        return (busy * 100.0 / total) to (iowait * 100.0 / total)
    }

    private fun parseCpu(text: String): CpuSnapshot? {
        val parts = text.lineSequence().firstOrNull { it.startsWith("cpu ") }
            ?.trim()
            ?.split(Regex("""\s+"""))
            ?: return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) return null
        val idle = values.getOrElse(3) { 0L }
        val iowait = values.getOrElse(4) { 0L }
        val total = values.sum()
        val busy = total - idle - iowait
        return CpuSnapshot(
            totalJiffies = total,
            busyJiffies = busy,
            idleJiffies = idle,
            iowaitJiffies = iowait,
            procsRunning = Regex("""^procs_running\s+(\d+)""", RegexOption.MULTILINE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            procsBlocked = Regex("""^procs_blocked\s+(\d+)""", RegexOption.MULTILINE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull(),
        )
    }

    private fun meminfoValue(text: String, key: String): Long? {
        return Regex("""^$key:\s+(\d+)""", RegexOption.MULTILINE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun vmstatValue(text: String, key: String): Long? {
        return Regex("""^$key\s+(\d+)""", RegexOption.MULTILINE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun psiAvg10(text: String, type: String): Double? {
        return Regex("""^$type\s+avg10=(\d+(?:\.\d+)?)""", RegexOption.MULTILINE).find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun parseUdp(snmp: String): Map<String, Long> {
        val lines = snmp.lineSequence().filter { it.startsWith("Udp:") }.toList()
        if (lines.size < 2) return emptyMap()
        val keys = lines[0].removePrefix("Udp:").trim().split(Regex("""\s+"""))
        val vals = lines[1].removePrefix("Udp:").trim().split(Regex("""\s+"""))
        return keys.zip(vals).mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to it } }.toMap()
    }

    private fun readLongFile(path: String): Long? {
        return RootShell.run("cat '$path' 2>/dev/null", timeoutMs = 2_000L).stdout.trim().toLongOrNull()
    }

    private fun diff(after: Long?, before: Long?): Long? = if (after != null && before != null) after - before else null
    private fun diffDrop(before: Long?, after: Long?): Long? = if (before != null && after != null) before - after else null
    private fun diffDouble(after: Double?, before: Double?): Double? = if (after != null && before != null) after - before else null

    private fun longAfter(text: String, key: String): Long? {
        return Regex("""$key:\s*(-?\d+)""").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun valueAfter(text: String, key: String): String? {
        return Regex("""$key:\s*([^\r\n]+)""").find(text)?.groupValues?.getOrNull(1)?.trim()
    }
}

internal fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}
