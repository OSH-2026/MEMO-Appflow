package com.memoos.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import com.memoos.core.config.ExecutionMode
import com.memoos.core.model.DatasetFormat
import com.memoos.core.model.DatasetManifest
import com.memoos.core.model.ReplaySessionConfig
import com.memoos.core.time.SystemTimeProvider
import com.memoos.data.collector.UsageStatsCollector
import com.memoos.data.dataset.DatasetRegistry
import com.memoos.data.dataset.DatasetSplitManager
import com.memoos.data.dataset.PublicDatasetManager
import com.memoos.data.feature.FeatureExtractor
import com.memoos.data.ingestion.PublicDatasetDownloader
import com.memoos.data.ingestion.SimpleUsageCsvNormalizer
import com.memoos.data.ingestion.SimpleUsageCsvParser
import com.memoos.data.ingestion.SimpleUsageJsonNormalizer
import com.memoos.data.ingestion.SimpleUsageJsonParser
import com.memoos.data.replay.ReplayEngine
import com.memoos.data.replay.ReplayEventStream
import com.memoos.data.repository.AppEventRepository
import com.memoos.data.repository.ConfigRepository
import com.memoos.data.repository.ExperimentRepository
import com.memoos.data.repository.PredictionRepository
import com.memoos.data.repository.SystemStatusRepository
import com.memoos.data.repository.WidgetStateStore
import com.memoos.data.storage.db.MemoDatabase
import com.memoos.evaluation.analyzer.ExperimentAnalyzer
import com.memoos.evaluation.export.CsvExporter
import com.memoos.evaluation.export.JsonExporter
import com.memoos.evaluation.metrics.PredictionMetrics
import com.memoos.evaluation.recorder.ExperimentRecorder
import com.memoos.orchestration.CollectPipeline
import com.memoos.orchestration.EvaluationPipeline
import com.memoos.orchestration.PolicyPipeline
import com.memoos.orchestration.PredictPipeline
import com.memoos.orchestration.ReplayPipeline
import com.memoos.orchestration.ReplayRunSummary
import com.memoos.policy.engine.ThresholdPolicyEngine
import com.memoos.policy.service.ResourcePolicyService
import com.memoos.predictor.engine.FrequencyPredictor
import com.memoos.predictor.engine.MarkovPredictor
import com.memoos.predictor.service.PredictionService
import com.memoos.predictor.trainer.TransitionTrainer
import com.memoos.system.bridge.AppLevelSystemBridge
import com.memoos.system.bridge.NativeSystemBridge
import com.memoos.system.memory.RetentionController
import com.memoos.system.monitor.BatteryMonitor
import com.memoos.system.monitor.MemoryMonitor
import com.memoos.system.prewarm.PrewarmController
import com.memoos.system.service.MemoSystemServiceFacade
import com.memoos.ui.settings.SettingsRepository
import com.memoos.ui.widget.WidgetStateRepository
import java.io.File

