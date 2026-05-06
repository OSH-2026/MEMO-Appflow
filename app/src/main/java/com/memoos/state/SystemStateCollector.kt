package com.memoos.state

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.memoos.device.RootShell
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SystemStateCollector(private val context: Context) {
    fun collect(): SystemStateSnapshot {
        return SystemStateSnapshot(
            memory = collectMemory(),
            battery = collectBattery(),
            network = collectNetwork(),
            process = collectProcess(),
            mediaDisplay = collectMediaDisplay(),
            wallTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    private fun collectMemory(): MemorySnapshot {
        val meminfo = RootShell.run("cat /proc/meminfo", requireRoot = false, timeoutMs = 3_000L).stdout
        val vmstat = RootShell.run("cat /proc/vmstat", requireRoot = false, timeoutMs = 3_000L).stdout
        val psi = RootShell.run("cat /proc/pressure/memory 2>/dev/null", requireRoot = true, timeoutMs = 3_000L).stdout
        val lmkd = RootShell.run("dumpsys lmkd 2>/dev/null | head -n 40", requireRoot = true, timeoutMs = 4_000L).stdout
        return MemorySnapshot(
            memTotalKb = meminfoValue(meminfo, "MemTotal"),
            memAvailableKb = meminfoValue(meminfo, "MemAvailable"),
            swapTotalKb = meminfoValue(meminfo, "SwapTotal"),
            swapFreeKb = meminfoValue(meminfo, "SwapFree"),
            pgscanDirect = vmstatValue(vmstat, "pgscan_direct"),
            pgscanKswapd = vmstatValue(vmstat, "pgscan_kswapd"),
            compactStall = vmstatValue(vmstat, "compact_stall"),
            psiSomeAvg10 = psiAvg10(psi, "some"),
            psiFullAvg10 = psiAvg10(psi, "full"),
            lmkdRaw = lmkd.take(2_000),
        )
    }

    private fun collectBattery(): BatterySnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }
        return BatterySnapshot(
            level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 },
            scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 },
            status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 },
            plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 },
            temperatureC = tempTenths?.let { it / 10.0 },
            voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it >= 0 },
        )
    }

    private fun collectNetwork(): NetworkSnapshot {
        val snmp = RootShell.run("cat /proc/net/snmp", requireRoot = true, timeoutMs = 3_000L).stdout
        val udpSocketCount = RootShell.run("cat /proc/net/udp /proc/net/udp6 2>/dev/null | wc -l", requireRoot = true, timeoutMs = 3_000L)
            .stdout.trim().toIntOrNull()
        val active = RootShell.run("dumpsys connectivity 2>/dev/null | head -n 80", requireRoot = false, timeoutMs = 4_000L).stdout
        val udp = parseUdp(snmp)
        return NetworkSnapshot(
            udpInDatagrams = udp["InDatagrams"],
            udpOutDatagrams = udp["OutDatagrams"],
            udpInErrors = udp["InErrors"],
            udpReceiveBufferErrors = udp["RcvbufErrors"],
            udpSendBufferErrors = udp["SndbufErrors"],
            udpSocketCount = udpSocketCount,
            activeNetworkRaw = active.take(2_000),
        )
    }

    private fun collectProcess(): ProcessSnapshot {
        val top = RootShell.run(
            "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' | head -n 5",
            requireRoot = false,
            timeoutMs = 4_000L,
        ).stdout
        val focus = RootShell.run(
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 5",
            requireRoot = false,
            timeoutMs = 4_000L,
        ).stdout
        val combined = top.ifBlank { focus }
        val packageName = Regex("""\s([a-zA-Z0-9_.]+)/(?:[a-zA-Z0-9_.$]+)""")
            .find(combined)?.groupValues?.getOrNull(1)
        val activity = Regex("""([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)""")
            .find(combined)?.value
        val names = listOf(
            "system_server",
            "surfaceflinger",
            "cameraserver",
            "audioserver",
            "media.codec",
            "mediaswcodec",
            "netd",
            "lmkd",
            "zygote64",
            "servicemanager",
        )
        val pids = names.mapNotNull { name ->
            val out = RootShell.run("pidof $name 2>/dev/null | awk '{print \$1}'", requireRoot = false, timeoutMs = 2_000L)
                .stdout.trim().toIntOrNull()
            out?.let { name to it }
        }.toMap()
        val hints = mutableListOf<String>()
        pids["netd"]?.let { hints += "netd active pid=$it" }
        pids["lmkd"]?.let { hints += "lmkd active pid=$it" }
        pids["system_server"]?.let { hints += "system_server coordination pid=$it" }
        pids["servicemanager"]?.let { hints += "Binder servicemanager pid=$it" }
        return ProcessSnapshot(packageName, activity, pids, hints)
    }

    private fun collectMediaDisplay(): MediaDisplaySnapshot {
        val pids = listOf("surfaceflinger", "cameraserver", "audioserver", "media.codec", "mediaswcodec").associateWith { name ->
            RootShell.run("pidof $name 2>/dev/null | awk '{print \$1}'", requireRoot = false, timeoutMs = 2_000L)
                .stdout.trim().toIntOrNull()
        }
        val ps = RootShell.run("ps -A 2>/dev/null | grep -Ei 'RenderThread|surfaceflinger|camera|codec|media' | head -n 40", requireRoot = false, timeoutMs = 3_000L).stdout
        val camera = RootShell.run("dumpsys media.camera 2>/dev/null | head -n 80", requireRoot = true, timeoutMs = 4_000L).stdout
        val media = RootShell.run("dumpsys media_session 2>/dev/null | head -n 80", requireRoot = false, timeoutMs = 4_000L).stdout
        return MediaDisplaySnapshot(
            surfaceFlingerPid = pids["surfaceflinger"],
            cameraServerPid = pids["cameraserver"],
            audioServerPid = pids["audioserver"],
            mediaCodecPid = pids["media.codec"] ?: pids["mediaswcodec"],
            renderThreadObserved = ps.contains("RenderThread", ignoreCase = true),
            cameraDumpsysHint = camera.take(2_000),
            mediaDumpsysHint = media.take(2_000),
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
}
