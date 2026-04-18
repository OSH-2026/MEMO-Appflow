package com.memoos.evaluation.export

import com.memoos.core.model.ExperimentRecord
import com.memoos.core.util.CsvUtils
import java.io.File

class CsvExporter {
    fun export(records: List<ExperimentRecord>, target: File): File {
        target.parentFile?.mkdirs()
        target.writeText(
            buildString {
                appendLine("mode,datasetName,predictorName,policyName,predictedTop3,actualNextApp,hitAt1,hitAt3,predictionTimestamp,observationTimestamp,keepAliveCount,prewarmCount,hintCount,launchLatencyMs")
                records.forEach { record ->
                    appendLine(
                        CsvUtils.toCsvRow(
                            listOf(
                                record.mode,
                                record.datasetName,
                                record.predictorName,
                                record.policyName,
                                record.predictedTop3.joinToString("|"),
                                record.actualNextApp,
                                record.hitAt1,
                                record.hitAt3,
                                record.predictionTimestamp,
                                record.observationTimestamp,
                                record.keepAliveCount,
                                record.prewarmCount,
                                record.hintCount,
                                record.launchLatencyMs,
                            ),
                        ),
                    )
                }
            },
        )
        return target
    }
}
