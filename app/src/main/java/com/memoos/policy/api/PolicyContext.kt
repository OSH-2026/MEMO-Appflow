package com.memoos.policy.api

import com.memoos.core.model.AppEvent
import com.memoos.system.monitor.MemorySnapshot

data class PolicyContext(
    val memorySnapshot: MemorySnapshot? = null,
    val recentHistory: List<AppEvent> = emptyList(),
    val nowMillis: Long = System.currentTimeMillis(),
)