class MemoGraph private constructor(
    val configRepository: ConfigRepository,
    val settingsRepository: SettingsRepository,
    val appEventRepository: AppEventRepository,
    val predictionRepository: PredictionRepository,
    val experimentRepository: ExperimentRepository,
    val systemStatusRepository: SystemStatusRepository,
    val publicDatasetManager: PublicDatasetManager,
    val widgetStateRepository: WidgetStateRepository,
    val memoryMonitor: MemoryMonitor,
    val batteryMonitor: BatteryMonitor,
    val collectPipeline: CollectPipeline,
    val predictPipeline: PredictPipeline,
    val policyPipeline: PolicyPipeline,
    val replayPipeline: ReplayPipeline,
    val evaluationPipeline: EvaluationPipeline,
    val experimentAnalyzer: ExperimentAnalyzer,
    val featureExtractor: FeatureExtractor,
    private val onlineNoisePackages: Set<String>,
    private val onlineEligiblePackages: Set<String>,
) {
    companion object {
        fun create(context: Context): MemoGraph {
            val db = Room.databaseBuilder(context, MemoDatabase::class.java, "memoos.db")
                .fallbackToDestructiveMigration()
                .build()
            val prefs = context.getSharedPreferences("memo_widget_state", Context.MODE_PRIVATE)
            val configPrefs = context.getSharedPreferences("memo_config_state", Context.MODE_PRIVATE)
            val systemPrefs = context.getSharedPreferences("memo_system_state", Context.MODE_PRIVATE)
            val widgetStateStore = WidgetStateStore(prefs)
            val seededDatasetDir = seedBundledDatasets(context)

            val configRepository = ConfigRepository(configPrefs)
            configRepository.update { current ->
                val cacheDir = File(current.datasetCacheDir)
                if (cacheDir.isAbsolute) current else current.copy(datasetCacheDir = seededDatasetDir.absolutePath)
            }
            val settingsRepository = SettingsRepository(configRepository)
            val appEventRepository = AppEventRepository(db.appEventDao())
            val predictionRepository = PredictionRepository(db.predictionDao(), widgetStateStore)
            val experimentRepository = ExperimentRepository(db.experimentRecordDao())
            val systemStatusRepository = SystemStatusRepository(systemPrefs)
            val datasetRegistry = DatasetRegistry().apply {
                register(
                    DatasetManifest(
                        name = "sample_local",
                        description = "Bundled CSV replay dataset seeded into emulator or device storage.",
                        sourceType = "local",
                        format = DatasetFormat.CSV,
                        localPath = File(seededDatasetDir, "sample_usage.csv").absolutePath,
                        cacheFileName = "sample_usage.csv",
                        parserKey = "simple_csv_usage",
                        normalizerKey = "simple_csv_usage",
                    ),
                )
                register(
                    DatasetManifest(
                        name = "sample_public_json",
                        description = "Bundled JSON replay dataset seeded into emulator or device storage.",
                        sourceType = "public",
                        format = DatasetFormat.JSON,
                        localPath = File(seededDatasetDir, "sample_usage.json").absolutePath,
                        cacheFileName = "sample_usage.json",
                        parserKey = "simple_json_usage",
                        normalizerKey = "simple_json_usage",
                    ),
                )
            }
            val publicDatasetManager = PublicDatasetManager(
                datasetRegistry = datasetRegistry,
                downloader = PublicDatasetDownloader(),
                parsers = listOf(SimpleUsageCsvParser(), SimpleUsageJsonParser()),
                normalizers = listOf(SimpleUsageCsvNormalizer(), SimpleUsageJsonNormalizer()),
            )

            val timeProvider = SystemTimeProvider()
            val memoryMonitor = MemoryMonitor(context)
            val batteryMonitor = BatteryMonitor(context)
            val predictionService = PredictionService(
                predictors = mapOf(
                    com.memoos.core.config.PredictorType.MARKOV to
                        MarkovPredictor(TransitionTrainer(), timeProvider, useNativeNormalization = true),
                    com.memoos.core.config.PredictorType.FREQUENCY to
                        FrequencyPredictor(timeProvider),
                ),
                predictionRepository = predictionRepository,
            )
            val policyService = ResourcePolicyService(ThresholdPolicyEngine())
            val systemFacade = MemoSystemServiceFacade(
                appLevelSystemBridge = AppLevelSystemBridge(PrewarmController(), RetentionController()),
                nativeSystemBridge = NativeSystemBridge(),
            )
            val experimentRecorder = ExperimentRecorder(
                experimentRepository = experimentRepository,
                memoryMonitor = memoryMonitor,
                batteryMonitor = batteryMonitor,
            )
            val predictPipeline = PredictPipeline(appEventRepository, predictionService)
            val policyPipeline = PolicyPipeline(policyService, systemFacade, systemStatusRepository)
            val evaluationPipeline = EvaluationPipeline(experimentRecorder)

            return MemoGraph(
                configRepository = configRepository,
                settingsRepository = settingsRepository,
                appEventRepository = appEventRepository,
                predictionRepository = predictionRepository,
                experimentRepository = experimentRepository,
                systemStatusRepository = systemStatusRepository,
                publicDatasetManager = publicDatasetManager,
                widgetStateRepository = WidgetStateRepository(
                    predictionRepository,
                    systemStatusRepository,
                    context.packageManager,
                ),
                memoryMonitor = memoryMonitor,
                batteryMonitor = batteryMonitor,
                collectPipeline = CollectPipeline(UsageStatsCollector(context, timeProvider), appEventRepository),
                predictPipeline = predictPipeline,
                policyPipeline = policyPipeline,
                replayPipeline = ReplayPipeline(
                    replayEngine = ReplayEngine(),
                    replayEventStream = ReplayEventStream(),
                    datasetSplitManager = DatasetSplitManager(),
                    predictPipeline = predictPipeline,
                    policyPipeline = policyPipeline,
                    evaluationPipeline = evaluationPipeline,
                    experimentRepository = experimentRepository,
                    csvExporter = CsvExporter(),
                    jsonExporter = JsonExporter(),
                ),
                evaluationPipeline = evaluationPipeline,
                experimentAnalyzer = ExperimentAnalyzer(experimentRepository, PredictionMetrics()),
                featureExtractor = FeatureExtractor(),
                onlineNoisePackages = resolveHomePackages(context.packageManager) + context.packageName,
                onlineEligiblePackages = resolveLaunchablePackages(context.packageManager),
            )
        }

        fun from(context: Context): MemoGraph {
            return (context.applicationContext as MemoApplication).graph
        }

        private fun seedBundledDatasets(context: Context): File {
            val datasetDir = File(context.filesDir, "dataset_cache").apply { mkdirs() }
            copyAsset(context, "dataset_cache/sample_usage.csv", File(datasetDir, "sample_usage.csv"))
            copyAsset(context, "dataset_cache/sample_usage.json", File(datasetDir, "sample_usage.json"))
            return datasetDir
        }

        private fun copyAsset(context: Context, assetPath: String, targetFile: File) {
            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    suspend fun runConfiguredReplay(replayConfig: ReplaySessionConfig): ReplayRunSummary {
        val config = configRepository.get()
        val datasetName = replayConfig.datasetName
        val existing = publicDatasetManager.manifest(datasetName)
        if (existing == null || config.datasetUrl != null || config.datasetLocalPath != null) {
            val resolvedLocalPath = config.datasetLocalPath ?: existing?.localPath
            val resolvedUrl = config.datasetUrl ?: existing?.url
            val isJson = resolvedLocalPath?.endsWith(".json", ignoreCase = true) == true ||
                resolvedUrl?.endsWith(".json", ignoreCase = true) == true ||
                existing?.format == DatasetFormat.JSON
            publicDatasetManager.register(
                DatasetManifest(
                    name = datasetName,
                    description = existing?.description ?: "Config-driven dataset registration",
                    sourceType = existing?.sourceType ?: if (config.executionMode.name.contains("LOCAL")) "local" else "public",
                    url = resolvedUrl,
                    localPath = resolvedLocalPath,
                    cacheFileName = resolvedLocalPath?.let { File(it).name } ?: existing?.cacheFileName,
                    format = if (isJson) DatasetFormat.JSON else existing?.format ?: DatasetFormat.CSV,
                    parserKey = if (isJson) "simple_json_usage" else existing?.parserKey ?: "simple_csv_usage",
                    normalizerKey = if (isJson) "simple_json_usage" else existing?.normalizerKey ?: "simple_csv_usage",
                ),
            )
        }
        val prepared = publicDatasetManager.prepareDataset(
            datasetName = datasetName,
            cacheDir = File(config.datasetCacheDir),
            overrideUrl = config.datasetUrl,
        )
        return replayPipeline.run(prepared.normalizedEvents, replayConfig, config)
    }

    suspend fun runPublicDatasetReplay(replayConfig: ReplaySessionConfig): ReplayRunSummary {
        return runConfiguredReplay(replayConfig)
    }

    suspend fun runOnlinePredictionCycle(): OnlineCycleSummary {
        val config = configRepository.get()
        if (config.executionMode != ExecutionMode.ONLINE_DEVICE) {
            return OnlineCycleSummary(
                collectedEvents = 0,
                predictionCount = 0,
                bridgeName = null,
                status = "Switch to ONLINE_DEVICE before running the live optimization cycle.",
            )
        }
        val collectedEvents = if (config.onlineCollectionEnabled) {
            val now = System.currentTimeMillis()
            val fallbackSince = now - config.collectionIntervalMinutes * 60_000L
            val latestTimestamp = appEventRepository.latestTimestamp()
            val collectSince = if (latestTimestamp == null) {
                fallbackSince
            } else {
                (latestTimestamp - 5_000L).coerceAtLeast(fallbackSince)
            }
            collectPipeline.collectEvents(collectSince)
        } else {
            emptyList()
        }
        val newlyObservedUserApps = collectedEvents
            .filter { it.packageName in onlineEligiblePackages }
            .filterNot { it.packageName in onlineNoisePackages }
            .sortedBy { it.timestamp }
        newlyObservedUserApps.firstOrNull()?.let { event ->
            experimentRepository.resolveLatestPendingOnline(
                actualNextApp = event.packageName,
                observationTimestamp = event.timestamp,
            )
        }
        val sanitizedHistory = appEventRepository.recent(config.historyWindowSize * 8)
            .filter { it.packageName in onlineEligiblePackages }
            .filterNot { it.packageName in onlineNoisePackages }
            .sortedBy { it.timestamp }
            .takeLast(config.historyWindowSize)
        Log.d(
            "MemoOS",
            "online.history collected=${collectedEvents.size} sanitized=${sanitizedHistory.size} packages=${sanitizedHistory.joinToString { it.packageName }}",
        )
        val batch = predictPipeline.run(sanitizedHistory, config)
        if (batch.predictions.isEmpty()) {
            Log.d("MemoOS", "online.predictions empty predictor=${config.predictorType.name}")
            return OnlineCycleSummary(
                collectedEvents = collectedEvents.size,
                predictionCount = 0,
                bridgeName = null,
                status = "No predictions yet. Use a few apps on this Android device, then MEMO-OS will refresh again.",
            )
        }
        Log.d(
            "MemoOS",
            "online.predictions count=${batch.predictions.size} top=${batch.predictions.joinToString { it.packageName }}",
        )
        val memoryBefore = memoryMonitor.snapshot()
        val execution = policyPipeline.run(batch, config)
        val memoryAfter = memoryMonitor.snapshot()
        evaluationPipeline.run(
            mode = ExecutionMode.ONLINE_DEVICE,
            datasetName = null,
            batch = batch,
            decision = execution.decision,
            actualNextApp = null,
            observationTimestamp = System.currentTimeMillis(),
            memorySnapshotRef = memoryMonitor.cycleSnapshotRef(memoryBefore, memoryAfter, execution.decision),
            batterySnapshotRef = batteryMonitor.snapshotRef(),
        )
        return OnlineCycleSummary(
            collectedEvents = collectedEvents.size,
            predictionCount = batch.predictions.size,
            bridgeName = execution.report.bridgeName,
            status = "Online cycle complete. Predicted ${batch.predictions.size} apps and applied the system plan through ${execution.report.bridgeName.userFacingBridgeName()}.",
        )
    }
}

private fun resolveHomePackages(packageManager: PackageManager): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()
}

private fun resolveLaunchablePackages(packageManager: PackageManager): Set<String> {
    return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        .map { it.packageName }
        .filter { packageManager.getLaunchIntentForPackage(it) != null }
        .toSet()
}

private fun String.userFacingBridgeName(): String {
    return when (this) {
        "app_level_system_bridge+native_system_bridge" -> "the app bridge and native bridge"
        "app_level_system_bridge" -> "the app bridge"
        "native_system_bridge" -> "the native bridge"
        else -> replace('_', ' ').replace('+', ' ')
    }
}

data class OnlineCycleSummary(
    val collectedEvents: Int,
    val predictionCount: Int,
    val bridgeName: String?,
    val status: String,
)
