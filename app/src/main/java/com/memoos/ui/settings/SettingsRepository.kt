package com.memoos.ui.settings

import com.memoos.core.config.ExecutionMode
import com.memoos.core.config.MemoConfig
import com.memoos.core.config.PredictorType
import com.memoos.data.repository.ConfigRepository

class SettingsRepository(
    private val configRepository: ConfigRepository,
) {
    fun current(): MemoConfig = configRepository.get()

    fun setExecutionMode(mode: ExecutionMode) {
        configRepository.update {
            it.copy(
                executionMode = mode,
                datasetMode = mode != ExecutionMode.ONLINE_DEVICE,
                replayModeEnabled = mode != ExecutionMode.ONLINE_DEVICE,
            )
        }
    }

    fun setDatasetSelection(name: String, url: String? = null) {
        configRepository.update {
            it.copy(datasetName = name, datasetUrl = url)
        }
    }

    fun setPredictorType(type: PredictorType) {
        configRepository.update { it.copy(predictorType = type) }
    }

    fun cyclePredictor() {
        val next = when (current().predictorType) {
            PredictorType.MARKOV -> PredictorType.FREQUENCY
            PredictorType.FREQUENCY -> PredictorType.MARKOV
        }
        setPredictorType(next)
    }
}
