package com.memoos.system.monitor

import android.content.Context
import android.os.BatteryManager

class BatteryMonitor(
    context: Context,
) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun snapshotRef(): String {
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return "level=$level;currentNowUa=$currentNow"
    }
}
