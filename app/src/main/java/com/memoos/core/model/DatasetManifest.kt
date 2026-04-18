package com.memoos.core.model

data class DatasetManifest(
    val name: String,
    val description: String,
    val sourceType: String,
    val url: String? = null,
    val localPath: String? = null,
    val cacheFileName: String? = null,
    val format: DatasetFormat = DatasetFormat.CSV,
    val hasHeader: Boolean = true,
    val parserKey: String = "simple_csv_usage",
    val normalizerKey: String = "simple_csv_usage",
)

enum class DatasetFormat {
    CSV,
    JSON,
}
