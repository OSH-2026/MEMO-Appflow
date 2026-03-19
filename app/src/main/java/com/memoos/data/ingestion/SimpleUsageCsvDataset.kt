package com.memoos.data.ingestion

import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.core.util.CsvUtils
import java.time.Instant
import java.time.ZoneId

data class UsageCsvRecord(
    val packageName: String,
    val timestamp: Long,
    val userId: String?,
)

class SimpleUsageCsvParser : PublicDatasetParser<UsageCsvRecord> {
    override val key: String = "simple_csv_usage"

    override fun parse(rawContent: String): List<UsageCsvRecord> {
        return rawContent.lineSequence()
            .filter { it.isNotBlank() }
            .drop(1)
            .mapNotNull { line ->
                val cells = CsvUtils.parseLine(line)
                val packageName = cells.getOrNull(0) ?: return@mapNotNull null
                val timestamp = cells.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                UsageCsvRecord(packageName, timestamp, cells.getOrNull(2))
            }
            .toList()
    }
}

class SimpleUsageCsvNormalizer : PublicDatasetNormalizer<UsageCsvRecord> {
    override val key: String = "simple_csv_usage"

    override fun normalize(records: List<UsageCsvRecord>): List<AppEvent> {
        return records.map { record ->
            val zoned = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault())
            AppEvent(
                packageName = record.packageName,
                timestamp = record.timestamp,
                dayOfWeek = zoned.dayOfWeek.value,
                hourOfDay = zoned.hour,
                sourceType = SourceType.PUBLIC_DATASET,
                metadata = buildMap {
                    put("dataset_user_id", record.userId.orEmpty())
                },
            )
        }
    }
}
