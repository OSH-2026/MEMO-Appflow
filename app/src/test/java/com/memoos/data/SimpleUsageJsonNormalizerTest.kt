package com.memoos.data

import com.memoos.core.model.SourceType
import com.memoos.data.ingestion.SimpleUsageJsonNormalizer
import com.memoos.data.ingestion.UsageJsonRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleUsageJsonNormalizerTest {
    @Test
    fun normalizesJsonRowsIntoAppEvents() {
        val normalizer = SimpleUsageJsonNormalizer()
        val events = normalizer.normalize(
            listOf(UsageJsonRecord("com.example.json", 1_700_000_000_000L, "src1")),
        )

        assertEquals(1, events.size)
        assertEquals("com.example.json", events.first().packageName)
        assertEquals(SourceType.PUBLIC_DATASET, events.first().sourceType)
    }
}
