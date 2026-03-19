package com.memoos.evaluation.export

import com.memoos.core.model.ExperimentRecord
import com.memoos.evaluation.metrics.PredictionMetricSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MarkdownReportExporter {
    fun export(
        records: List<ExperimentRecord>,
        summary: PredictionMetricSummary,
        currentMode: String,
        currentPredictor: String,
        target: File,
    ): File {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val keepAliveTotal = records.sumOf { it.keepAliveCount }
        val prewarmTotal = records.sumOf { it.prewarmCount }
        val hintTotal = records.sumOf { it.hintCount }
        val datasets = records.mapNotNull { it.datasetName }.distinct()
        val latest = records.firstOrNull()

        target.parentFile?.mkdirs()
        target.writeText(
            buildString {
                appendLine("# MEMO-OS Experiment Report")
                appendLine()
                appendLine("- Generated at: $generatedAt")
                appendLine("- Current mode: $currentMode")
                appendLine("- Current predictor: $currentPredictor")
                appendLine("- Records analyzed: ${records.size}")
                appendLine("- Datasets covered: ${if (datasets.isEmpty()) "online device traces" else datasets.joinToString(", ")}")
                appendLine()
                appendLine("## Prediction Quality")
                appendLine("- Hit@1: ${format(summary.hitAt1)}")
                appendLine("- Hit@3: ${format(summary.hitAt3)}")
                appendLine("- MRR: ${format(summary.mrr)}")
                appendLine("- Verified next-launch events: ${summary.evaluatedCount}")
                appendLine("- Pending predictions waiting for the next observed launch: ${summary.pendingCount}")
                appendLine()
                appendLine("## System-Facing Actions")
                appendLine("- Keep-alive decisions: $keepAliveTotal")
                appendLine("- Prewarm decisions: $prewarmTotal")
                appendLine("- Launch hint decisions: $hintTotal")
                appendLine()
                appendLine("## Latest Recorded Cycle")
                if (latest == null) {
                    appendLine("- No experiment records have been stored yet.")
                } else {
                    appendLine("- Predictor: ${latest.predictorName}")
                    appendLine("- Policy: ${latest.policyName}")
                    appendLine("- Predicted Top-3: ${latest.predictedTop3.joinToString(", ")}")
                    appendLine("- Actual next app: ${latest.actualNextApp ?: "n/a"}")
                    appendLine("- Hit@1 / Hit@3: ${latest.hitAt1} / ${latest.hitAt3}")
                    appendLine("- Decision counts: keep=${latest.keepAliveCount}, prewarm=${latest.prewarmCount}, hint=${latest.hintCount}")
                    appendLine("- Launch latency: ${latest.launchLatencyMs?.toString() ?: "n/a"} ms")
                    appendLine("- Memory telemetry: ${latest.memorySnapshotRef ?: "n/a"}")
                    appendLine("- Battery telemetry: ${latest.batterySnapshotRef ?: "n/a"}")
                }
                appendLine()
                appendLine("## Interpretation")
                appendLine("- MEMO-OS is optimized around the chain prediction -> policy -> system-facing execution.")
                appendLine("- The user-facing surface is downstream: the dashboard and widget expose the result of those decisions.")
                appendLine("- Replay mode remains available for reproducible benchmarking, while online mode reflects live behavior on this Android device.")
            },
        )
        return target
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)
}
