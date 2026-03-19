package com.memoos.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.memoos.R
import com.memoos.ui.main.MemoGraph

object RecommendationWidgetUpdater {
    fun requestPin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(
            ComponentName(context, RecommendationWidgetProvider::class.java),
            null,
            null,
        )
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, RecommendationWidgetProvider::class.java))
        ids.forEach { update(context, manager, it) }
    }

    fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val state = MemoGraph.from(context).widgetStateRepository.read()
        val view = RemoteViews(context.packageName, R.layout.widget_memo)
        view.setTextViewText(R.id.widgetStatus, state.status.take(48))
        view.setTextViewText(R.id.widgetUpdatedAt, state.updatedAt)
        view.setTextViewText(R.id.widgetKeepCount, state.keepCount.toString())
        view.setTextViewText(R.id.widgetPrewarmCount, state.prewarmCount.toString())
        view.setTextViewText(R.id.widgetHintCount, state.hintCount.toString())
        bind(view, context, R.id.widgetPredictionRow1, R.id.widgetPredictionRank1, R.id.widgetPredictionTitle1, R.id.widgetPredictionAction1, state.items.getOrNull(0))
        bind(view, context, R.id.widgetPredictionRow2, R.id.widgetPredictionRank2, R.id.widgetPredictionTitle2, R.id.widgetPredictionAction2, state.items.getOrNull(1))
        bind(view, context, R.id.widgetPredictionRow3, R.id.widgetPredictionRank3, R.id.widgetPredictionTitle3, R.id.widgetPredictionAction3, state.items.getOrNull(2))
        manager.updateAppWidget(appWidgetId, view)
    }

    private fun bind(
        view: RemoteViews,
        context: Context,
        rowId: Int,
        rankId: Int,
        titleId: Int,
        actionId: Int,
        item: WidgetPredictionItem?,
    ) {
        view.setTextViewText(rankId, item?.rank?.toString() ?: "-")
        view.setTextViewText(titleId, item?.label ?: "Waiting for live signal")
        view.setTextViewText(actionId, item?.actionLabel ?: "Pending")
        val packageName = item?.packageName ?: return
        val intent = Intent(context, RecommendationWidgetProvider::class.java).apply {
            action = RecommendationWidgetProvider.ACTION_LAUNCH_PREDICTED_APP
            putExtra(RecommendationWidgetProvider.EXTRA_PACKAGE_NAME, packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        view.setOnClickPendingIntent(rowId, pendingIntent)
    }
}
