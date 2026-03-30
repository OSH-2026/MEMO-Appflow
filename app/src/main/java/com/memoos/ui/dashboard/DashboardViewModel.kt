package com.memoos.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.memoos.core.config.ExecutionMode
import com.memoos.core.config.PredictorType
import com.memoos.core.model.AppEvent
import com.memoos.core.model.ReplaySessionConfig
import com.memoos.data.dataset.PublicDatasetManager
import com.memoos.ui.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DashboardViewModel(
    private val settingsRepository: SettingsRepository,
    private val datasetManager: PublicDatasetManager,
    private val replayRunner: suspend (ReplaySessionConfig) -> String,
    private val latestPredictionText: suspend () -> String,
    private val metricsText: suspend () -> String,
    private val systemStatusText: suspend () -> String,
    private val exportLogs: suspend () -> String,
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val visibleDatasets = datasetManager.listManifests()
                .filterNot { it.name.contains("placeholder", ignoreCase = true) }
                .map { it.name }
            val config = settingsRepository.current().let { current ->
                if (visibleDatasets.isNotEmpty() && current.datasetName !in visibleDatasets) {
                    settingsRepository.setDatasetSelection(visibleDatasets.first())
                    settingsRepository.current()
                } else {
                    current
                }
            }
            _state.value = _state.value.copy(
                availableDatasets = visibleDatasets,
                selectedDataset = config.datasetName,
                selectedMode = config.executionMode,
                predictorText = config.predictorType.displayName(),
                nextPredictorText = config.predictorType.next().displayName(),
                guideText = config.executionMode.guideText(),
                datasetPreview = buildDatasetPreview(config),
                predictions = latestPredictionText(),
                metrics = metricsText(),
                systemStatus = systemStatusText(),
            )
        }
    }

    fun cycleMode() {
        val next = when (_state.value.selectedMode) {
            ExecutionMode.PUBLIC_DATASET_REPLAY -> ExecutionMode.LOCAL_REPLAY
            ExecutionMode.LOCAL_REPLAY -> ExecutionMode.ONLINE_DEVICE
            ExecutionMode.ONLINE_DEVICE -> ExecutionMode.PUBLIC_DATASET_REPLAY
        }
        settingsRepository.setExecutionMode(next)
        refresh()
    }

    fun cyclePredictor() {
        settingsRepository.cyclePredictor()
        refresh()
    }

    fun cycleDataset() {
        val datasets = _state.value.availableDatasets
        if (datasets.isEmpty()) return
        val currentIndex = datasets.indexOf(_state.value.selectedDataset).coerceAtLeast(0)
        val next = datasets[(currentIndex + 1) % datasets.size]
        settingsRepository.setDatasetSelection(next)
        refresh()
    }

    fun prepareDataset() {
        viewModelScope.launch {
            runCatching {
                val config = settingsRepository.current()
                datasetManager.prepareDataset(config.datasetName, File(config.datasetCacheDir), config.datasetUrl)
            }.onSuccess { prepared ->
                val appCount = prepared.normalizedEvents.map { it.packageName }.distinct().size
                _state.value = _state.value.copy(
                    status = "Dataset ready: ${prepared.resolution.manifest.name}. Parsed ${prepared.normalizedEvents.size} events across $appCount apps from ${prepared.resolution.source}.",
                    datasetPreview = buildPreviewText(prepared.normalizedEvents),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(status = "Failed to prepare dataset: ${error.userFacingMessage()}")
            }
        }
    }

    fun runReplay() {
        viewModelScope.launch {
            runCatching {
                val config = settingsRepository.current()
                replayRunner(
                    ReplaySessionConfig(
                        datasetName = config.datasetName,
                        batchSize = config.historyWindowSize,
                        exportResults = true,
                    ),
                )
            }.onSuccess { result ->
                _state.value = _state.value.copy(status = "$result Export a report if you want a file-based summary.")
            }.onFailure { error ->
                _state.value = _state.value.copy(status = "Replay failed: ${error.userFacingMessage()}")
            }
            refresh()
        }
    }

    fun export() {
        viewModelScope.launch {
            runCatching { exportLogs() }
                .onSuccess { _state.value = _state.value.copy(status = it) }
                .onFailure { error ->
                    _state.value = _state.value.copy(status = "Export failed: ${error.userFacingMessage()}")
                }
        }
    }

    private suspend fun buildDatasetPreview(config: com.memoos.core.config.MemoConfig): String {
        val manifest = datasetManager.manifest(config.datasetName)
            ?: return "Dataset not registered yet."
        val cacheDir = File(config.datasetCacheDir)
        val localFile = manifest.localPath?.let(::File)
        val cachedFile = manifest.cacheFileName?.let { File(cacheDir, it) }
        val hasPreviewableFile = (localFile?.exists() == true) || (cachedFile?.exists() == true)
        if (!hasPreviewableFile && manifest.url != null) {
            return "Preview is unavailable until you tap Prepare dataset. MEMO-Appflow will download the file once and cache it at ${cacheDir.absolutePath}."
        }
        return runCatching {
            val prepared = datasetManager.prepareDataset(config.datasetName, cacheDir, config.datasetUrl)
            buildPreviewText(prepared.normalizedEvents)
        }.getOrElse { error ->
            "Preview unavailable: ${error.userFacingMessage()}"
        }
    }

    private fun buildPreviewText(events: List<AppEvent>): String {
        if (events.isEmpty()) return "No normalized events are available yet."
        val sorted = events.sortedBy { it.timestamp }
        val topCounts = sorted.groupingBy { it.packageName }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
        val maxCount = topCounts.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
        val bars = topCounts.joinToString("\n") { entry ->
            val barLength = ((entry.value.toDouble() / maxCount) * 12).toInt().coerceAtLeast(1)
            "${humanizePackage(entry.key).padEnd(16)} ${"#".repeat(barLength)} ${entry.value}"
        }
        val transitions = sorted.zipWithNext()
            .groupingBy { (from, to) -> "${humanizePackage(from.packageName)} -> ${humanizePackage(to.packageName)}" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
        val maxTransition = transitions.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
        val transitionBars = transitions.joinToString("\n") { entry ->
            val barLength = ((entry.value.toDouble() / maxTransition) * 10).toInt().coerceAtLeast(1)
            "${entry.key.padEnd(28)} ${"=".repeat(barLength)} ${entry.value}"
        }
        val recentSteps = sorted.takeLast(6).joinToString(" | ") { event ->
            "${formatClock(event.timestamp)} ${humanizePackage(event.packageName)}"
        }
        return buildString {
            append("Events: ${sorted.size} | Apps: ${sorted.map { it.packageName }.distinct().size}\n")
            append("Time span: ${formatClock(sorted.first().timestamp)} -> ${formatClock(sorted.last().timestamp)}\n")
            append("Top apps:\n")
            append(bars)
            append("\nCommon transitions:\n")
            append(if (transitionBars.isBlank()) "Not enough events yet." else transitionBars)
            append("\nRecent sequence:\n")
            append(recentSteps)
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val datasetManager: PublicDatasetManager,
        private val replayRunner: suspend (ReplaySessionConfig) -> String,
        private val latestPredictionText: suspend () -> String,
        private val metricsText: suspend () -> String,
        private val systemStatusText: suspend () -> String,
        private val exportLogs: suspend () -> String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                settingsRepository,
                datasetManager,
                replayRunner,
                latestPredictionText,
                metricsText,
                systemStatusText,
                exportLogs,
            ) as T
        }
    }

    private companion object {
        val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        fun formatClock(timestamp: Long): String {
            return Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(CLOCK_FORMATTER)
        }

        fun humanizePackage(packageName: String): String {
            return when (packageName) {
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
                else -> packageName.substringAfterLast('.')
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .replaceFirstChar { it.titlecase(Locale.US) }
            }
        }
    }
}

