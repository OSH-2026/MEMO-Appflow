package com.memoos.data.collector

import com.memoos.core.model.AppEvent

interface UsageCollector {
    suspend fun collectSince(sinceTimestamp: Long): List<AppEvent>
}
