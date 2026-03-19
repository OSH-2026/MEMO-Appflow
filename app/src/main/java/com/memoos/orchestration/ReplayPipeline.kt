package com.memoos.orchestration

import com.memoos.core.config.ExecutionMode
import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.ReplaySessionConfig
import com.memoos.data.dataset.DatasetSplitManager
import com.memoos.data.replay.ReplayEngine
import com.memoos.data.replay.ReplayEventStream
import com.memoos.data.repository.ExperimentRepository
import com.memoos.evaluation.export.CsvExporter
import com.memoos.evaluation.export.JsonExporter
import java.io.File

class ReplayPipeline(
    private val replayEngine: ReplayEngine,
    private val replayEventStream: ReplayEventStream,
    private val datasetSplitManager: DatasetSplitManager,
    private val predictPipeline: PredictPipeline,
    private val policyPipeline: PolicyPipeline,
    private val evaluationPipeline: EvaluationPipeline,
    private val experimentRepository: ExperimentRepository,
    private val csvExporter: CsvExporter,
    private val jsonExporter: JsonExporter,
) {
    suspend fun run(events: List<AppEvent>, replayConfig: ReplaySessionConfig, config: MemoConfig): ReplayRunSummary {
        val split = datasetSplitManager.split(events, config)
        val testEvents = if (split.test.size > config.historyWindowSize) split.test else events.sortedBy { it.timestamp }
        var evaluatedWindows = 0
        replayEngine.slice(testEvents, replayConfig).forEach { candidateEvents ->
            replayEventStream.temporalWindows(candidateEvents, config.historyWindowSize).forEach { window ->
                val batch = predictPipeline.run(window.history, config)
                val execution = policyPipeline.run(batch, config)
                evaluationPipeline.run(
                    mode = if (config.executionMode == ExecutionMode.LOCAL_REPLAY) {
                        ExecutionMode.LOCAL_REPLAY
                    } else {
                        ExecutionMode.PUBLIC_DATASET_REPLAY
                    },
                    datasetName = replayConfig.datasetName,
                    batch = batch,
                    decision = execution.decision,
                    actualNextApp = window.actualNextEvent.packageName,
                    observationTimestamp = window.actualNextEvent.timestamp,
                )
                evaluatedWindows += 1
            }
        }
        if (replayConfig.exportResults) {
            exportResults(config, replayConfig.datasetName)
        }
        return ReplayRunSummary(datasetName = replayConfig.datasetName, evaluatedWindows = evaluatedWindows)
    }

    private suspend fun exportResults(config: MemoConfig, datasetName: String) {
        val exportDir = File(config.datasetCacheDir, "exports").apply { mkdirs() }
        val records = experimentRepository.latest(500)
            .filter { it.datasetName == datasetName }
        csvExporter.export(records, File(exportDir, "${datasetName}_replay_results.csv"))
        jsonExporter.export(records, File(exportDir, "${datasetName}_replay_results.json"))
    }
}

data class ReplayRunSummary(
    val datasetName: String,
    val evaluatedWindows: Int,
)
