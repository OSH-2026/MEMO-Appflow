package com.memoos.evaluation.export

import com.memoos.core.model.ExperimentRecord
import java.io.File

class JsonExporter {
    fun export(records: List<ExperimentRecord>, target: File): File {
        target.parentFile?.mkdirs()
        val body = records.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { record ->
            """
            {
              "mode": "${record.mode}",
              "datasetName": ${record.datasetName?.let { "\"$it\"" } ?: "null"},
              "predictorName": "${record.predictorName}",
              "policyName": "${record.policyName}",
              "predictedTop3": "${record.predictedTop3.joinToString("|")}",
              "actualNextApp": ${record.actualNextApp?.let { "\"$it\"" } ?: "null"},
              "hitAt1": ${record.hitAt1},
              "hitAt3": ${record.hitAt3},
              "predictionTimestamp": ${record.predictionTimestamp},
              "observationTimestamp": ${record.observationTimestamp},
              "keepAliveCount": ${record.keepAliveCount},
              "prewarmCount": ${record.prewarmCount},
              "hintCount": ${record.hintCount},
              "launchLatencyMs": ${record.launchLatencyMs ?: "null"}
            }
            """.trimIndent()
        }
        target.writeText(body)
        return target
    }
}
