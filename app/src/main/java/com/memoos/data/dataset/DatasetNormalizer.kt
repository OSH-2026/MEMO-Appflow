package com.memoos.data.dataset

import com.memoos.core.model.AppEvent

interface DatasetNormalizer<RawRecord : Any> {
    val key: String
    fun normalize(records: List<RawRecord>): List<AppEvent>
}
