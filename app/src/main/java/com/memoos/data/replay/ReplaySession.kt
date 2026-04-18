package com.memoos.data.replay

import com.memoos.core.model.AppEvent
import com.memoos.core.model.ReplaySessionConfig

data class ReplaySession(
    val config: ReplaySessionConfig,
    val events: List<AppEvent>,
)
