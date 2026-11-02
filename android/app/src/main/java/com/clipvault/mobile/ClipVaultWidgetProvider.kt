package com.clipvault.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class ClipVaultWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.clipvault.mobile.WIDGET_UPDATE") {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = android.content.ComponentName(context, ClipVaultWidgetProvider::class.java)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetManager.getAppWidgetIds(componentName), R.id.widget_list)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val intent = Intent(context, ClipVaultWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }

            views.setRemoteAdapter(R.id.widget_list, intent)
            views.setEmptyView(R.id.widget_list, android.R.id.empty)

            // Setup click intent for launching app
            val appIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (appIntent != null) {
                val pendingIntent = PendingIntent.getActivity(context, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setPendingIntentTemplate(R.id.widget_list, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
