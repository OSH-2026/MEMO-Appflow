package com.memoos.data.ingestion

import com.memoos.core.model.DatasetManifest
import com.memoos.data.dataset.DatasetSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class PublicDatasetDownloader {
    suspend fun resolveDatasetFile(manifest: DatasetManifest, cacheDir: File): DatasetResolution = withContext(Dispatchers.IO) {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val resolvedName = manifest.cacheFileName ?: manifest.localPath?.let { File(it).name }
        val targetFile = File(cacheDir, resolvedName ?: "${manifest.name}.${manifest.format.name.lowercase()}")
        if (targetFile.exists()) {
            return@withContext DatasetResolution(manifest, targetFile, DatasetSource.PUBLIC_LOCAL_CACHE)
        }
        manifest.localPath?.let { localPath ->
            val localFile = File(localPath)
            if (localFile.exists()) {
                localFile.copyTo(targetFile, overwrite = true)
                return@withContext DatasetResolution(manifest, targetFile, DatasetSource.LOCAL_LOG)
            }
        }
        val url = manifest.url ?: error("Dataset ${manifest.name} has no local cache and no download URL.")
        URL(url).openStream().use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        DatasetResolution(manifest, targetFile, DatasetSource.PUBLIC_REMOTE)
    }
}

data class DatasetResolution(
    val manifest: DatasetManifest,
    val file: File,
    val source: DatasetSource,
)
