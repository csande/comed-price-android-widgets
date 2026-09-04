package com.example.comedpricewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object WidgetUpdateUtil {

    private const val TAG = "WidgetUpdateUtil"

    private const val ALARM_INTERVAL_MILLIS = 5L * 60L * 1000L
    private const val IMMEDIATE_WORK_NAME = "comed_price_immediate_refresh"
    private const val WATCHDOG_WORK_NAME = "comed_price_watchdog"
    private const val WATCHDOG_INTERVAL_MINUTES = 15L

    /**
     * The chain is considered dead if no update cycle has ticked (see
     * onChainTick) in this long. Set comfortably above the 5-minute alarm
     * interval so a single alarm delayed by light Doze throttling doesn't
     * falsely trigger a watchdog recovery.
     */
    private const val WATCHDOG_STALE_THRESHOLD_MILLIS = 12L * 60L * 1000L

    private const val PREFS_NAME = "comed_price_widget_prefs"
    private const val KEY_LAST_BAND = "last_band"
    private const val KEY_LAST_TICK_MILLIS = "last_chain_tick_millis"
    private const val KEY_LAST_GOOD_PRICE_TEXT = "last_good_price_text"
    private const val KEY_LAST_GOOD_BAND = "last_good_band"
    private const val KEY_LAST_GOOD_UPDATED_MILLIS = "last_good_updated_millis"

    // ---- Widget content -----------------------------------------------

    /**
     * Fetches the latest price and pushes it to every placed widget
     * instance. Performs blocking network I/O; call only from a
     * background thread (this is invoked from RefreshWorker). Runs
     * unconditionally regardless of screen state — there is no
     * interactive-screen check here. AlarmManager's own Doze-aware
     * throttling (see scheduleNextAlarm) is what limits how often this
     * actually runs during idle stretches, so gating on screen state here
     * would be redundant and would leave the widget stale immediately
     * after a short screen-off period.
     *
     * Three cases redisplay the last successfully fetched price instead
     * of overwriting the widget with "unavailable":
     *  - No usable network connection is detected up front (skips the
     *    fetch attempt entirely).
     *  - The fetch was attempted but failed in a way that never got as
     *    far as an HTTP response (only connection-level exceptions across
     *    every retry), while the device is currently in Doze idle mode —
     *    a combination that looks enough like "no network was actually
     *    available for this attempt" to prefer stale data over an
     *    explicit unavailable state.
     * A feed-staleness result (the request succeeded, but the data
     * itself is too old — see ComedApiClient) and any other fetch
     * failure both still render as an explicit "unavailable" state,
     * since those aren't connectivity problems.
     */
    fun refreshAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, PriceWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return

        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No network available; redisplaying last known price instead of fetching")
            renderLastGoodState(context, appWidgetManager, widgetIds)
            return
        }

        when (val result = ComedApiClient.fetchPrice()) {
            is ComedApiClient.PriceResult.Available -> {
                val band = bandFor(result.average)
                val priceText = formatPrice(context, result.average)
                val nowMillis = System.currentTimeMillis()
                val updatedText = formatLastUpdated(context, nowMillis)

                saveLastBand(context, band)
                saveLastGoodState(context, priceText, band, nowMillis)
                renderWidgets(context, appWidgetManager, widgetIds, priceText, updatedText, band)
            }
            is ComedApiClient.PriceResult.FeedStale -> {
                renderUnavailable(context, appWidgetManager, widgetIds)
            }
            is ComedApiClient.PriceResult.FetchFailed -> {
                val presumedNoConnectivity = result.likelyConnectivityIssue && isDeviceIdleModeActive(context)
                if (presumedNoConnectivity) {
                    Log.d(
                        TAG,
                        "Fetch never reached the server and device is idle; treating as no network, " +
                                "redisplaying last known price"
                    )
                    renderLastGoodState(context, appWidgetManager, widgetIds)
                } else {
                    renderUnavailable(context, appWidgetManager, widgetIds)
                }
            }
        }
    }

    /** Pushes the given price/time/band to every widget instance. */
    private fun renderWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetIds: IntArray,
        priceText: String,
        updatedText: String,
        band: PriceBand
    ) {
        for (widgetId in widgetIds) {
            val views = RemoteViews(context.packageName, R.layout.price_widget)
            views.setTextViewText(R.id.price_text, priceText)
            views.setTextViewText(R.id.last_updated_text, updatedText)
            views.setViewVisibility(R.id.content_container, View.VISIBLE)
            views.setViewVisibility(R.id.loading_indicator, View.INVISIBLE)
            views.setInt(R.id.widget_root, "setBackgroundResource", band.backgroundRes)
            views.setOnClickPendingIntent(R.id.widget_root, buildManualRefreshIntent(context))
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    /**
     * Renders the explicit "unavailable" state: the unavailable string,
     * the UNKNOWN (grey) band, and the current time as the last-attempted
     * timestamp.
     */
    private fun renderUnavailable(context: Context, appWidgetManager: AppWidgetManager, widgetIds: IntArray) {
        val band = PriceBand.UNKNOWN
        val priceText = context.getString(R.string.price_unavailable)
        val updatedText = formatLastUpdated(context, System.currentTimeMillis())
        saveLastBand(context, band)
        renderWidgets(context, appWidgetManager, widgetIds, priceText, updatedText, band)
    }

    /**
     * Redraws every widget instance with the last successfully fetched
     * price, used whenever refreshAllWidgets decides this tick should
     * prefer stale data over an explicit unavailable state. Falls back to
     * the unavailable/placeholder strings if no successful fetch has ever
     * completed.
     */
    private fun renderLastGoodState(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetIds: IntArray
    ) {
        val state = loadLastGoodState(context)
        // Keep the spinner-coloring band (see showLoadingState) consistent
        // with what's actually on screen right now.
        saveLastBand(context, state.band)
        val updatedText = state.updatedMillis?.let { formatLastUpdated(context, it) }
            ?: context.getString(R.string.time_placeholder)
        renderWidgets(context, appWidgetManager, widgetIds, state.priceText, updatedText, state.band)
    }

    /**
     * Immediately swaps every placed widget to a spinner, before the
     * network fetch that will eventually replace it even starts. Called
     * synchronously from ManualRefreshReceiver.onReceive so a tap gets
     * instant visual feedback rather than an unexplained few-second delay.
     */
    fun showLoadingState(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, PriceWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return

        val lastBand = loadLastBand(context)

        for (widgetId in widgetIds) {
            val views = RemoteViews(context.packageName, R.layout.price_widget)
            views.setViewVisibility(R.id.content_container, View.INVISIBLE)
            views.setViewVisibility(R.id.loading_indicator, View.VISIBLE)
            views.setInt(R.id.widget_root, "setBackgroundResource", lastBand.backgroundRes)
            views.setOnClickPendingIntent(R.id.widget_root, buildManualRefreshIntent(context))
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun formatPrice(context: Context, average: Double): String {
        return String.format(Locale.US, "\u26A1 %.1f\u00A2", average)
    }

    /**
     * Formats the given time using the device's own 12h/24h clock
     * preference, so the widget shows when it was last actually refreshed.
     */
    private fun formatLastUpdated(context: Context, millis: Long): String {
        return DateFormat.getTimeFormat(context).format(Date(millis))
    }

    /**
     * Matches the band legend on ComEd's live-prices page: green under 8,
     * orange from 8 to 14 inclusive, red above 14. UNKNOWN is used when the
     * price is unavailable.
     */
    private enum class PriceBand(val backgroundRes: Int) {
        GREEN(R.drawable.widget_background_green),
        ORANGE(R.drawable.widget_background_orange),
        RED(R.drawable.widget_background_red),
        UNKNOWN(R.drawable.widget_background_unknown)
    }

    private fun bandFor(average: Double): PriceBand {
        return when {
            average < 8.0 -> PriceBand.GREEN
            average <= 14.0 -> PriceBand.ORANGE
            else -> PriceBand.RED
        }
    }

    private fun saveLastBand(context: Context, band: PriceBand) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_BAND, band.name)
            .apply()
    }

    private fun loadLastBand(context: Context): PriceBand {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_BAND, null)
        return stored?.let { name ->
            runCatching { PriceBand.valueOf(name) }.getOrNull()
        } ?: PriceBand.UNKNOWN
    }

    private data class LastGoodState(val priceText: String, val band: PriceBand, val updatedMillis: Long?)

    private fun saveLastGoodState(context: Context, priceText: String, band: PriceBand, updatedMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GOOD_PRICE_TEXT, priceText)
            .putString(KEY_LAST_GOOD_BAND, band.name)
            .putLong(KEY_LAST_GOOD_UPDATED_MILLIS, updatedMillis)
            .apply()
    }

    private fun loadLastGoodState(context: Context): LastGoodState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val priceText = prefs.getString(KEY_LAST_GOOD_PRICE_TEXT, null)
            ?: context.getString(R.string.price_unavailable)
        val band = prefs.getString(KEY_LAST_GOOD_BAND, null)?.let { name ->
            runCatching { PriceBand.valueOf(name) }.getOrNull()
        } ?: PriceBand.UNKNOWN
        val updatedMillis = if (prefs.contains(KEY_LAST_GOOD_UPDATED_MILLIS)) {
            prefs.getLong(KEY_LAST_GOOD_UPDATED_MILLIS, 0L)
        } else {
            null
        }
        return LastGoodState(priceText, band, updatedMillis)
    }

    /**
     * True if the device currently has a network connection with
     * confirmed internet access. This only catches client-side-visible
     * unavailability (airplane mode, no signal, Wi-Fi association without
     * internet, etc.) — it can't detect Doze's per-app background network
     * suspension in advance, since the OS doesn't expose that as a
     * queryable state ahead of an attempt. That case is instead handled
     * after the fact, by combining a connectivity-like fetch failure with
     * isDeviceIdleModeActive() below.
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true // Can't determine; fail open rather than silently never refreshing.

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * True if the device is currently in Doze's idle mode. Used only as
     * a post-failure signal (see refreshAllWidgets), never to decide
     * whether to attempt a fetch in the first place — the device can
     * still have working network access during a specific alarm-granted
     * execution window even while this reports true, so gating the
     * attempt on it would skip ticks that were likely to succeed.
     * Doze doesn't exist before API 23, so this is unconditionally false
     * on older devices.
     */
    private fun isDeviceIdleModeActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isDeviceIdleMode
    }

    /** Tapping the widget sends this broadcast, which triggers an immediate refresh. */
    private fun buildManualRefreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, ManualRefreshReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- Update chain bookkeeping ---------------------------------------

    /**
     * Called from RefreshWorker once an update cycle finishes, whether or
     * not the fetch itself succeeded. Records that the chain is still
     * alive and re-arms the next alarm — this is the single place
     * responsible for keeping the 5-minute cadence going, so every
     * trigger (alarm, tap, boot, initial placement, watchdog recovery)
     * funnels through it via RefreshWorker.
     */
    fun onChainTick(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_TICK_MILLIS, System.currentTimeMillis())
            .apply()
        scheduleNextAlarm(context)
    }

    /**
     * True if the chain hasn't ticked recently enough to trust that the
     * alarm is still going to fire on its own. A value of 0 (never
     * ticked) is treated as stale, which also covers the case of a chain
     * that never got off the ground.
     */
    fun isChainStale(context: Context): Boolean {
        val lastTick = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_TICK_MILLIS, 0L)
        return (System.currentTimeMillis() - lastTick) > WATCHDOG_STALE_THRESHOLD_MILLIS
    }

    /**
     * Starts (or idempotently resumes) the update chain: arms the
     * watchdog and runs an immediate update cycle, which will itself
     * schedule the next alarm once it completes. Called from onEnabled,
     * onUpdate (initial placement), and BootReceiver — safe to call
     * redundantly, since both the watchdog scheduling and the immediate
     * work enqueue are deduplicated.
     */
    fun initializeWidgetUpdates(context: Context) {
        scheduleWatchdog(context)
        enqueueImmediateRefresh(context)
    }

    // ---- WorkManager (runs the update cycle, and the watchdog) ---------

    /**
     * Enqueues one run of the update cycle (fetch + refresh widgets +
     * re-arm the next alarm) right away. Uses a unique work name with
     * KEEP semantics so that near-simultaneous callers (e.g. an alarm
     * firing right as a manual tap comes in) don't trigger duplicate
     * concurrent network requests — whichever run actually executes will
     * still re-arm the alarm for everyone.
     */
    fun enqueueImmediateRefresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().build()
        )
    }

    /**
     * Schedules a low-frequency (15-minute, WorkManager's practical floor)
     * periodic check of whether the alarm chain above is still alive. This
     * is not part of the normal 5-minute cadence — it only intervenes
     * (see WatchdogWorker) if the chain has gone silent, e.g. the alarm
     * or its PendingIntent was cleared by the OS, or a RefreshWorker run
     * was killed mid-execution before it could re-arm the next alarm.
     */
    fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
            WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelWatchdog(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK_NAME)
    }

    // ---- AlarmManager (drives the 5-minute cadence) ---------------------

    /**
     * Schedules (or re-schedules) the next 5-minute alarm-driven update.
     * Reusing the same PendingIntent request code means this both cancels
     * any existing pending trigger and installs the new one in a single
     * call. Subject to Doze-aware throttling by the OS while the device
     * is idle — that throttling, not any screen-state check in this app,
     * is what keeps the cadence off battery during long idle stretches.
     */
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
