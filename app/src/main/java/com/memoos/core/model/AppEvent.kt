package com.memoos.core.model

data class AppEvent(
    val packageName: String,
    val timestamp: Long,
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val sourceType: SourceType,
    val metadata: Map<String, String> = emptyMap(),
)

enum class SourceType {
    PUBLIC_DATASET,
    LOCAL_LOG,
    ONLINE_USAGE,
    REPLAY,
}
