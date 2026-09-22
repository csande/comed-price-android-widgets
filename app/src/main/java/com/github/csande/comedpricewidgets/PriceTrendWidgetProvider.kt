package com.github.csande.comedpricewidgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * AppWidgetProvider for the large, resizable trend widget. Does not run
 * its own alarm/refresh chain -- initializeWidgetUpdates/cancelAlarm/
 * cancelWatchdog are the same single chain shared with PriceWidgetProvider
 * (see WidgetUpdateUtil), so placing/removing instances of either widget
 * type correctly starts or stops that one chain rather than each type
 * managing a redundant chain of its own.
 */
class PriceTrendWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Reached both around initial placement (once EXTRA_APPWIDGET_ID's
        // configure Activity returns RESULT_OK -- see
        // PriceTrendWidgetConfigureActivity) and, redundantly, alongside
        // onEnabled. Safe to call repeatedly: both the watchdog scheduling
        // and the immediate work enqueue it triggers are deduplicated.
        WidgetUpdateUtil.initializeWidgetUpdates(context)
    }

    override fun onEnabled(context: Context) {
        // Called once, when the first instance of this widget type is
        // placed (even if instances of the other widget type already
        // exist and already started the chain) -- redundant but harmless.
        WidgetUpdateUtil.initializeWidgetUpdates(context)
    }

    override fun onDisabled(context: Context) {
        // Called once, when the last instance of *this* widget type is
        // removed -- the compact widget may still have instances placed,
        // so the shared alarm/watchdog chain is only actually torn down
        // once neither widget type has any instances left.
        WidgetUpdateUtil.handleWidgetProviderInstancesRemoved(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Each trend widget instance has its own saved chart-style/
        // history-hours config (see PriceTrendWidgetConfigureActivity);
        // drop it so a removed widget's settings don't linger indefinitely
        // in SharedPreferences under an appWidgetId nothing references
        // anymore.
        for (appWidgetId in appWidgetIds) {
            WidgetUpdateUtil.clearTrendWidgetConfig(context, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // Fired when this instance is resized. Re-renders the chart
        // bitmap at the new size using whatever price data is already
        // cached -- no network fetch needed just to redraw at a new size.
        WidgetUpdateUtil.renderSingleTrendWidget(context, appWidgetId)
    }
}
