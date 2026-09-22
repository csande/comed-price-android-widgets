package com.github.csande.comedpricewidgets

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Runs at most every 15 minutes (WorkManager's practical floor for
 * periodic work). Does not itself fetch price data — it only checks
 * whether the alarm-driven update chain (see WidgetUpdateUtil.onChainTick)
 * has ticked recently. If it hasn't, something dropped the chain (e.g.
 * the alarm was cleared by the OS, or a RefreshWorker run was killed
 * before it could re-arm the next alarm), so this kicks off a fresh
 * update cycle, which will re-arm the alarm itself once it completes.
 *
 * This is a safety net, not part of the normal 5-minute cadence: under
 * ordinary operation it should find the chain healthy and do nothing on
 * every run.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!WidgetUpdateUtil.hasAnyWidgetsPlaced(applicationContext)) return Result.success()

        if (WidgetUpdateUtil.isChainStale(applicationContext)) {
            WidgetUpdateUtil.enqueueImmediateRefresh(applicationContext)
        }
        return Result.success()
    }
}
