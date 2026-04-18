package com.memoos.data.dataset

import com.memoos.core.model.AppEvent
import com.memoos.core.model.DatasetManifest
import com.memoos.data.ingestion.DatasetResolution
import com.memoos.data.ingestion.PublicDatasetDownloader
import java.io.File

class PublicDatasetManager(
    private val datasetRegistry: DatasetRegistry,
    private val downloader: PublicDatasetDownloader,
    parsers: List<DatasetParser<out Any>>,
    normalizers: List<DatasetNormalizer<out Any>>,
) {
    private val parserRegistry = parsers.associateBy { it.key }
    private val normalizerRegistry = normalizers.associateBy { it.key }

    fun listManifests(): List<DatasetManifest> = datasetRegistry.all()

    fun manifest(name: String): DatasetManifest? = datasetRegistry.get(name)

    fun register(manifest: DatasetManifest) {
        datasetRegistry.register(manifest)
    }

    suspend fun prepareDataset(datasetName: String, cacheDir: File, overrideUrl: String? = null): PreparedDataset {
        val manifest = requireNotNull(datasetRegistry.get(datasetName)) { "Unknown dataset: $datasetName" }
        val resolvedManifest = if (overrideUrl != null) manifest.copy(url = overrideUrl) else manifest
        val resolution = downloader.resolveDatasetFile(resolvedManifest, cacheDir)
        val normalizedEvents = parseAndNormalize(resolution)
        return PreparedDataset(resolution, normalizedEvents)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAndNormalize(resolution: DatasetResolution): List<AppEvent> {
        val manifest = resolution.manifest
        val parser = requireNotNull(parserRegistry[manifest.parserKey]) {
            "No parser registered for key=${manifest.parserKey}"
        } as DatasetParser<Any>
        val normalizer = requireNotNull(normalizerRegistry[manifest.normalizerKey]) {
            "No normalizer registered for key=${manifest.normalizerKey}"
        } as DatasetNormalizer<Any>
        val rawRecords = parser.parse(resolution.file.readText())
        return normalizer.normalize(rawRecords).sortedBy { it.timestamp }
    }
}

data class PreparedDataset(
    val resolution: DatasetResolution,
    val normalizedEvents: List<AppEvent>,
)
