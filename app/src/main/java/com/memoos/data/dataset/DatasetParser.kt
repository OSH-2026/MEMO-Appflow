package com.memoos.data.dataset

interface DatasetParser<RawRecord : Any> {
    val key: String
    fun parse(rawContent: String): List<RawRecord>
}