private fun Throwable.userFacingMessage(): String {
    val message = message.orEmpty()
    return when {
        message.contains("no local cache and no download URL", ignoreCase = true) ->
            "This manifest is only a placeholder right now. Use sample_local or sample_public_json first."
        message.isNotBlank() -> message
        else -> javaClass.simpleName
    }
}

private fun PredictorType.displayName(): String = when (this) {
    PredictorType.MARKOV -> "Markov transitions"
    PredictorType.FREQUENCY -> "Recent frequency"
}

private fun PredictorType.next(): PredictorType = when (this) {
    PredictorType.MARKOV -> PredictorType.FREQUENCY
    PredictorType.FREQUENCY -> PredictorType.MARKOV
}

private fun ExecutionMode.guideText(): String {
    return when (this) {
        ExecutionMode.ONLINE_DEVICE ->
            "This lab is optional in live mode. Use the main screen for automatic capture and prediction. Use this page only when you want to inspect metrics, compare algorithms on a built-in dataset, or export a report."
        ExecutionMode.PUBLIC_DATASET_REPLAY ->
            "Use this order: 1. Switch mode if needed. 2. Switch algorithm. 3. Switch dataset. 4. Prepare dataset. 5. Run replay benchmark. 6. Export CSV, JSON, and report.md."
        ExecutionMode.LOCAL_REPLAY ->
            "Use this order: 1. Confirm local replay mode. 2. Switch algorithm. 3. Switch dataset. 4. Prepare the dataset. 5. Run replay. 6. Export the report."
    }
}

data class DashboardUiState(
    val selectedMode: ExecutionMode = ExecutionMode.ONLINE_DEVICE,
    val selectedDataset: String = "sample_local",
    val availableDatasets: List<String> = emptyList(),
    val predictorText: String = "Markov transitions",
    val nextPredictorText: String = "Recent frequency",
    val guideText: String = "Use this lab for replay experiments and reports.",
    val datasetPreview: String = "Prepare a dataset to preview its event distribution.",
    val predictions: String = "No predictions yet",
    val metrics: String = "No metrics yet",
    val systemStatus: String = "System execution idle",
    val status: String = "Ready. Start with the built-in dataset, prepare it, then run replay.",
)
