package com.github.csande.comedpricewidgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
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
    private const val KEY_LAST_GOOD_FEED_TIMESTAMP_MILLIS = "last_good_feed_timestamp_millis"
    private const val KEY_LAST_GOOD_RAW_POINTS_JSON = "last_good_raw_points_json"

    // ---- Time series widget per-instance configuration ------------------------
    //
    // Kept in a separate SharedPreferences file from the shared price
    // state above, keyed per appWidgetId: each placed time series widget has
    // its own chart style / time-series-length choice (set via
    // PriceTimeSeriesWidgetConfigureActivity), independent of every other
    // instance and of the compact widget, which has no configuration.

    private const val TIME_SERIES_PREFS_NAME = "comed_price_time_series_widget_prefs"
    private const val KEY_CHART_STYLE_PREFIX = "chart_style_"
    private const val KEY_GRAPH_HOURS_PREFIX = "graph_hours_"

    const val MIN_TIME_SERIES_GRAPH_HOURS = 1
    const val MAX_TIME_SERIES_GRAPH_HOURS = 24
    private const val DEFAULT_TIME_SERIES_GRAPH_HOURS = 2
    private val DEFAULT_CHART_STYLE = PriceChartRenderer.ChartStyle.LINE

    data class TimeSeriesWidgetConfig(val chartStyle: PriceChartRenderer.ChartStyle, val graphHours: Int)

    fun loadTimeSeriesWidgetConfig(context: Context, appWidgetId: Int): TimeSeriesWidgetConfig {
        val prefs = context.getSharedPreferences(TIME_SERIES_PREFS_NAME, Context.MODE_PRIVATE)
        val styleIndex = prefs.getInt(
            KEY_CHART_STYLE_PREFIX + appWidgetId,
            PriceChartRenderer.indexFromChartStyle(DEFAULT_CHART_STYLE)
        )
        val hours = prefs.getInt(KEY_GRAPH_HOURS_PREFIX + appWidgetId, DEFAULT_TIME_SERIES_GRAPH_HOURS)
            .coerceIn(MIN_TIME_SERIES_GRAPH_HOURS, MAX_TIME_SERIES_GRAPH_HOURS)
        return TimeSeriesWidgetConfig(PriceChartRenderer.chartStyleFromIndex(styleIndex), hours)
    }

    fun saveTimeSeriesWidgetConfig(context: Context, appWidgetId: Int, config: TimeSeriesWidgetConfig) {
        context.getSharedPreferences(TIME_SERIES_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CHART_STYLE_PREFIX + appWidgetId, PriceChartRenderer.indexFromChartStyle(config.chartStyle))
            .putInt(KEY_GRAPH_HOURS_PREFIX + appWidgetId, config.graphHours)
            .apply()
    }

    /** Called from PriceTimeSeriesWidgetProvider.onDeleted so removed instances don't leak prefs entries. */
    fun clearTimeSeriesWidgetConfig(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(TIME_SERIES_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CHART_STYLE_PREFIX + appWidgetId)
            .remove(KEY_GRAPH_HOURS_PREFIX + appWidgetId)
            .apply()
    }

    // ---- Widget content -----------------------------------------------

    /**
     * Fetches the latest price and pushes it to every placed widget
     * instance of both widget types (the compact 1x1 widget and the
     * large time series widget) — a single fetch's result is reused for both,
     * rather than each widget type triggering its own network call.
     * Performs blocking network I/O; call only from a background thread
     * (this is invoked from RefreshWorker). Runs unconditionally
     * regardless of screen state — there is no interactive-screen check
     * here. AlarmManager's own Doze-aware throttling (see
     * scheduleNextAlarm) is what limits how often this actually runs
     * during idle stretches, so gating on screen state here would be
     * redundant and would leave the widgets stale immediately after a
     * short screen-off period.
     *
     * The displayed time is the feed's own timestamp for its most recent
     * data point, not when this device happened to poll — see
     * ComedApiClient.PriceResult.Available for why.
     *
     * Two cases redisplay the last successfully fetched price (and its
     * feed timestamp and time series chart) instead of overwriting the widgets
     * with "unavailable":
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
        val widgetIds = priceWidgetIds(context)
        val timeSeriesIds = timeSeriesWidgetIds(context)
        if (widgetIds.isEmpty() && timeSeriesIds.isEmpty()) return

        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No network available; redisplaying last known price instead of fetching")
            renderLastGoodState(context, appWidgetManager, widgetIds)
            renderLastGoodTimeSeriesWidgets(context, appWidgetManager, timeSeriesIds)
            return
        }

        when (val result = ComedApiClient.fetchPrice()) {
            is ComedApiClient.PriceResult.Available -> {
                val band = bandFor(result.average)
                val priceText = formatPrice(context, result.average)
                val updatedText = formatLastUpdated(context, result.feedTimestampMillis)

                saveLastBand(context, band)
                saveLastGoodState(context, priceText, band, result.feedTimestampMillis, result.points)
                renderWidgets(context, appWidgetManager, widgetIds, priceText, updatedText, band)
                renderTimeSeriesWidgets(context, appWidgetManager, timeSeriesIds, priceText, updatedText, band, result.points)
            }
            is ComedApiClient.PriceResult.FeedStale -> {
                renderUnavailable(context, appWidgetManager, widgetIds)
                renderUnavailableTimeSeriesWidgets(context, appWidgetManager, timeSeriesIds)
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
                    renderLastGoodTimeSeriesWidgets(context, appWidgetManager, timeSeriesIds)
                } else {
                    renderUnavailable(context, appWidgetManager, widgetIds)
                    renderUnavailableTimeSeriesWidgets(context, appWidgetManager, timeSeriesIds)
                }
            }
        }
    }

    /** Pushes the given price/time/band to every compact widget instance. */
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
     * the UNKNOWN (grey) band, and the placeholder time — there's no
     * price behind this state, so there's no feed timestamp to attach to
     * it either.
     */
    private fun renderUnavailable(context: Context, appWidgetManager: AppWidgetManager, widgetIds: IntArray) {
        val band = PriceBand.UNKNOWN
        val priceText = context.getString(R.string.price_unavailable)
        val updatedText = context.getString(R.string.time_placeholder)
        saveLastBand(context, band)
        renderWidgets(context, appWidgetManager, widgetIds, priceText, updatedText, band)
    }

    /**
     * Redraws every compact widget instance with the last successfully
     * fetched price and its feed timestamp, used whenever
     * refreshAllWidgets decides this tick should prefer stale data over
     * an explicit unavailable state. Falls back to the
     * unavailable/placeholder strings if no successful fetch has ever
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
        val updatedText = state.feedTimestampMillis?.let { formatLastUpdated(context, it) }
            ?: context.getString(R.string.time_placeholder)
        renderWidgets(context, appWidgetManager, widgetIds, state.priceText, updatedText, state.band)
    }

    /**
     * Immediately swaps every placed widget instance (both types) to a
     * spinner, before the network fetch that will eventually replace it
     * even starts. Called synchronously from ManualRefreshReceiver.onReceive
     * so a tap gets instant visual feedback rather than an unexplained
     * few-second delay.
     */
    fun showLoadingState(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = priceWidgetIds(context)
        val timeSeriesIds = timeSeriesWidgetIds(context)
        if (widgetIds.isEmpty() && timeSeriesIds.isEmpty()) return

        val lastBand = loadLastBand(context)

        for (widgetId in widgetIds) {
            val views = RemoteViews(context.packageName, R.layout.price_widget)
            views.setViewVisibility(R.id.content_container, View.INVISIBLE)
            views.setViewVisibility(R.id.loading_indicator, View.VISIBLE)
            views.setInt(R.id.widget_root, "setBackgroundResource", lastBand.backgroundRes)
            views.setOnClickPendingIntent(R.id.widget_root, buildManualRefreshIntent(context))
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        if (timeSeriesIds.isNotEmpty()) {
            val display = currentTimeSeriesDisplayState(context)
            for (timeSeriesId in timeSeriesIds) {
                val config = loadTimeSeriesWidgetConfig(context, timeSeriesId)
                val slots = buildTimeSeriesSlots(display.rawPoints, config.graphHours)
                val views = buildTimeSeriesWidgetViews(
                    context, appWidgetManager, timeSeriesId, display.priceText, display.updatedText, display.band,
                    slots, config.chartStyle, loading = true
                )
                appWidgetManager.updateAppWidget(timeSeriesId, views)
            }
        }
    }

    private fun formatPrice(context: Context, average: Double): String {
        return String.format(Locale.US, "\u26A1 %.1f\u00A2", average)
    }

    /**
     * Formats the given time using the device's own 12h/24h clock
     * preference. Callers pass the feed's own timestamp for its most
     * recent data point (not "when this device polled"), so this reflects
     * how current the displayed price actually is.
     */
    private fun formatLastUpdated(context: Context, millis: Long): String {
        return DateFormat.getTimeFormat(context).format(Date(millis))
    }

    /** Same device clock preference as formatLastUpdated, handed to the chart renderer for its X-axis labels. */
    private fun chartTimeFormatter(context: Context): (Long) -> String {
        val timeFormat = DateFormat.getTimeFormat(context)
        return { millis -> timeFormat.format(Date(millis)) }
    }

    /**
     * Matches the band legend on ComEd's live-prices page: green under 8,
     * orange from 8 to 14 inclusive, red above 14. UNKNOWN is used when the
     * price is unavailable. colorRes is the same band color used
     * elsewhere (the compact widget's background, PriceChartRenderer's
     * line/bar coloring) -- reused here as the time series widget's price text
     * color, since that widget's background stays neutral rather than
     * changing with the band (see buildTimeSeriesWidgetViews).
     */
    private enum class PriceBand(val backgroundRes: Int, val colorRes: Int) {
        GREEN(R.drawable.widget_background_green, R.color.price_low_color),
        ORANGE(R.drawable.widget_background_orange, R.color.price_medium_color),
        RED(R.drawable.widget_background_red, R.color.price_high_color),
        UNKNOWN(R.drawable.widget_background_unknown, R.color.price_unknown_color)
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

    private data class LastGoodState(
        val priceText: String,
        val band: PriceBand,
        val feedTimestampMillis: Long?,
        val rawPoints: List<ComedApiClient.PricePoint>
    )

    private fun saveLastGoodState(
        context: Context,
        priceText: String,
        band: PriceBand,
        feedTimestampMillis: Long,
        rawPoints: List<ComedApiClient.PricePoint>
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GOOD_PRICE_TEXT, priceText)
            .putString(KEY_LAST_GOOD_BAND, band.name)
            .putLong(KEY_LAST_GOOD_FEED_TIMESTAMP_MILLIS, feedTimestampMillis)
            .putString(KEY_LAST_GOOD_RAW_POINTS_JSON, pointsToJson(rawPoints))
            .apply()
    }

    private fun loadLastGoodState(context: Context): LastGoodState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val priceText = prefs.getString(KEY_LAST_GOOD_PRICE_TEXT, null)
            ?: context.getString(R.string.price_unavailable)
        val band = prefs.getString(KEY_LAST_GOOD_BAND, null)?.let { name ->
            runCatching { PriceBand.valueOf(name) }.getOrNull()
        } ?: PriceBand.UNKNOWN
        val feedTimestampMillis = if (prefs.contains(KEY_LAST_GOOD_FEED_TIMESTAMP_MILLIS)) {
            prefs.getLong(KEY_LAST_GOOD_FEED_TIMESTAMP_MILLIS, 0L)
        } else {
            null
        }
        val rawPoints = jsonToPoints(prefs.getString(KEY_LAST_GOOD_RAW_POINTS_JSON, null))
        return LastGoodState(priceText, band, feedTimestampMillis, rawPoints)
    }

    private fun pointsToJson(points: List<ComedApiClient.PricePoint>): String {
        val array = JSONArray()
        for (point in points) {
            val obj = JSONObject()
            obj.put("m", point.millisUtc)
            obj.put("p", point.price)
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsonToPoints(json: String?): List<ComedApiClient.PricePoint> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            val result = ArrayList<ComedApiClient.PricePoint>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(ComedApiClient.PricePoint(obj.getLong("m"), obj.getDouble("p")))
            }
            result
        } catch (e: Exception) {
            // Corrupt or incompatible cached JSON (e.g. from a future
            // format change) — treat as "no cached time series data" rather than
            // crashing the widget update.
            emptyList()
        }
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

    /** Tapping either widget type sends this broadcast, which triggers an immediate refresh of both. */
    private fun buildManualRefreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, ManualRefreshReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- Time series widget rendering ------------------------------------------

    private const val COMED_LIVE_PRICES_URL = "https://hourlypricing.comed.com/live-prices/"

    /** Tapping the time series widget's link line opens ComEd's live-prices page, separately from the tap-to-refresh area. */
    private fun buildOpenLiveComedPricesIntent(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(COMED_LIVE_PRICES_URL))
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Approximate chart-area size in dp, derived from price_time_series_widget.xml's
    // fixed horizontal chrome (content padding + the Y-axis label column
    // + the chart image's own start margin -- all exact, direct copies of
    // those layout values) and an estimated vertical chrome height (see
    // estimatedChromeHeightDp below, which is where the real guesswork
    // remains, since it depends on font metrics rather than fixed dp
    // values). RemoteViews has no way to measure the actual laid-out
    // ImageView size before rendering into it, so this is a deliberate
    // approximation rather than an exact fit. The chart ImageView uses
    // scaleType="fitXY" so the rendered bitmap fills it edge-to-edge with
    // no letterboxing -- which means any remaining error in this estimate
    // shows up as a slight, usually imperceptible stretch of the chart's
    // gridlines/data (there's no longer any text inside the bitmap for a
    // stretch to visibly distort -- see PriceChartRenderer).
    //
    // Horizontal chrome breakdown (must match price_time_series_widget.xml):
    //   14dp + 14dp   time_series_content_container's paddingStart/paddingEnd
    //   20dp          time_series_y_axis_labels column width
    //    2dp          time_series_chart_image's layout_marginStart
    private const val CHART_HORIZONTAL_CHROME_DP = 14f + 14f + 20f + 2f
    private const val DEFAULT_CHART_WIDTH_DP = 250f
    private const val DEFAULT_CHART_HEIGHT_DP = 150f

    /**
     * Estimates the header row (title / refresh slot / price) plus link
     * row (link / last-updated) plus X-axis label row height, in dp, by
     * measuring real font metrics at the same text sizes
     * price_time_series_widget.xml actually uses, rather than a flat guessed
     * constant. Keep the sizes and padding/margin values below in sync
     * with that layout if it ever changes -- there's no way to query them
     * back from the inflated RemoteViews layout itself.
     */
    private fun estimatedChromeHeightDp(context: Context): Float {
        val displayMetrics = context.resources.displayMetrics
        val scaledDensity = displayMetrics.scaledDensity
        val density = displayMetrics.density

        fun lineHeightDp(textSizeSp: Float, includeFontPadding: Boolean): Float {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.textSize = textSizeSp * scaledDensity
            val fm = paint.fontMetrics
            // Matches TextView's own two line-height modes: with font
            // padding included (the default), TextView reserves
            // fm.bottom - fm.top; with it turned off (time_series_price_text
            // and the axis labels all set includeFontPadding="false"),
            // just fm.descent - fm.ascent.
            val heightPx = if (includeFontPadding) fm.bottom - fm.top else fm.descent - fm.ascent
            return heightPx / density
        }

        val titleLineHeightDp = lineHeightDp(19f, includeFontPadding = true)
        val priceLineHeightDp = lineHeightDp(20f, includeFontPadding = false)
        val refreshSlotHeightDp = 32f // fixed-size FrameLayout, not text-driven
        val headerRowHeightDp = maxOf(titleLineHeightDp, priceLineHeightDp, refreshSlotHeightDp)

        val linkLineHeightDp = lineHeightDp(9f, includeFontPadding = true)
        val timeLineHeightDp = lineHeightDp(9f, includeFontPadding = true)
        val linkRowMarginBottomDp = 4f
        val linkRowHeightDp = maxOf(linkLineHeightDp, timeLineHeightDp) + linkRowMarginBottomDp

        val xAxisLabelLineHeightDp = lineHeightDp(10f, includeFontPadding = false)
        val xAxisRowMarginTopDp = 0f
        val xAxisRowHeightDp = xAxisLabelLineHeightDp + xAxisRowMarginTopDp

        val paddingTopDp = 4f
        val paddingBottomDp = 10f

        return paddingTopDp + headerRowHeightDp + linkRowHeightDp + xAxisRowHeightDp + paddingBottomDp
    }

    private fun computeChartPixelSize(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ): Pair<Int, Int> {
        val options = runCatching { appWidgetManager.getAppWidgetOptions(appWidgetId) }.getOrNull()
        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            ?.takeIf { it > 0 } ?: DEFAULT_CHART_WIDTH_DP.toInt()
        val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            ?.takeIf { it > 0 } ?: DEFAULT_CHART_HEIGHT_DP.toInt()

        val density = context.resources.displayMetrics.density
        val widthPx = ((minWidthDp - CHART_HORIZONTAL_CHROME_DP) * density).toInt().coerceAtLeast(1)
        val heightPx = ((minHeightDp - estimatedChromeHeightDp(context)) * density).toInt().coerceAtLeast(1)
        return Pair(widthPx, heightPx)
    }

    /**
     * ComEd's feed publishes on a 5-minute cadence (matches
     * PriceAlarmReceiver's own 5-minute alarm interval). Used below to
     * recognize when one or more expected points are missing from the
     * feed, rather than just connecting whatever points happen to exist.
     */
    private const val EXPECTED_POINT_INTERVAL_MINUTES = 5.0

    /**
     * A gap between two consecutive real points wider than this is
     * treated as "at least one point is missing" rather than ordinary
     * jitter in when the feed happened to publish or this device
     * happened to poll. 1.5x the expected interval gives comfortable
     * room for a few minutes of jitter (see ComedApiClient's own
     * FEED_STALENESS_THRESHOLD_MINUTES comment for the kind of timing
     * slop this feed exhibits normally) without also firing on every
     * ordinary tick.
     */
    private const val MISSING_POINT_GAP_THRESHOLD_MINUTES = EXPECTED_POINT_INTERVAL_MINUTES * 1.5

    /**
     * Turns the raw, filtered feed points into the slot list
     * PriceChartRenderer actually draws: every point still where it was,
     * plus a PriceChartRenderer.TimeSeriesSlot.Missing inserted for each
     * expected 5-minute point that isn't there, rather than silently
     * leaving that gap for the renderer to paper over by connecting (line
     * chart) or compressing past (bar chart) straight through it. Also
     * applies the existing `hours` window and oldest-first sort that
     * filterTimeSeries used to do on its own — independent of
     * ComedApiClient's own MAX_AGE_MINUTES, which governs the
     * weighted-average calculation, not the graph.
     *
     * Missing slots are only ever inserted *between* two real points
     * (never before the first or after the last), so the returned list's
     * first and last entries are always Present -- callers that need an
     * anchor point (e.g. for the X axis's own time range) can rely on
     * that.
     */
    private fun buildTimeSeriesSlots(
        points: List<ComedApiClient.PricePoint>,
        hours: Int
    ): List<PriceChartRenderer.TimeSeriesSlot> {
        if (points.isEmpty()) return emptyList()
        val mostRecent = points.maxOf { it.millisUtc }
        val cutoff = mostRecent - hours * 60L * 60L * 1000L
        val filtered = points.filter { it.millisUtc >= cutoff }.sortedBy { it.millisUtc }
        if (filtered.isEmpty()) return emptyList()

        val intervalMillis = (EXPECTED_POINT_INTERVAL_MINUTES * 60_000.0).toLong()
        val gapThresholdMillis = (MISSING_POINT_GAP_THRESHOLD_MINUTES * 60_000.0).toLong()

        val slots = mutableListOf<PriceChartRenderer.TimeSeriesSlot>()
        slots.add(PriceChartRenderer.TimeSeriesSlot.Present(filtered[0].millisUtc, filtered[0].price))
        for (i in 1 until filtered.size) {
            val previous = filtered[i - 1]
            val current = filtered[i]
            val gapMillis = current.millisUtc - previous.millisUtc
            if (gapMillis > gapThresholdMillis) {
                // One or more expected points are missing between these
                // two -- step forward in exact 5-minute increments from
                // the previous real point, inserting a Missing slot for
                // each one that isn't (close enough to) the next real
                // point, so a longer outage yields proportionally more
                // Missing slots rather than just one regardless of size.
                var expectedMillis = previous.millisUtc + intervalMillis
                while (current.millisUtc - expectedMillis > intervalMillis / 2) {
                    slots.add(PriceChartRenderer.TimeSeriesSlot.Missing(expectedMillis))
                    expectedMillis += intervalMillis
                }
            }
            slots.add(PriceChartRenderer.TimeSeriesSlot.Present(current.millisUtc, current.price))
        }
        return slots
    }

    /**
     * Builds the RemoteViews for one time series widget instance. Content (price,
     * time, chart) always stays visible -- unlike the compact widget, a
     * refresh in progress is shown only via the small refresh
     * icon/spinner slot in the header (time_series_refresh_slot), matching the
     * KDE widget's ToolButton/BusyIndicator pair: `loading` toggles which
     * of the two is visible, everything else renders normally either way.
     */
    private fun buildTimeSeriesWidgetViews(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        priceText: String,
        updatedText: String,
        band: PriceBand,
        slots: List<PriceChartRenderer.TimeSeriesSlot>,
        chartStyle: PriceChartRenderer.ChartStyle,
        loading: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.price_time_series_widget)
        views.setTextViewText(R.id.time_series_price_text, priceText)
        views.setTextColor(R.id.time_series_price_text, ContextCompat.getColor(context, band.colorRes))
        views.setTextViewText(R.id.time_series_last_updated_text, updatedText)

        views.setViewVisibility(R.id.time_series_refresh_icon, if (loading) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.time_series_refresh_spinner, if (loading) View.VISIBLE else View.GONE)
        views.setOnClickPendingIntent(R.id.time_series_refresh_icon, buildManualRefreshIntent(context))

        // Unlike the compact widget, tapping this widget's background
        // does nothing -- only the refresh icon above and the link below
        // are individually tappable, matching the KDE widget (tapping
        // its ToolButton refreshes; tapping elsewhere in the popup
        // doesn't). This widget's background also doesn't change with
        // the price band -- see the fixed widget_background_neutral
        // drawable set on time_series_widget_root in price_time_series_widget.xml.
        // The price is instead conveyed by the chart's line/bar coloring
        // (see PriceChartRenderer) and the price text itself, matching
        // how standard Android widgets (Gmail, Calendar, News, etc.) keep
        // a consistent neutral card and let the content inside carry any
        // color-coding.
        views.setOnClickPendingIntent(R.id.time_series_link_text, buildOpenLiveComedPricesIntent(context))

        val (widthPx, heightPx) = computeChartPixelSize(context, appWidgetManager, appWidgetId)
        val chart = PriceChartRenderer.render(
            context, widthPx, heightPx, slots, chartStyle, chartTimeFormatter(context)
        )
        views.setImageViewBitmap(R.id.time_series_chart_image, chart.bitmap)

        val (topLabel, upperMidLabel, lowerMidLabel, bottomLabel) = chart.yAxisLabelsTopToBottom
        views.setTextViewText(R.id.time_series_y_label_top, topLabel)
        views.setTextViewText(R.id.time_series_y_label_upper_mid, upperMidLabel)
        views.setTextViewText(R.id.time_series_y_label_lower_mid, lowerMidLabel)
        views.setTextViewText(R.id.time_series_y_label_bottom, bottomLabel)
        views.setTextViewText(R.id.time_series_x_label_start, chart.xAxisLabelStart)
        views.setTextViewText(R.id.time_series_x_label_mid, chart.xAxisLabelMid)
        views.setTextViewText(R.id.time_series_x_label_end, chart.xAxisLabelEnd)

        return views
    }

    /** Pushes the given price/time/band/time series data to every time series widget instance, each filtered to its own configured time series window. */
    private fun renderTimeSeriesWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        timeSeriesIds: IntArray,
        priceText: String,
        updatedText: String,
        band: PriceBand,
        rawPoints: List<ComedApiClient.PricePoint>
    ) {
        for (timeSeriesId in timeSeriesIds) {
            val config = loadTimeSeriesWidgetConfig(context, timeSeriesId)
            val slots = buildTimeSeriesSlots(rawPoints, config.graphHours)
            val views = buildTimeSeriesWidgetViews(
                context, appWidgetManager, timeSeriesId, priceText, updatedText, band, slots, config.chartStyle,
                loading = false
            )
            appWidgetManager.updateAppWidget(timeSeriesId, views)
        }
    }

    private fun renderUnavailableTimeSeriesWidgets(context: Context, appWidgetManager: AppWidgetManager, timeSeriesIds: IntArray) {
        renderTimeSeriesWidgets(
            context, appWidgetManager, timeSeriesIds,
            context.getString(R.string.price_unavailable),
            context.getString(R.string.time_placeholder),
            PriceBand.UNKNOWN,
            emptyList()
        )
    }

    private fun renderLastGoodTimeSeriesWidgets(context: Context, appWidgetManager: AppWidgetManager, timeSeriesIds: IntArray) {
        val state = loadLastGoodState(context)
        val updatedText = state.feedTimestampMillis?.let { formatLastUpdated(context, it) }
            ?: context.getString(R.string.time_placeholder)
        renderTimeSeriesWidgets(context, appWidgetManager, timeSeriesIds, state.priceText, updatedText, state.band, state.rawPoints)
    }

    private data class TimeSeriesDisplayState(
        val priceText: String,
        val updatedText: String,
        val band: PriceBand,
        val rawPoints: List<ComedApiClient.PricePoint>
    )

    /**
     * What should currently be on screen for the time series widgets, derived
     * from the last completed refresh (not a new fetch) -- either the
     * explicit "unavailable" state, if that's how the last tick ended, or
     * the last successfully fetched price/time/time series data otherwise. Shared
     * by showLoadingState (flashing the refresh spinner without touching
     * the rest of the content) and renderSingleTimeSeriesWidget (resize/config
     * re-renders).
     */
    private fun currentTimeSeriesDisplayState(context: Context): TimeSeriesDisplayState {
        val band = loadLastBand(context)
        return if (band == PriceBand.UNKNOWN) {
            TimeSeriesDisplayState(
                context.getString(R.string.price_unavailable),
                context.getString(R.string.time_placeholder),
                PriceBand.UNKNOWN,
                emptyList()
            )
        } else {
            val state = loadLastGoodState(context)
            val updatedText = state.feedTimestampMillis?.let { formatLastUpdated(context, it) }
                ?: context.getString(R.string.time_placeholder)
            TimeSeriesDisplayState(state.priceText, updatedText, state.band, state.rawPoints)
        }
    }

    /**
     * Re-renders exactly one time series widget instance from whatever price
     * state is currently on screen (the last completed refresh's result —
     * not a new fetch), at that instance's current size and its own
     * chart-style/time-series-length config. Used after a resize
     * (PriceTimeSeriesWidgetProvider.onAppWidgetOptionsChanged) and right after
     * saving new configuration (PriceTimeSeriesWidgetConfigureActivity), so
     * both cases reflect immediately without waiting for the next
     * 5-minute tick.
     */
    fun renderSingleTimeSeriesWidget(context: Context, appWidgetId: Int) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val display = currentTimeSeriesDisplayState(context)
        val config = loadTimeSeriesWidgetConfig(context, appWidgetId)
        val slots = buildTimeSeriesSlots(display.rawPoints, config.graphHours)
        val views = buildTimeSeriesWidgetViews(
            context, appWidgetManager, appWidgetId, display.priceText, display.updatedText, display.band,
            slots, config.chartStyle, loading = false
        )
        appWidgetManager.updateAppWidget(appWidgetId, views)
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
     * schedule the next alarm once it completes. Called from either
     * provider's onEnabled/onUpdate (initial placement) and BootReceiver —
     * safe to call redundantly, since both the watchdog scheduling and
     * the immediate work enqueue are deduplicated. There is only ever one
     * chain shared by both widget types (see hasAnyWidgetsPlaced /
     * handleWidgetProviderInstancesRemoved).
     */
    fun initializeWidgetUpdates(context: Context) {
        scheduleWatchdog(context)
        enqueueImmediateRefresh(context)
    }

    /** Widget ids for the compact 1x1 widget (PriceWidgetProvider). */
    private fun priceWidgetIds(context: Context): IntArray {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, PriceWidgetProvider::class.java)
        )
    }

    /** Widget ids for the large time series widget (PriceTimeSeriesWidgetProvider). */
    private fun timeSeriesWidgetIds(context: Context): IntArray {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, PriceTimeSeriesWidgetProvider::class.java)
        )
    }

    /** True if at least one instance of either widget type is currently placed. Used by BootReceiver and WatchdogWorker. */
    fun hasAnyWidgetsPlaced(context: Context): Boolean {
        return priceWidgetIds(context).isNotEmpty() || timeSeriesWidgetIds(context).isNotEmpty()
    }

    /**
     * Called from either provider's onDisabled (fired when the *last*
     * instance of that specific widget type is removed). Only actually
     * tears down the shared alarm/watchdog chain once neither widget type
     * has any instances left — removing the last compact widget while a
     * time series widget is still placed (or vice versa) must not stop updates
     * for the one that remains.
     */
    fun handleWidgetProviderInstancesRemoved(context: Context) {
        if (!hasAnyWidgetsPlaced(context)) {
            cancelAlarm(context)
            cancelWatchdog(context)
        }
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
