package com.memoos.data.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.core.time.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class UsageStatsCollector(
    context: Context,
    private val timeProvider: TimeProvider,
) : UsageCollector {

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = context.packageManager
    private val ignoredPackages = buildSet {
        add(context.packageName)
        addAll(resolveHomePackages(packageManager))
    }

    override suspend fun collectSince(sinceTimestamp: Long): List<AppEvent> = withContext(Dispatchers.IO) {
        val end = timeProvider.now()
        val events = usageStatsManager.queryEvents(sinceTimestamp, end)
        buildList {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    val packageName = event.packageName ?: continue
                    if (packageName in ignoredPackages) continue
                    if (packageManager.getLaunchIntentForPackage(packageName) == null) continue
                    val zoned = Instant.ofEpochMilli(event.timeStamp).atZone(ZoneId.systemDefault())
                    add(
                        AppEvent(
                            packageName = packageName,
                            timestamp = event.timeStamp,
                            dayOfWeek = zoned.dayOfWeek.value,
                            hourOfDay = zoned.hour,
                            sourceType = SourceType.ONLINE_USAGE,
                            metadata = mapOf("className" to (event.className ?: "")),
                        ),
                    )
                }
            }
        }.sortedBy { it.timestamp }
            .collapseConsecutiveResumes()
            .also { eventsSummary ->
                Log.d(
                    "MemoOS",
                    "usage.collect since=$sinceTimestamp count=${eventsSummary.size} packages=${eventsSummary.takeLast(6).joinToString { it.packageName }}",
                )
            }
    }
}

private fun resolveHomePackages(packageManager: PackageManager): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()
}

private fun List<AppEvent>.collapseConsecutiveResumes(): List<AppEvent> {
    if (isEmpty()) return emptyList()
    val collapsed = ArrayList<AppEvent>(size)
    for (event in this) {
        val previous = collapsed.lastOrNull()
        if (previous?.packageName == event.packageName) continue
        collapsed += event
    }
    return collapsed
}
