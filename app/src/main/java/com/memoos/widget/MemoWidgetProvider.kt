package com.memoos.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.RemoteViews
import com.memoos.MainActivity
import com.memoos.R
import com.memoos.action.RecommendedApp
import com.memoos.store.MemoStore

class MemoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = MemoStore(context).load()
        val apps = state.recommendations.mapIndexed { index, app ->
            WidgetApp(app.packageName, app.label, index)
        }
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, apps))
        }
    }

    companion object {
        fun updateAll(context: Context, recommendations: List<RecommendedApp>) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MemoWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            val apps = recommendations.mapIndexed { index, app -> WidgetApp(app.packageName, app.label, index) }
            ids.forEach { id -> manager.updateAppWidget(id, buildViews(context, apps)) }
        }

        private fun buildViews(context: Context, apps: List<WidgetApp>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_memo_recommend)
            views.setTextViewText(R.id.widget_title, "MEMO Top-3")
            val slots = listOf(
                Triple(R.id.widget_app_1_icon, R.id.widget_app_1_label, R.id.widget_app_1_container),
                Triple(R.id.widget_app_2_icon, R.id.widget_app_2_label, R.id.widget_app_2_container),
                Triple(R.id.widget_app_3_icon, R.id.widget_app_3_label, R.id.widget_app_3_container),
            )
            slots.forEachIndexed { index, slot ->
                val app = apps.getOrNull(index)
                if (app == null) {
                    views.setTextViewText(slot.second, "--")
                    views.setImageViewResource(slot.first, R.drawable.ic_widget_placeholder)
                    views.setOnClickPendingIntent(slot.third, openMainPendingIntent(context, index))
                } else {
                    views.setTextViewText(slot.second, app.label)
                    views.setImageViewBitmap(slot.first, iconBitmap(context, app.packageName))
                    views.setOnClickPendingIntent(slot.third, launchPendingIntent(context, app.packageName, index))
                }
            }
            views.setOnClickPendingIntent(R.id.widget_root, openMainPendingIntent(context, 99))
            return views
        }

        private fun iconBitmap(context: Context, packageName: String): Bitmap {
            val drawable = try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                context.getDrawable(R.drawable.ic_widget_placeholder)!!
            }
            return drawableToBitmap(drawable, 96, 96)
        }

        private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        private fun launchPendingIntent(context: Context, packageName: String, requestCode: Int): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(context, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, requestCode, intent, flags())
        }

        private fun openMainPendingIntent(context: Context, requestCode: Int): PendingIntent {
            return PendingIntent.getActivity(context, requestCode, Intent(context, MainActivity::class.java), flags())
        }

        private fun flags(): Int {
            return PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        }
    }
}

private data class WidgetApp(val packageName: String, val label: String, val index: Int)
