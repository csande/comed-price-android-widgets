package com.example.comedpricewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by AlarmManager roughly every 5 minutes. Immediately reschedules
 * the next alarm (so the cadence continues even if the refresh work below
 * is slow or fails) and hands the actual network refresh off to
 * WorkManager, since BroadcastReceiver.onReceive must not block.
 */
class PriceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUpdateUtil.scheduleNextAlarm(context)
        WidgetUpdateUtil.enqueueImmediateRefresh(context)
    }
}
