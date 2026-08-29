package com.example.comedpricewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Locale
import java.util.concurrent.TimeUnit

object WidgetUpdateUtil {

    private const val ALARM_INTERVAL_MILLIS = 5L * 60L * 1000L
    private const val FALLBACK_WORK_NAME = "comed_price_fallback_refresh"
    private const val IMMEDIATE_WORK_NAME = "comed_price_immediate_refresh"
    private const val LIVE_PRICES_URL = "https://hourlypricing.comed.com/live-prices/"

    // ---- Widget content -----------------------------------------------

    /**
     * Fetches the latest price and pushes it to every placed widget
     * instance. Performs blocking network I/O; call only from a
     * background thread (this is invoked from RefreshWorker).
     */
    fun refreshAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, PriceWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return

        val average = ComedApiClient.fetchAveragePrice()
        val displayText = formatPrice(context, average)
        val backgroundRes = backgroundResFor(average)

        for (widgetId in widgetIds) {
            val views = RemoteViews(context.packageName, R.layout.price_widget)
            views.setTextViewText(R.id.price_text, displayText)
            views.setInt(R.id.widget_root, "setBackgroundResource", backgroundRes)
            views.setOnClickPendingIntent(R.id.price_text, buildOpenBrowserIntent(context))
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun formatPrice(context: Context, average: Double?): String {
        if (average == null) return context.getString(R.string.price_unavailable)
        return String.format(Locale.US, "\u26A1%.1f\u00A2", average)
    }

    /**
     * Background drawable for the given price (cents/kWh), matching the
     * band legend on ComEd's live-prices page: green under 8, orange from
     * 8 to 14 inclusive, red above 14. A neutral gray is used when the
     * price is unavailable.
     */
    private fun backgroundResFor(average: Double?): Int {
        if (average == null) return R.drawable.widget_background_unknown
        return when {
            average < 8.0 -> R.drawable.widget_background_green
            average <= 14.0 -> R.drawable.widget_background_orange
            else -> R.drawable.widget_background_red
        }
    }

    private fun buildOpenBrowserIntent(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LIVE_PRICES_URL)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- WorkManager (does the actual background refresh) -------------

    /**
     * Enqueues a one-off background refresh right away. Uses a unique work
     * name with KEEP semantics so that near-simultaneous callers (e.g.
     * onEnabled and onUpdate both firing when a widget is first placed)
     * don't trigger duplicate concurrent network requests.
     */
    fun enqueueImmediateRefresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().build()
        )
    }

    /**
     * Schedules a 15-minute periodic refresh (WorkManager's minimum
     * interval) as a safety net in case the AlarmManager chain below gets
     * dropped by the OS (e.g. aggressive OEM battery management).
     */
    fun schedulePeriodicFallback(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FALLBACK_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelPeriodicFallback(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FALLBACK_WORK_NAME)
    }

    // ---- AlarmManager (drives the true 5-minute cadence) ---------------

    /** Schedules (or re-schedules) the next 5-minute alarm-driven refresh. */
    fun scheduleNextAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmPendingIntent(context)
        val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MILLIS

        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            // No exact-alarm grant: falls back to an inexact wakeup, which
            // the OS may delay. See README for the manual permission step.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PriceAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
