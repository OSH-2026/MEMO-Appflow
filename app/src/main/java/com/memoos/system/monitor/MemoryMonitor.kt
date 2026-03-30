package com.memoos.system.monitor

import android.app.ActivityManager
import android.content.Context
import com.memoos.core.model.ResourceDecision
import kotlin.math.absoluteValue

class MemoryMonitor(
    context: Context,
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun snapshot(): MemorySnapshot {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return MemorySnapshot(
            availableMb = info.availMem.toMb(),
            totalMb = info.totalMem.toMb(),
            thresholdMb = info.threshold.toMb(),
            lowMemory = info.lowMemory,
        )
    }

    fun snapshotRef(): String = snapshot().toRef()

    fun cycleSnapshotRef(
        before: MemorySnapshot,
        after: MemorySnapshot,
        decision: ResourceDecision,
    ): String {
        val deltaMb = after.availableMb - before.availableMb
        return buildString {
            append("beforeAvailMb=").append(before.availableMb)
            append(";afterAvailMb=").append(after.availableMb)
            append(";deltaMb=").append(deltaMb)
            append(";thresholdMb=").append(after.thresholdMb)
            append(";totalMb=").append(after.totalMb)
            append(";lowMemory=").append(after.lowMemory)
            append(";keepCount=").append(decision.keepAlivePackages.size)
            append(";prewarmCount=").append(decision.prewarmPackages.size)
            append(";hintCount=").append(decision.hintPackages.size)
            append(";reclaimMode=").append(decision.reclaimMode)
            append(";protectedCount=").append(decision.protectedPackages.size)
            append(";deferredKillCount=").append(decision.deferredKillPackages.size)
            append(";killCandidateCount=").append(decision.killCandidatePackages.size)
            append(";headroomMb=").append((after.availableMb - after.thresholdMb).coerceAtLeast(0))
            append(";pressure=").append(
                when {
                    after.lowMemory -> "critical"
                    after.availableMb <= after.thresholdMb * 2 -> "elevated"
                    else -> "stable"
                },
            )
            append(";deltaLabel=").append(
                if (deltaMb == 0L) "flat" else if (deltaMb > 0) "up_${deltaMb.absoluteValue}" else "down_${deltaMb.absoluteValue}",
            )
        }
    }
}

data class MemorySnapshot(
    val availableMb: Long,
    val totalMb: Long,
    val thresholdMb: Long,
    val lowMemory: Boolean,
) {
    fun toRef(): String {
        return "availMb=$availableMb;totalMb=$totalMb;thresholdMb=$thresholdMb;lowMemory=$lowMemory"
    }
}

private fun Long.toMb(): Long = this / (1024L * 1024L)
