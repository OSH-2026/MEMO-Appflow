package com.memoos.ui.main

import android.app.AppOpsManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.memoos.R
import com.memoos.core.config.ExecutionMode
import com.memoos.core.config.PredictorType
import com.memoos.core.model.AppEvent
import com.memoos.core.model.Prediction
import com.memoos.data.repository.SystemStatusSnapshot
import com.memoos.databinding.ActivityMainBinding
import com.memoos.evaluation.metrics.PredictionMetricSummary
import com.memoos.ui.dashboard.DashboardActivity
import com.memoos.ui.settings.SettingsActivity
import com.memoos.ui.widget.RecommendationWidgetUpdater
import com.memoos.worker.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var graph: MemoGraph? = null
    private var lastAutoRefreshAt: Long = 0L
    private var liveRefreshJob: Job? = null
    private val labelCache = mutableMapOf<String, String>()
    private val iconCache = mutableMapOf<String, Drawable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setControlsEnabled(false)
        binding.modeBadgeText.text = "Live online mode"
        binding.modelBadgeText.text = "Loading model"
        binding.statusText.text = "Preparing the MEMO-Appflow live optimization loop..."

        binding.runOnlineButton.setOnClickListener {
            lifecycleScope.launch {
                runLiveRefresh(force = true, initiatedByUser = true)
            }
        }

        binding.grantUsageAccessButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                binding.statusText.text =
                    "Allow Usage Access for MEMO-Appflow, then come back. Live refresh will resume automatically."
            }.onFailure { error ->
                binding.statusText.text = "Unable to open Usage Access settings: ${error.userFacingMessage()}"
            }
        }

        binding.switchModelButton.setOnClickListener {
            val graph = graph ?: return@setOnClickListener showInitializingMessage()
            graph.settingsRepository.cyclePredictor()
            binding.statusText.text =
                "Prediction algorithm changed. MEMO-Appflow will use the new model on the next live refresh."
            refreshSummary()
        }

        binding.pinWidgetButton.setOnClickListener {
            val pinned = RecommendationWidgetUpdater.requestPin(this)
            binding.statusText.text = if (pinned) {
                "Launcher pin request sent. Confirm it and MEMO-Appflow Top 3 will appear on the home screen."
            } else {
                "This launcher does not support one-tap pinning. Add MEMO-Appflow Top 3 from the widget picker."
            }
        }

        binding.openDashboardButton.setOnClickListener {
            if (graph == null) return@setOnClickListener showInitializingMessage()
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        binding.openSettingsButton.setOnClickListener {
            if (graph == null) return@setOnClickListener showInitializingMessage()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.root.post {
            lifecycleScope.launch { initializeGraph() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (graph != null) {
            lifecycleScope.launch {
                delay(250)
                runLiveRefresh(force = true, initiatedByUser = false)
            }
            startLiveRefreshLoop()
        }
    }

    override fun onPause() {
        super.onPause()
        liveRefreshJob?.cancel()
        liveRefreshJob = null
    }

    private suspend fun initializeGraph() {
        runCatching {
            withContext(Dispatchers.IO) {
                MemoGraph.from(this@MainActivity)
            }
        }.onSuccess { loadedGraph ->
            graph = loadedGraph
            loadedGraph.settingsRepository.setExecutionMode(ExecutionMode.ONLINE_DEVICE)
            setControlsEnabled(true)
            binding.statusText.text = if (hasUsageAccess()) {
                getString(R.string.main_status_idle)
            } else {
                "Usage Access is still off. Turn it on to unlock live prediction and system optimization."
            }
            refreshSummary()
            lifecycleScope.launch(Dispatchers.Default) {
                WorkScheduler(this@MainActivity).schedule(loadedGraph.configRepository.get())
            }
            lifecycleScope.launch {
                delay(250)
                runLiveRefresh(force = true, initiatedByUser = false)
            }
            startLiveRefreshLoop()
        }.onFailure { error ->
            binding.statusText.text = "Initialization failed: ${error.userFacingMessage()}"
        }
    }

    private suspend fun runLiveRefresh(force: Boolean, initiatedByUser: Boolean) {
        val graph = graph ?: return showInitializingMessage()
        if (!hasUsageAccess()) {
            if (initiatedByUser) {
                binding.statusText.text =
                    "Turn on Usage Access first. MEMO-Appflow needs recent launch history from this Android device."
            }
            refreshSummary()
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoRefreshAt < 2_500L) {
            return
        }
        lastAutoRefreshAt = now
        graph.settingsRepository.setExecutionMode(ExecutionMode.ONLINE_DEVICE)
        runCatching {
            withContext(Dispatchers.Default) {
                graph.runOnlinePredictionCycle()
            }
        }.onSuccess { result ->
            if (initiatedByUser || result.collectedEvents > 0 || result.predictionCount > 0) {
                binding.statusText.text = result.status
            }
        }.onFailure { error ->
            if (initiatedByUser) {
                binding.statusText.text = "Live refresh failed: ${error.userFacingMessage()}"
            }
        }
        RecommendationWidgetUpdater.updateAll(this@MainActivity)
        refreshSummary()
    }

    private fun startLiveRefreshLoop() {
        if (liveRefreshJob?.isActive == true) return
        liveRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(6_000L)
                runLiveRefresh(force = false, initiatedByUser = false)
            }
        }
    }

    private fun refreshSummary() {
        lifecycleScope.launch {
            val graph = graph ?: return@launch
            val snapshot = withContext(Dispatchers.IO) {
                val config = graph.configRepository.get()
                val latestBatch = graph.predictionRepository.latestBatch()
                val recentHistory = graph.appEventRepository.recent(30).sortedBy { it.timestamp }
                val systemStatus = graph.systemStatusRepository.read()
                val records = graph.experimentRepository.latest(60)
                val predictionSummary = graph.experimentAnalyzer.summarizeRecent(40)
                MainSummarySnapshot(
                    config = config,
                    latestBatch = latestBatch,
                    recentHistory = recentHistory,
                    systemStatus = systemStatus,
                    records = records,
                    predictionSummary = predictionSummary,
                )
            }
            val config = snapshot.config
            val latestBatch = snapshot.latestBatch
            val recentHistory = snapshot.recentHistory
            val systemStatus = snapshot.systemStatus
            val records = snapshot.records
            val predictionSummary = snapshot.predictionSummary
            val latestOnlineRecord = records.firstOrNull { it.mode == ExecutionMode.ONLINE_DEVICE.name }

            binding.modeBadgeText.text = if (hasUsageAccess()) {
                "Live capture on"
            } else {
                "Usage access required"
            }
            binding.modelBadgeText.text = "Model: ${config.predictorType.displayName()}"
            binding.switchModelButton.text = "Use ${config.predictorType.next().displayName()}"
            binding.onlineGuideText.text = if (hasUsageAccess()) {
                "MEMO-Appflow keeps collecting app launches for this Android device. Use a few apps, return here, and the online predictor refreshes automatically every few seconds while this screen is visible. Tap Refresh now if you want an immediate cycle."
            } else {
                "Turn on Usage Access first. Without it, Android will not expose the launch history needed for live prediction."
            }

            binding.predictionReasonText.text = buildReasonText(
                predictorType = config.predictorType,
                recentHistory = recentHistory.map { packageLabel(it.packageName) },
            )

            val predictions = latestBatch?.predictions.orEmpty()
            bindPredictionRow(
                row = 0,
                prediction = predictions.find { it.rank == 1 },
                latestBatchName = latestBatch?.predictorName,
                systemStatus = systemStatus,
            )
            bindPredictionRow(
                row = 1,
                prediction = predictions.find { it.rank == 2 },
                latestBatchName = latestBatch?.predictorName,
                systemStatus = systemStatus,
            )
            bindPredictionRow(
                row = 2,
                prediction = predictions.find { it.rank == 3 },
                latestBatchName = latestBatch?.predictorName,
                systemStatus = systemStatus,
            )

            val telemetry = latestOnlineRecord?.memorySnapshotRef?.toMemoryTelemetry()
            binding.decisionChart.setDecisionCounts(
                keep = systemStatus.keepAlivePackages.size,
                prewarm = systemStatus.prewarmPackages.size,
                hint = systemStatus.hintPackages.size,
            )
            binding.keepAliveCountText.text = systemStatus.keepAlivePackages.size.toString()
            binding.prewarmCountText.text = systemStatus.prewarmPackages.size.toString()
            binding.hintCountText.text = systemStatus.hintPackages.size.toString()
            binding.headroomMetricText.text = buildHeadroomMetricText(telemetry)
            binding.accuracyMetricText.text = buildAccuracyMetricText(predictionSummary)
            binding.usageSummaryText.text = buildTimelineText(recentHistory)
            binding.actionSummaryText.text = buildActionSummary(systemStatus)
            binding.effectSummaryText.text = buildMemoryEffectText(telemetry, predictionSummary)
            binding.historySummaryText.text = buildLiveStatusText(
                latestBatch = latestBatch,
                recordCount = records.count { it.mode == ExecutionMode.ONLINE_DEVICE.name },
                predictorType = config.predictorType,
                predictionSummary = predictionSummary,
                systemStatus = systemStatus,
            )
        }
    }

    private fun bindPredictionRow(
        row: Int,
        prediction: Prediction?,
        latestBatchName: String?,
        systemStatus: SystemStatusSnapshot,
    ) {
        val views = predictionViews()[row]
        if (prediction == null) {
            views.icon.setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
            views.title.text = "Waiting for live signal"
            views.meta.text = "Use a few apps on this Android device, then return here."
            views.action.text = "No system action yet"
            return
        }

        views.icon.setImageDrawable(packageIcon(prediction.packageName))
        views.title.text = "#${prediction.rank} ${packageLabel(prediction.packageName)}"
        views.meta.text =
            "${format(prediction.score.toDouble())} confidence • ${latestBatchName?.prettyPredictorName() ?: "predictor"}"
        views.action.text = actionLabelFor(prediction.packageName, systemStatus)
    }

    private fun predictionViews(): List<PredictionRowViews> {
        return listOf(
            PredictionRowViews(
                binding.predictionIcon1,
                binding.predictionTitle1,
                binding.predictionMeta1,
                binding.predictionAction1,
            ),
            PredictionRowViews(
                binding.predictionIcon2,
                binding.predictionTitle2,
                binding.predictionMeta2,
                binding.predictionAction2,
            ),
            PredictionRowViews(
                binding.predictionIcon3,
                binding.predictionTitle3,
                binding.predictionMeta3,
                binding.predictionAction3,
            ),
        )
    }

    private fun buildReasonText(
        predictorType: PredictorType,
        recentHistory: List<String>,
    ): String {
        val tail = recentHistory.takeLast(6).filter { it.isNotBlank() }
        val historyText = if (tail.isEmpty()) {
            "No recent launches captured yet."
        } else {
            tail.joinToString(" -> ")
        }
        return "Why this model picked these apps\n${predictorType.description()}\nRecent launch path: $historyText"
    }

    private fun buildTimelineText(recentHistory: List<AppEvent>): String {
        if (recentHistory.isEmpty()) {
            return "No launches captured yet.\nKeep MEMO-Appflow open, use a few apps on this Android device, and return here."
        }
        val recentSteps = recentHistory.takeLast(6).joinToString("\n") { event ->
            "${formatClock(event.timestamp)}    ${packageLabel(event.packageName)}"
        }
        val uniqueApps = recentHistory.map { it.packageName }.distinct().size
        return buildString {
            append(recentSteps)
            append("\n\nWindow: ${recentHistory.size} launches across $uniqueApps apps")
        }
    }

    private fun buildActionSummary(systemStatus: SystemStatusSnapshot): String {
        if (systemStatus.bridgeName.isBlank()) {
            return "Keep active: waiting\nPrewarm ready: waiting\nLaunch hints: waiting\nBridge: idle\nPolicy: idle"
        }
        return buildString {
            appendLine("Keep active: ${packageListText(systemStatus.keepAlivePackages)}")
            appendLine("Prewarm ready: ${packageListText(systemStatus.prewarmPackages)}")
            appendLine("Protected preload: ${packageListText(systemStatus.protectedPackages)}")
            appendLine("Launch hints: ${packageListText(systemStatus.hintPackages)}")
            appendLine("Reclaim mode: ${systemStatus.reclaimMode.prettyPressure()}")
            appendLine("Deferred kill: ${packageListText(systemStatus.deferredKillPackages)}")
            appendLine("Kill candidates: ${packageListText(systemStatus.killCandidatePackages)}")
            appendLine("Preload budget: ${systemStatus.preloadBudgetMb} MB | Estimated saved launch time: ${systemStatus.predictedLaunchBenefitMs} ms")
            appendLine("Bridge: ${systemStatus.bridgeName.prettyBridgeName()}")
            append("Policy: ${systemStatus.policyName.prettyPolicyName()}")
        }
    }

    private fun buildMemoryEffectText(
        telemetry: MemoryTelemetry?,
        summary: PredictionMetricSummary,
    ): String {
        return if (telemetry == null) {
            buildString {
                appendLine("Memory telemetry appears after the first completed online cycle.")
                appendLine("Live evaluation starts once MEMO-Appflow can match a prior prediction to the next confirmed launch.")
                append(accuracyNarrative(summary))
            }
        } else {
            buildString {
                appendLine("Available memory after the last plan: ${telemetry.afterAvailMb} MB")
                appendLine("Safe headroom above threshold: ${telemetry.headroomMb} MB")
                appendLine("Pressure: ${telemetry.pressure.prettyPressure()} | Low memory: ${telemetry.lowMemoryText}")
                appendLine("Memory delta after scheduling: ${telemetry.deltaMb.signedMb()}")
                append(accuracyNarrative(summary))
            }
        }
    }

    private fun buildLiveStatusText(
        latestBatch: com.memoos.core.model.PredictionBatch?,
        recordCount: Int,
        predictorType: PredictorType,
        predictionSummary: PredictionMetricSummary,
        systemStatus: SystemStatusSnapshot,
    ): String {
        val lastRefresh = latestBatch?.generatedAt?.let(::formatClock) ?: "not yet"
        return buildString {
            append("Updated $lastRefresh")
            append(" | ")
            append(if (hasUsageAccess()) "Capture on" else "Capture off")
            append(" | ")
            append("${predictorType.displayName()}")
            append(" | ")
            append("Verified ${predictionSummary.evaluatedCount}")
            if (predictionSummary.pendingCount > 0) {
                append(" (+${predictionSummary.pendingCount} pending)")
            }
            append(" | ")
            append(systemStatus.bridgeName.prettyBridgeName())
            append(" | Records $recordCount")
        }
    }

    private fun actionLabelFor(packageName: String, systemStatus: SystemStatusSnapshot): String {
        val labels = buildList {
            if (packageName in systemStatus.keepAlivePackages) add("Keep active")
            if (packageName in systemStatus.prewarmPackages) add("Prewarmed")
            if (packageName in systemStatus.protectedPackages) add("Protected preload")
            if (packageName in systemStatus.hintPackages) add("Launch hint")
            if (packageName in systemStatus.deferredKillPackages) add("Kill deferred")
            if (packageName in systemStatus.killCandidatePackages) add("Kill candidate")
        }
        return if (labels.isEmpty()) "Predicted candidate" else labels.joinToString(" + ")
    }

    private fun buildHeadroomMetricText(telemetry: MemoryTelemetry?): String {
        return if (telemetry == null) {
            "Waiting for\nmemory sample"
        } else {
            "${telemetry.headroomMb} MB safe\n${telemetry.pressure.prettyPressure()}"
        }
    }

    private fun buildAccuracyMetricText(summary: PredictionMetricSummary): String {
        return if (summary.evaluatedCount == 0) {
            if (summary.pendingCount > 0) {
                "Awaiting next\nconfirmed launch\n${summary.pendingCount} pending"
            } else {
                "No validated\nlaunches yet"
            }
        } else {
            "Hit@1 ${format(summary.hitAt1)}\nHit@3 ${format(summary.hitAt3)}\n${summary.evaluatedCount} verified"
        }
    }

    private fun accuracyNarrative(summary: PredictionMetricSummary): String {
        return if (summary.evaluatedCount == 0) {
            if (summary.pendingCount > 0) {
                "Live validation is waiting for the next confirmed app launch. Pending predictions: ${summary.pendingCount}."
            } else {
                "No validated launches yet. Open a few apps and return so MEMO-Appflow can compare a prediction against what you actually opened next."
            }
        } else {
            "Live accuracy so far: Hit@1 ${format(summary.hitAt1)}, Hit@3 ${format(summary.hitAt3)}, MRR ${String.format(Locale.US, "%.2f", summary.mrr)} across ${summary.evaluatedCount} verified next-launch events."
        }
    }

    private fun packageListText(packages: List<String>): String {
        return if (packages.isEmpty()) {
            "none"
        } else {
            packages.joinToString(", ") { packageLabel(it) }
        }
    }

    private fun packageLabel(packageName: String): String {
        return labelCache.getOrPut(packageName) {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName.fallbackLabel())
        }
    }

    private fun packageIcon(packageName: String): Drawable {
        return iconCache.getOrPut(packageName) {
            runCatching {
                packageManager.getApplicationIcon(packageName)
            }.getOrElse {
                packageManager.getApplicationIcon(applicationInfo)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.runOnlineButton.isEnabled = enabled
        binding.switchModelButton.isEnabled = enabled
        binding.pinWidgetButton.isEnabled = enabled
        binding.openDashboardButton.isEnabled = enabled
        binding.openSettingsButton.isEnabled = enabled
        binding.grantUsageAccessButton.isEnabled = true
    }

    private fun showInitializingMessage() {
        binding.statusText.text = "MEMO-Appflow is still initializing. Try again in a moment."
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun PredictorType.displayName(): String = when (this) {
        PredictorType.MARKOV -> "Markov transitions"
        PredictorType.FREQUENCY -> "Recent frequency"
    }

    private fun PredictorType.description(): String = when (this) {
        PredictorType.MARKOV ->
            "Current model: Markov transitions. It learns which app usually follows the one you opened most recently."
        PredictorType.FREQUENCY ->
            "Current model: recent frequency. It ranks the apps that dominate the current live history window."
    }

    private fun PredictorType.next(): PredictorType = when (this) {
        PredictorType.MARKOV -> PredictorType.FREQUENCY
        PredictorType.FREQUENCY -> PredictorType.MARKOV
    }

    private fun String.prettyPredictorName(): String = when (this) {
        "markov_predictor" -> "Markov transitions"
        "frequency_predictor" -> "Recent frequency"
        else -> replace('_', ' ')
    }

    private fun Throwable.userFacingMessage(): String {
        val message = message.orEmpty()
        return when {
            message.contains("no local cache and no download URL", ignoreCase = true) ->
                "This dataset has no local cache and no download URL yet. Use sample_local or sample_public_json first."
            message.isNotBlank() -> message
            else -> javaClass.simpleName
        }
    }

    private fun format(value: Double): String = "${String.format(Locale.US, "%.0f", value * 100)}%"

    private fun formatClock(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(CLOCK_FORMATTER)
    }

    private fun String.toMemoryTelemetry(): MemoryTelemetry? {
        if (isBlank()) return null
        val pairs = split(";")
            .mapNotNull { chunk ->
                val separator = chunk.indexOf('=')
                if (separator <= 0) null else chunk.substring(0, separator) to chunk.substring(separator + 1)
            }
            .toMap()
        val before = pairs["beforeAvailMb"]?.toLongOrNull() ?: return null
        val after = pairs["afterAvailMb"]?.toLongOrNull() ?: return null
        return MemoryTelemetry(
            beforeAvailMb = before,
            afterAvailMb = after,
            deltaMb = pairs["deltaMb"]?.toLongOrNull() ?: (after - before),
            headroomMb = pairs["headroomMb"]?.toLongOrNull() ?: 0L,
            thresholdMb = pairs["thresholdMb"]?.toLongOrNull() ?: 0L,
            pressure = pairs["pressure"].orEmpty().ifBlank { "unknown" },
            lowMemoryText = if (pairs["lowMemory"] == "true") "yes" else "no",
            keepCount = pairs["keepCount"]?.toIntOrNull() ?: 0,
            prewarmCount = pairs["prewarmCount"]?.toIntOrNull() ?: 0,
            hintCount = pairs["hintCount"]?.toIntOrNull() ?: 0,
        )
    }

    private fun Long.signedMb(): String = if (this > 0) "+${this} MB" else "${this} MB"

    private companion object {
        val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

private data class PredictionRowViews(
    val icon: ImageView,
    val title: TextView,
    val meta: TextView,
    val action: TextView,
)

private data class MemoryTelemetry(
    val beforeAvailMb: Long,
    val afterAvailMb: Long,
    val deltaMb: Long,
    val headroomMb: Long,
    val thresholdMb: Long,
    val pressure: String,
    val lowMemoryText: String,
    val keepCount: Int,
    val prewarmCount: Int,
    val hintCount: Int,
)

private data class MainSummarySnapshot(
    val config: com.memoos.core.config.MemoConfig,
    val latestBatch: com.memoos.core.model.PredictionBatch?,
    val recentHistory: List<AppEvent>,
    val systemStatus: SystemStatusSnapshot,
    val records: List<com.memoos.core.model.ExperimentRecord>,
    val predictionSummary: PredictionMetricSummary,
)

private fun String.prettyBridgeName(): String {
    return when (this) {
        "app_level_system_bridge+native_system_bridge" -> "App bridge + native bridge"
        "app_level_system_bridge" -> "App bridge"
        "native_system_bridge" -> "Native bridge"
        "" -> "Bridge idle"
        else -> replace('_', ' ').replace('+', ' ').trim().replaceFirstChar { it.uppercase() }
    }
}

private fun String.prettyPolicyName(): String {
    return when (this) {
        "threshold_policy" -> "Adaptive threshold policy"
        "appflow_paper_policy" -> "AppFlow paper policy"
        "" -> "Policy idle"
        else -> replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
    }
}

private fun String.prettyPressure(): String = replaceFirstChar { it.uppercase() }

private fun String.fallbackLabel(): String {
    return when (this) {
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
        "com.google.android.apps.nexuslauncher" -> "Pixel Launcher"
        else -> substringAfterLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}
