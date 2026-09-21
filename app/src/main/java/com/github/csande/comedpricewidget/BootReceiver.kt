package com.github.csande.comedpricewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager alarms and unpersisted WorkManager jobs are both cleared
 * on reboot, so the update chain is restarted here if any instance of
 * either widget type is currently placed on a homescreen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!WidgetUpdateUtil.hasAnyWidgetsPlaced(context)) return

        WidgetUpdateUtil.initializeWidgetUpdates(context)
    }
}
