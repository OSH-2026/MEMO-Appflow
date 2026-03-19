package com.memoos.data.ingestion

import com.memoos.data.dataset.DatasetParser

interface PublicDatasetParser<RawRecord : Any> : DatasetParser<RawRecord> {
    override fun parse(rawContent: String): List<RawRecord>
}
