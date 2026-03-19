package com.memoos.orchestration

import com.memoos.data.collector.UsageCollector
import com.memoos.core.model.AppEvent
import com.memoos.data.repository.AppEventRepository

class CollectPipeline(
    private val usageCollector: UsageCollector,
    private val appEventRepository: AppEventRepository,
) {
    suspend fun collectEvents(sinceTimestamp: Long): List<AppEvent> {
        val events = usageCollector.collectSince(sinceTimestamp)
        appEventRepository.insert(events)
        return events
    }

    suspend fun run(sinceTimestamp: Long): Int {
        return collectEvents(sinceTimestamp).size
    }
}
