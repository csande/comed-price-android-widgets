package com.github.csande.comedpricewidgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class PriceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // With updatePeriodMillis=0 (see price_widget_info.xml) the system
        // calls this only around initial placement, not on a recurring
        // schedule — the 5-minute cadence is entirely driven by the
        // AlarmManager chain started below. Safe to call redundantly
        // alongside onEnabled: both the watchdog scheduling and the
        // immediate work enqueue are deduplicated.
        WidgetUpdateUtil.initializeWidgetUpdates(context)
    }

    override fun onEnabled(context: Context) {
        // Called once, when the first instance of *this* widget type is
        // placed (even if instances of PriceTimeSeriesWidgetProvider already
        // exist and already started the chain) -- redundant but harmless.
        WidgetUpdateUtil.initializeWidgetUpdates(context)
    }

    override fun onDisabled(context: Context) {
        // Called once, when the last instance of *this* widget type is
        // removed -- the time series widget may still have instances placed,
        // so the shared alarm/watchdog chain is only actually torn down
        // once neither widget type has any instances left.
        WidgetUpdateUtil.handleWidgetProviderInstancesRemoved(context)
    }
}
