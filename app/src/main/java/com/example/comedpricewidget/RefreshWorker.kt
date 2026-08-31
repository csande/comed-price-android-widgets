package com.example.comedpricewidget

import android.content.Context
import android.os.PowerManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Runs on a WorkManager background thread. Fetches the current price and
 * updates every placed widget instance — unless the screen is currently
 * off, in which case this is a no-op, since there's no point spending a
 * network fetch and battery on an update nobody can see. The next
 * refresh (either the following 5-minute tick if the screen is on by
 * then, or a manual tap) will pick up a current value once someone is
 * actually looking again.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            return Result.success()
        }
        WidgetUpdateUtil.refreshAllWidgets(applicationContext)
        return Result.success()
    }
}
