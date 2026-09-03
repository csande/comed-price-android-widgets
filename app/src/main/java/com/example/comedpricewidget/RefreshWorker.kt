package com.example.comedpricewidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * The sole "update cycle" worker: fetches the current price, refreshes
 * every placed widget instance, and — regardless of whether the fetch
 * succeeded — records that the chain is alive and re-arms the next
 * 5-minute alarm. Runs unconditionally whether the screen is on or off;
 * there is no screen-state check here. AlarmManager's own Doze-aware
 * throttling is what limits how often this actually runs during idle
 * stretches, so a manual on/off branch here would be redundant with the
 * OS and would leave the widget stale right after a short screen-off
 * period.
 *
 * Triggered by: PriceAlarmReceiver (5-minute cadence), ManualRefreshReceiver
 * (tap), PriceWidgetProvider (initial placement), BootReceiver (reboot),
 * and WatchdogWorker (recovery if the chain above went silent).
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            WidgetUpdateUtil.refreshAllWidgets(applicationContext)
        } finally {
            // Always tick and re-arm the next alarm, even if the fetch
            // above failed or threw, so a single bad run can't stall the
            // chain until the watchdog eventually notices.
            WidgetUpdateUtil.onChainTick(applicationContext)
        }
        return Result.success()
    }
}
