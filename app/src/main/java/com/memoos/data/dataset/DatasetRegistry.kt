package com.memoos.data.dataset

import com.memoos.core.model.DatasetFormat
import com.memoos.core.model.DatasetManifest

class DatasetRegistry(
    private val manifests: MutableMap<String, DatasetManifest> = mutableMapOf(
        "sample_local" to DatasetManifest(
            name = "sample_local",
            description = "Local CSV replay dataset for bootstrapping experiments.",
            sourceType = "local",
            format = DatasetFormat.CSV,
            localPath = "dataset_cache/sample_usage.csv",
            cacheFileName = "sample_usage.csv",
            parserKey = "simple_csv_usage",
            normalizerKey = "simple_csv_usage",
        ),
        "sample_public_json" to DatasetManifest(
            name = "sample_public_json",
            description = "Sample JSON-formatted public app usage dataset.",
            sourceType = "public",
            format = DatasetFormat.JSON,
            localPath = "dataset_cache/sample_usage.json",
            cacheFileName = "sample_usage.json",
            parserKey = "simple_json_usage",
            normalizerKey = "simple_json_usage",
        ),
        "lsapp_placeholder" to DatasetManifest(
            name = "lsapp_placeholder",
            description = "Manifest placeholder for LSApp-style public app-launch sequence datasets.",
            sourceType = "public",
            format = DatasetFormat.CSV,
            url = "https://example.com/datasets/lsapp_placeholder.csv",
            cacheFileName = "lsapp_placeholder.csv",
            parserKey = "simple_csv_usage",
            normalizerKey = "simple_csv_usage",
        ),
    ),
) {
    fun register(manifest: DatasetManifest) {
        manifests[manifest.name] = manifest
    }

    fun get(name: String): DatasetManifest? = manifests[name]

    fun all(): List<DatasetManifest> = manifests.values.toList()

    fun registerAll(datasetManifests: List<DatasetManifest>) {
        datasetManifests.forEach(::register)
    }
}
