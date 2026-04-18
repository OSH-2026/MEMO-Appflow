package com.memoos.system.launcher

import android.content.Context
import android.content.Intent

class AppLaunchController(
    private val context: Context,
) {
    fun launch(packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}
