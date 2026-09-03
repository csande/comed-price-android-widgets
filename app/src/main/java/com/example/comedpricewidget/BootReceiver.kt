package com.example.comedpricewidget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * AlarmManager alarms and unpersisted WorkManager jobs are both cleared
 * on reboot, so the update chain is restarted here if any widget is
 * currently placed on a homescreen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, PriceWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return

        WidgetUpdateUtil.initializeWidgetUpdates(context)
    }
}
