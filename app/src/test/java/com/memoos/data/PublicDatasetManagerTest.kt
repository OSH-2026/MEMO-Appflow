package com.memoos.data

import com.memoos.core.model.DatasetFormat
import com.memoos.core.model.DatasetManifest
import com.memoos.data.dataset.DatasetRegistry
import com.memoos.data.dataset.PublicDatasetManager
import com.memoos.data.ingestion.PublicDatasetDownloader
import com.memoos.data.ingestion.SimpleUsageCsvNormalizer
import com.memoos.data.ingestion.SimpleUsageCsvParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PublicDatasetManagerTest {
    @Test
    fun preparesRegisteredDatasetFromLocalCache() = kotlinx.coroutines.runBlocking {
        val tempDir = createTempDirectory().toFile()
        val source = File(tempDir, "source.csv")
        source.writeText(
            """
            packageName,timestamp,userId
            com.example.a,1700000000000,u1
            com.example.b,1700000300000,u1
            """.trimIndent(),
        )
        val registry = DatasetRegistry(mutableMapOf())
        registry.register(
            DatasetManifest(
                name = "local_test",
                description = "test",
                sourceType = "local",
                localPath = source.absolutePath,
                cacheFileName = "cached.csv",
                format = DatasetFormat.CSV,
                parserKey = "simple_csv_usage",
                normalizerKey = "simple_csv_usage",
            ),
        )
        val manager = PublicDatasetManager(
            datasetRegistry = registry,
            downloader = PublicDatasetDownloader(),
            parsers = listOf(SimpleUsageCsvParser()),
            normalizers = listOf(SimpleUsageCsvNormalizer()),
        )

        val prepared = manager.prepareDataset("local_test", File(tempDir, "cache"))

        assertEquals(2, prepared.normalizedEvents.size)
        assertEquals("com.example.a", prepared.normalizedEvents.first().packageName)
    }
}
