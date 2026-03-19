package com.memoos.data.feature

import com.memoos.core.model.AppEvent

class FeatureExtractor {
    fun recentPackages(events: List<AppEvent>, limit: Int): List<String> {
        return events.sortedByDescending { it.timestamp }.take(limit).map { it.packageName }
    }
}
