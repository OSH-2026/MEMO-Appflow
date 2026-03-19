package com.memoos.data

import com.memoos.core.model.SourceType
import com.memoos.data.ingestion.SimpleUsageCsvNormalizer
import com.memoos.data.ingestion.UsageCsvRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleUsageCsvNormalizerTest {
    @Test
    fun normalizesCsvRowsIntoAppEvents() {
        val normalizer = SimpleUsageCsvNormalizer()
        val events = normalizer.normalize(
            listOf(UsageCsvRecord("com.example.app", 1_700_000_000_000L, "u1")),
        )

        assertEquals(1, events.size)
        assertEquals("com.example.app", events.first().packageName)
        assertEquals(SourceType.PUBLIC_DATASET, events.first().sourceType)
    }
}
