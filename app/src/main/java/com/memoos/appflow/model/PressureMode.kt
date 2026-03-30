package com.memoos.appflow.model

enum class PressureMode {
    NORMAL,
    REBALANCE,
    EFFICIENCY_FIRST,
}

fun PressureMode.wireName(): String = name.lowercase()
