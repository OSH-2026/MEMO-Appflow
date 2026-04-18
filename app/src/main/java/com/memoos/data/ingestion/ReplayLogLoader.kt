package com.memoos.data.ingestion

import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.core.util.CsvUtils
import java.io.File
import java.time.Instant
import java.time.ZoneId

class ReplayLogLoader {
    fun load(file: File, sourceType: SourceType = SourceType.LOCAL_LOG): List<AppEvent> {
        if (!file.exists()) return emptyList()
        return load(file.readText(), file.name, sourceType)
    }

    fun load(rawContent: String, sourceName: String, sourceType: SourceType = SourceType.LOCAL_LOG): List<AppEvent> {
        return rawContent.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val cells = CsvUtils.parseLine(line)
                val packageName = cells.getOrNull(0) ?: return@mapNotNull null
                val timestamp = cells.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val zoned = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                AppEvent(
                    packageName = packageName,
                    timestamp = timestamp,
                    dayOfWeek = zoned.dayOfWeek.value,
                    hourOfDay = zoned.hour,
                    sourceType = sourceType,
                    metadata = mapOf("raw_source" to sourceName),
                )
            }
            .toList()
    }
}
