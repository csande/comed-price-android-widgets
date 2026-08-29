package com.example.comedpricewidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Runs on a WorkManager background thread. Fetches the current price and
 * updates every placed widget instance.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        WidgetUpdateUtil.refreshAllWidgets(applicationContext)
        return Result.success()
    }
}
