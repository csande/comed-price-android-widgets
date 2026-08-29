package com.example.comedpricewidget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * AlarmManager alarms are cleared on reboot, so the 5-minute chain and the
 * WorkManager fallback are re-armed here if any widget is currently
 * placed on a homescreen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, PriceWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return

        WidgetUpdateUtil.scheduleNextAlarm(context)
        WidgetUpdateUtil.schedulePeriodicFallback(context)
        WidgetUpdateUtil.enqueueImmediateRefresh(context)
    }
}
