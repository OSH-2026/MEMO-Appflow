package com.memoos.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.memoos.system.launcher.AppLaunchController

class RecommendationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { RecommendationWidgetUpdater.update(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LAUNCH_PREDICTED_APP) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
            AppLaunchController(context).launch(packageName)
        }
    }

    companion object {
        const val ACTION_LAUNCH_PREDICTED_APP = "com.memoos.action.LAUNCH_PREDICTED_APP"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }
}
