package com.memoos.data.ingestion

import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import java.time.Instant
import java.time.ZoneId

data class UsageJsonRecord(
    val packageName: String,
    val timestamp: Long,
    val sourceId: String?,
)

class SimpleUsageJsonParser : PublicDatasetParser<UsageJsonRecord> {
    override val key: String = "simple_json_usage"

    override fun parse(rawContent: String): List<UsageJsonRecord> {
        return rawContent.lineSequence()
            .filter { it.contains("packageName") }
            .mapNotNull { line ->
                val packageName = extract(line, "packageName") ?: return@mapNotNull null
                val timestamp = extract(line, "timestamp")?.toLongOrNull() ?: return@mapNotNull null
                UsageJsonRecord(packageName, timestamp, extract(line, "sourceId"))
            }
            .toList()
    }

    private fun extract(line: String, key: String): String? {
        val pattern = "\"$key\""
        val start = line.indexOf(pattern)
        if (start < 0) return null
        val colonIndex = line.indexOf(':', start)
        if (colonIndex < 0) return null
        return line.substring(colonIndex + 1)
            .trim()
            .trim(',')
            .trim()
            .trim('"')
    }
}

class SimpleUsageJsonNormalizer : PublicDatasetNormalizer<UsageJsonRecord> {
    override val key: String = "simple_json_usage"

    override fun normalize(records: List<UsageJsonRecord>): List<AppEvent> {
        return records.map { record ->
            val zoned = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault())
            AppEvent(
                packageName = record.packageName,
                timestamp = record.timestamp,
                dayOfWeek = zoned.dayOfWeek.value,
                hourOfDay = zoned.hour,
                sourceType = SourceType.PUBLIC_DATASET,
                metadata = buildMap {
                    put("source_id", record.sourceId.orEmpty())
                    put("parser", key)
                },
            )
        }
    }
}
