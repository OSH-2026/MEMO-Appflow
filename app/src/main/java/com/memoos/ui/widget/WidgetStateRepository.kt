package com.memoos.ui.widget

import android.content.pm.PackageManager
import com.memoos.data.repository.PredictionRepository
import com.memoos.data.repository.SystemStatusRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class WidgetStateRepository(
    private val predictionRepository: PredictionRepository,
    private val systemStatusRepository: SystemStatusRepository,
    private val packageManager: PackageManager,
) {
    fun read(): WidgetDisplayState {
        val snapshot = predictionRepository.widgetSnapshot()
        val systemStatus = systemStatusRepository.read()
        val mapped = snapshot.predictions.map { prediction ->
            WidgetPredictionItem(
                packageName = prediction.packageName,
                label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(prediction.packageName, 0),
                    ).toString()
                }.getOrDefault(prediction.packageName.humanizePackage()),
                rank = prediction.rank,
                actionLabel = actionLabelFor(prediction.packageName, systemStatus),
            )
        }
        return WidgetDisplayState(
            status = when {
                systemStatus.bridgeName.isNotBlank() ->
                    "System plan ready"
                snapshot.predictions.isNotEmpty() ->
                    "Top-3 prediction ready"
                else ->
                    "Use a few apps, then refresh MEMO-Appflow"
            },
            updatedAt = if (systemStatus.executionTimestamp > 0L) {
                "Updated ${formatClock(systemStatus.executionTimestamp)}"
            } else {
                "Waiting for live signal"
            },
            keepCount = systemStatus.keepAlivePackages.size,
            prewarmCount = systemStatus.prewarmPackages.size,
            hintCount = systemStatus.hintPackages.size,
            items = mapped.ifEmpty {
                listOf(
                    WidgetPredictionItem(null, "Open a few apps", 1, "Live capture"),
                    WidgetPredictionItem(null, "Return to MEMO-Appflow", 2, "Refresh online cycle"),
                    WidgetPredictionItem(null, "Top-3 appears here", 3, "Tap rows to launch"),
                )
            }.let { items ->
                if (items.size >= 3) {
                    items.take(3)
                } else {
                    items + (items.size + 1..3).map {
                        WidgetPredictionItem(null, "Waiting for live signal", it, "Pending")
                    }
                }
            },
        )
    }

    private fun actionLabelFor(
        packageName: String,
        systemStatus: com.memoos.data.repository.SystemStatusSnapshot,
    ): String {
        val labels = buildList {
            if (packageName in systemStatus.keepAlivePackages) add("Keep active")
            if (packageName in systemStatus.prewarmPackages) add("Prewarmed")
            if (packageName in systemStatus.protectedPackages) add("Protected")
            if (packageName in systemStatus.hintPackages) add("Launch hint")
            if (packageName in systemStatus.deferredKillPackages) add("Kill deferred")
        }
        return if (labels.isEmpty()) "Predicted" else labels.joinToString(" + ")
    }

    private fun formatClock(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(CLOCK_FORMATTER)
    }

    private companion object {
        val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

private fun String.humanizePackage(): String {
    return when (this) {
        "com.memoos" -> "MEMO-Appflow"
        "com.google.android.apps.nexuslauncher" -> "Pixel Launcher"
        "com.android.chrome" -> "Chrome"
        "com.android.settings" -> "Settings"
        "com.google.android.apps.maps" -> "Maps"
        "com.google.android.calendar" -> "Calendar"
        "com.google.android.apps.photos" -> "Photos"
        "com.google.android.apps.docs" -> "Docs"
        "com.google.android.apps.messaging" -> "Messages"
        "com.google.android.dialer" -> "Phone"
        "com.google.android.contacts" -> "Contacts"
        "com.google.android.youtube" -> "YouTube"
        "com.google.android.gm" -> "Gmail"
        "com.google.android.apps.nbu.files" -> "Files"
        "com.spotify.music" -> "Spotify"
        else -> substringAfterLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}

data class WidgetDisplayState(
    val status: String,
    val updatedAt: String,
    val keepCount: Int,
    val prewarmCount: Int,
    val hintCount: Int,
    val items: List<WidgetPredictionItem>,
)

data class WidgetPredictionItem(
    val packageName: String?,
    val label: String,
    val rank: Int,
    val actionLabel: String,
)
