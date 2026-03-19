package com.memoos.data.ingestion

import com.memoos.core.model.AppEvent
import com.memoos.data.dataset.DatasetNormalizer

interface PublicDatasetNormalizer<RawRecord : Any> : DatasetNormalizer<RawRecord> {
    override fun normalize(records: List<RawRecord>): List<AppEvent>
}
