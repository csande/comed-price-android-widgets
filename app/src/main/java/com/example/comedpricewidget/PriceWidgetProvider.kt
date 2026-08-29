package com.example.comedpricewidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class PriceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Called by the system when a widget is placed, and thereafter at
        // most every 30 minutes (the OS-enforced floor for
        // updatePeriodMillis; see price_widget_info.xml). The real
        // 5-minute cadence is driven by PriceAlarmReceiver — this is a
        // safety-net refresh only.
        WidgetUpdateUtil.enqueueImmediateRefresh(context)
    }

    override fun onEnabled(context: Context) {
        // Called once, when the first widget instance is placed.
        WidgetUpdateUtil.scheduleNextAlarm(context)
        WidgetUpdateUtil.schedulePeriodicFallback(context)
        WidgetUpdateUtil.enqueueImmediateRefresh(context)
    }

    override fun onDisabled(context: Context) {
        // Called once, when the last widget instance is removed.
        WidgetUpdateUtil.cancelAlarm(context)
        WidgetUpdateUtil.cancelPeriodicFallback(context)
    }
}
