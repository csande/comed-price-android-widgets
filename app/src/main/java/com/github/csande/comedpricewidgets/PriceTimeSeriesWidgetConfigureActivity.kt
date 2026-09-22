package com.github.csande.comedpricewidgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.NumberPicker
import android.widget.RadioGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Shown once when a time series widget is placed (per the "configure" attribute
 * in price_time_series_widget_info.xml), and again if the user chooses to
 * reconfigure it. Lets the person pick the chart style (Line/Bar) and how
 * many hours the time series chart covers — the same two settings
 * the KDE widget exposes in ConfigGeneral.qml, ported here to their own
 * Activity since AppWidget configuration on Android needs one (RemoteViews
 * has no config-screen mechanism of its own).
 *
 * Per the standard AppWidget configuration contract: the result is
 * RESULT_CANCELED until Save is pressed, so backing out (e.g. via the
 * system back button) removes the half-placed widget instead of leaving
 * it stuck with no configuration.
 */
class PriceTimeSeriesWidgetConfigureActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var chartStyleRadioGroup: RadioGroup
    private lateinit var graphHoursPicker: NumberPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        // Android 15+ (API 35, this app's targetSdk) enforces edge-to-edge:
        // the window no longer automatically reserves space for the status
        // bar / toolbar, so without this the top of the layout (the title
        // and "Chart style" label) renders underneath them and is hidden.
        // Explicitly opting into edge-to-edge here (rather than relying on
        // whether the OS happens to force it) means the inset handling
        // below applies consistently on every API level down to minSdk.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_price_time_series_widget_configure)
        applyRootInsetPadding()

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        chartStyleRadioGroup = findViewById(R.id.chart_style_radio_group)
        graphHoursPicker = findViewById(R.id.graph_hours_picker)
        graphHoursPicker.minValue = WidgetUpdateUtil.MIN_TIME_SERIES_GRAPH_HOURS
        graphHoursPicker.maxValue = WidgetUpdateUtil.MAX_TIME_SERIES_GRAPH_HOURS
        graphHoursPicker.displayedValues = (WidgetUpdateUtil.MIN_TIME_SERIES_GRAPH_HOURS..WidgetUpdateUtil.MAX_TIME_SERIES_GRAPH_HOURS)
            .map { hours -> if (hours == 1) "1 hour" else "$hours hours" }
            .toTypedArray()

        // Prefills from any existing config -- this same activity handles
        // both initial placement (defaults) and later reconfiguration.
        val existingConfig = WidgetUpdateUtil.loadTimeSeriesWidgetConfig(this, appWidgetId)
        chartStyleRadioGroup.check(
            if (existingConfig.chartStyle == PriceChartRenderer.ChartStyle.BAR) {
                R.id.chart_style_bar_radio
            } else {
                R.id.chart_style_line_radio
            }
        )
        graphHoursPicker.value = existingConfig.graphHours

        findViewById<Button>(R.id.save_button).setOnClickListener { onSaveClicked() }
    }

    /**
     * Pads the root layout by the system bars' inset on each side, added
     * on top of its existing 24dp padding (set in XML, still applied as
     * the base here) rather than replacing it -- so content keeps clear
     * of the status bar/toolbar at the top and the gesture/nav bar at the
     * bottom, on top of the layout's own breathing room. Recomputed as an
     * absolute value each time the listener fires (not accumulated), so
     * it's safe if insets change (e.g. rotation) while this is visible.
     */
    private fun applyRootInsetPadding() {
        val root = findViewById<View>(R.id.configure_root)
        val basePaddingPx = root.paddingLeft // the 24dp from XML, same on all four sides
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = basePaddingPx + bars.left,
                top = basePaddingPx + bars.top,
                right = basePaddingPx + bars.right,
                bottom = basePaddingPx + bars.bottom
            )
            windowInsets
        }
    }

    private fun onSaveClicked() {
        val chartStyle = if (chartStyleRadioGroup.checkedRadioButtonId == R.id.chart_style_bar_radio) {
            PriceChartRenderer.ChartStyle.BAR
        } else {
            PriceChartRenderer.ChartStyle.LINE
        }
        val config = WidgetUpdateUtil.TimeSeriesWidgetConfig(chartStyle, graphHoursPicker.value)
        WidgetUpdateUtil.saveTimeSeriesWidgetConfig(this, appWidgetId, config)

        // Renders immediately from whatever price data is already cached
        // (instant, no network wait) and also kicks off a real fetch in
        // case this is the very first widget placed and nothing has been
        // cached yet -- enqueueImmediateRefresh is idempotent/deduplicated,
        // so this is safe to call regardless.
        WidgetUpdateUtil.renderSingleTimeSeriesWidget(this, appWidgetId)
        WidgetUpdateUtil.initializeWidgetUpdates(this)

        val resultValue = android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
