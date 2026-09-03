package com.example.comedpricewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by AlarmManager roughly every 5 minutes (subject to Doze
 * throttling while the device is idle). Hands off to WorkManager, since
 * BroadcastReceiver.onReceive must not block. Scheduling the next alarm
 * is no longer done here — RefreshWorker re-arms it once the update
 * cycle finishes (see WidgetUpdateUtil.onChainTick), guaranteeing that
 * happens even if the cycle's fetch fails.
 */
class PriceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUpdateUtil.enqueueImmediateRefresh(context)
    }
}
