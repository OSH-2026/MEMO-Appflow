package com.memoos.core.util

object MetadataSerializer {
    fun encode(metadata: Map<String, String>): String {
        return metadata.entries.joinToString("&") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }
    }

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&")
            .mapNotNull { part ->
                val index = part.indexOf("=")
                if (index <= 0) return@mapNotNull null
                unescape(part.substring(0, index)) to unescape(part.substring(index + 1))
            }
            .toMap()
    }

    private fun escape(value: String): String = value.replace("%", "%25").replace("=", "%3D").replace("&", "%26")

    private fun unescape(value: String): String = value.replace("%26", "&").replace("%3D", "=").replace("%25", "%")
}
