package com.memoos.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.memoos.databinding.ActivitySettingsBinding
import com.memoos.ui.main.MemoGraph

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val config = MemoGraph.from(this).configRepository.get()
        binding.settingsSummary.text = """
            executionMode = ${config.executionMode}
            predictorType = ${config.predictorType}
            topK = ${config.topK}
            historyWindowSize = ${config.historyWindowSize}
            prewarmThreshold = ${config.prewarmThreshold}
            keepAliveThreshold = ${config.keepAliveThreshold}
            hintThreshold = ${config.hintThreshold}
            datasetName = ${config.datasetName}
            datasetUrl = ${config.datasetUrl}
            datasetLocalPath = ${config.datasetLocalPath}
            datasetCacheDir = ${config.datasetCacheDir}
            onlineCollectionEnabled = ${config.onlineCollectionEnabled}
            nativeBridgeEnabled = ${config.nativeBridgeEnabled}
        """.trimIndent()
    }
}
