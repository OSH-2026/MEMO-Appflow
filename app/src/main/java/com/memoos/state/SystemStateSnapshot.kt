package com.memoos.state

data class MemorySnapshot(
    val memTotalKb: Long? = null,
    val memAvailableKb: Long? = null,
    val swapTotalKb: Long? = null,
    val swapFreeKb: Long? = null,
    val pgscanDirect: Long? = null,
    val pgscanKswapd: Long? = null,
    val compactStall: Long? = null,
    val psiSomeAvg10: Double? = null,
    val psiFullAvg10: Double? = null,
    val lmkdRaw: String = "",
) {
    val pressureLevel: String
        get() {
            val availableRatio = if (memTotalKb != null && memAvailableKb != null && memTotalKb > 0) {
                memAvailableKb.toDouble() / memTotalKb.toDouble()
            } else {
                null
            }
            return when {
                psiFullAvg10 != null && psiFullAvg10 >= 5.0 -> "critical"
                availableRatio != null && availableRatio < 0.08 -> "critical"
                psiSomeAvg10 != null && psiSomeAvg10 >= 15.0 -> "elevated"
                availableRatio != null && availableRatio < 0.18 -> "elevated"
                else -> "normal"
            }
        }
}

data class BatterySnapshot(
    val level: Int? = null,
    val scale: Int? = null,
    val status: Int? = null,
    val plugged: Int? = null,
    val temperatureC: Double? = null,
    val voltageMv: Int? = null,
) {
    val levelPercent: Int?
        get() = if (level != null && scale != null && scale > 0) {
            ((level.toDouble() / scale.toDouble()) * 100.0).toInt()
        } else {
            level
        }

    val thermalRisk: String
        get() = when {
            temperatureC != null && temperatureC >= 43.0 -> "critical"
            temperatureC != null && temperatureC >= 38.0 -> "elevated"
            else -> "normal"
        }
}

data class NetworkSnapshot(
    val udpInDatagrams: Long? = null,
    val udpOutDatagrams: Long? = null,
    val udpInErrors: Long? = null,
    val udpReceiveBufferErrors: Long? = null,
    val udpSendBufferErrors: Long? = null,
    val udpSocketCount: Int? = null,
    val activeNetworkRaw: String = "",
)

data class ProcessSnapshot(
    val foregroundPackage: String? = null,
    val foregroundActivity: String? = null,
    val criticalPids: Map<String, Int> = emptyMap(),
    val serviceHints: List<String> = emptyList(),
)

data class MediaDisplaySnapshot(
    val surfaceFlingerPid: Int? = null,
    val cameraServerPid: Int? = null,
    val audioServerPid: Int? = null,
    val mediaCodecPid: Int? = null,
    val renderThreadObserved: Boolean = false,
    val cameraDumpsysHint: String = "",
    val mediaDumpsysHint: String = "",
)

data class SystemStateSnapshot(
    val memory: MemorySnapshot = MemorySnapshot(),
    val battery: BatterySnapshot = BatterySnapshot(),
    val network: NetworkSnapshot = NetworkSnapshot(),
    val process: ProcessSnapshot = ProcessSnapshot(),
    val mediaDisplay: MediaDisplaySnapshot = MediaDisplaySnapshot(),
    val wallTime: String = "",
) {
    fun evidenceLines(): List<String> {
        val lines = mutableListOf<String>()
        memory.memAvailableKb?.let { avail ->
            val total = memory.memTotalKb ?: 0
            lines += "memory available=${avail}kB total=${total}kB pressure=${memory.pressureLevel}"
        }
        if (memory.pgscanDirect != null || memory.pgscanKswapd != null) {
            lines += "memory reclaim pgscan_direct=${memory.pgscanDirect ?: 0} pgscan_kswapd=${memory.pgscanKswapd ?: 0}"
        }
        battery.levelPercent?.let {
            lines += "battery level=${it}% thermal=${battery.thermalRisk} temp_c=${battery.temperatureC ?: -1.0}"
        }
        if (network.udpInDatagrams != null || network.udpOutDatagrams != null) {
            lines += "network UDP in=${network.udpInDatagrams ?: 0} out=${network.udpOutDatagrams ?: 0} sockets=${network.udpSocketCount ?: 0}"
        }
        process.foregroundPackage?.let {
            lines += "foreground app package=$it activity=${process.foregroundActivity ?: "unknown"}"
        }
        if (mediaDisplay.surfaceFlingerPid != null || mediaDisplay.renderThreadObserved) {
            lines += "display SurfaceFlinger pid=${mediaDisplay.surfaceFlingerPid ?: -1} renderThread=${mediaDisplay.renderThreadObserved}"
        }
        mediaDisplay.cameraServerPid?.let { lines += "camera service cameraserver pid=$it" }
        mediaDisplay.mediaCodecPid?.let { lines += "media codec service pid=$it" }
        process.serviceHints.take(6).forEach { lines += "service hint $it" }
        return lines
    }
}
