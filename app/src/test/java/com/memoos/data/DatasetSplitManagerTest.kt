package com.memoos.data

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.data.dataset.DatasetSplitManager
import org.junit.Assert.assertEquals
import org.junit.Test

class DatasetSplitManagerTest {
    @Test
    fun splitsDatasetIntoTrainValidationAndTest() {
        val events = (1L..10L).map {
            AppEvent("pkg.$it", it, 1, 10, SourceType.PUBLIC_DATASET)
        }

        val splits = DatasetSplitManager().split(events, MemoConfig(trainSplit = 0.6f, valSplit = 0.2f, testSplit = 0.2f))

        assertEquals(6, splits.train.size)
        assertEquals(2, splits.validation.size)
        assertEquals(2, splits.test.size)
    }
}
