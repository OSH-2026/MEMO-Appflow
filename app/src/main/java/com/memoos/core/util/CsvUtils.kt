package com.memoos.core.util

object CsvUtils {
    fun parseLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val builder = StringBuilder()
        var inQuotes = false
        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    cells += builder.toString().trim()
                    builder.clear()
                }
                else -> builder.append(char)
            }
        }
        cells += builder.toString().trim()
        return cells
    }

    fun toCsvRow(values: List<Any?>): String {
        return values.joinToString(",") { value ->
            val raw = value?.toString().orEmpty()
            if (raw.contains(",")) "\"$raw\"" else raw
        }
    }
}
