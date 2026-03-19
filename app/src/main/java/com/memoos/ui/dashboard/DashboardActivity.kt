package com.memoos.ui.dashboard

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.memoos.databinding.ActivityDashboardBinding
import com.memoos.evaluation.export.CsvExporter
import com.memoos.evaluation.export.JsonExporter
import com.memoos.evaluation.export.MarkdownReportExporter
import com.memoos.ui.main.MemoGraph
import kotlinx.coroutines.launch
import java.io.File

class DashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDashboardBinding
    private val viewModel: DashboardViewModel by viewModels {
        val graph = MemoGraph.from(this)
        DashboardViewModel.Factory(
            settingsRepository = graph.settingsRepository,
            datasetManager = graph.publicDatasetManager,
            replayRunner = { replayConfig ->
                val result = graph.runPublicDatasetReplay(replayConfig)
                "Replay ${result.datasetName} finished with ${result.evaluatedWindows} evaluated windows."
            },
            latestPredictionText = {
                val items = graph.widgetStateRepository.read().items
                if (items.isEmpty()) {
                    "No replay predictions yet."
                } else {
                    items.joinToString("\n") { item ->
                        "#${item.rank} ${item.label}  |  ${item.actionLabel}"
                    }
                }
            },
            metricsText = {
                val summary = graph.experimentAnalyzer.summarizeRecent(50)
                when {
                    summary.evaluatedCount == 0 && summary.pendingCount > 0 ->
                        "Awaiting the next confirmed launch | pending ${summary.pendingCount} predictions"
                    summary.evaluatedCount == 0 ->
                        "No verified replay or online records yet"
                    else ->
                        "Hit@1 ${format(summary.hitAt1)} | Hit@3 ${format(summary.hitAt3)} | MRR ${format(summary.mrr)} | verified ${summary.evaluatedCount} | pending ${summary.pendingCount}"
                }
            },
            systemStatusText = {
                graph.systemStatusRepository.read().summaryText
            },
            exportLogs = {
                val exportDir = File(filesDir, "exports")
                val records = graph.experimentRepository.latest(500)
                CsvExporter().export(records, File(exportDir, "experiments.csv"))
                JsonExporter().export(records, File(exportDir, "experiments.json"))
                MarkdownReportExporter().export(
                    records = records,
                    summary = graph.experimentAnalyzer.summarizeRecent(500),
                    currentMode = graph.configRepository.get().executionMode.name,
                    currentPredictor = graph.configRepository.get().predictorType.name,
                    target = File(exportDir, "report.md"),
                )
                "Exported experiments.csv, experiments.json, and report.md to ${exportDir.absolutePath}"
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.exportLogsButton.setOnClickListener { viewModel.export() }
        binding.prepareDatasetButton.setOnClickListener { viewModel.prepareDataset() }
        binding.runReplayEvalButton.setOnClickListener { viewModel.runReplay() }
        binding.cycleModeButton.setOnClickListener { viewModel.cycleMode() }
        binding.cyclePredictorButton.setOnClickListener { viewModel.cyclePredictor() }
        binding.cycleDatasetButton.setOnClickListener { viewModel.cycleDataset() }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.modeText.text = "Mode: ${state.selectedMode.displayName()}"
                binding.datasetText.text = "Dataset: ${state.selectedDataset}"
                binding.predictorText.text = "Algorithm: ${state.predictorText}"
                binding.guideText.text = state.guideText
                binding.statusText.text = state.status
                binding.datasetPreviewText.text = state.datasetPreview
                binding.predictionsText.text = state.predictions
                binding.metricsText.text = state.metrics
                binding.decisionsText.text = state.systemStatus
                binding.cycleModeButton.text = "Step 1. Switch mode"
                binding.cyclePredictorButton.text = "Step 2. Switch algorithm to ${state.nextPredictorText}"
                binding.cycleDatasetButton.text = "Step 3. Switch dataset"
                binding.prepareDatasetButton.text = "Step 4. Prepare dataset"
                binding.runReplayEvalButton.text = "Step 5. Run replay benchmark"
                binding.exportLogsButton.text = "Step 6. Export CSV, JSON, report.md"
            }
        }
    }

    private companion object {
        fun format(value: Double): String = "${(value * 100).toInt()}%"
    }
}

private fun com.memoos.core.config.ExecutionMode.displayName(): String {
    return when (this) {
        com.memoos.core.config.ExecutionMode.PUBLIC_DATASET_REPLAY -> "Public dataset replay"
        com.memoos.core.config.ExecutionMode.LOCAL_REPLAY -> "Local replay"
        com.memoos.core.config.ExecutionMode.ONLINE_DEVICE -> "Online live mode"
    }
}
