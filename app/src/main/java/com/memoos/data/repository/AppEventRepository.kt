package com.memoos.data.repository

import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.core.util.MetadataSerializer
import com.memoos.data.storage.dao.AppEventDao
import com.memoos.data.storage.entity.AppEventEntity

class AppEventRepository(
    private val dao: AppEventDao,
) {
    suspend fun insert(events: List<AppEvent>) {
        dao.insertAll(
            events.map {
                AppEventEntity(
                    packageName = it.packageName,
                    timestamp = it.timestamp,
                    dayOfWeek = it.dayOfWeek,
                    hourOfDay = it.hourOfDay,
                    sourceType = it.sourceType.name,
                    metadata = MetadataSerializer.encode(it.metadata),
                )
            },
        )
    }

    suspend fun recent(limit: Int): List<AppEvent> = dao.latest(limit).map { entity ->
        AppEvent(
            packageName = entity.packageName,
            timestamp = entity.timestamp,
            dayOfWeek = entity.dayOfWeek,
            hourOfDay = entity.hourOfDay,
            sourceType = SourceType.valueOf(entity.sourceType),
            metadata = MetadataSerializer.decode(entity.metadata),
        )
    }

    suspend fun latestTimestamp(): Long? = dao.latestTimestamp()
}
