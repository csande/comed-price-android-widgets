package com.github.csande.comedpricewidgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.Button
import android.widget.NumberPicker
import android.widget.RadioGroup

/**
 * Shown once when a trend widget is placed (per the "configure" attribute
 * in price_trend_widget_info.xml), and again if the user chooses to
 * reconfigure it. Lets the person pick the chart style (Line/Bar) and how
 * many hours of history the trend chart covers — the same two settings
 * the KDE widget exposes in ConfigGeneral.qml, ported here to their own
 * Activity since AppWidget configuration on Android needs one (RemoteViews
 * has no config-screen mechanism of its own).
 *
 * Per the standard AppWidget configuration contract: the result is
 * RESULT_CANCELED until Save is pressed, so backing out (e.g. via the
 * system back button) removes the half-placed widget instead of leaving
 * it stuck with no configuration.
 */
class PriceTrendWidgetConfigureActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var chartStyleRadioGroup: RadioGroup
    private lateinit var graphHoursPicker: NumberPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_price_trend_widget_configure)

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
        graphHoursPicker.minValue = WidgetUpdateUtil.MIN_TREND_GRAPH_HOURS
        graphHoursPicker.maxValue = WidgetUpdateUtil.MAX_TREND_GRAPH_HOURS
        graphHoursPicker.displayedValues = (WidgetUpdateUtil.MIN_TREND_GRAPH_HOURS..WidgetUpdateUtil.MAX_TREND_GRAPH_HOURS)
            .map { hours -> if (hours == 1) "1 hour" else "$hours hours" }
            .toTypedArray()

        // Prefills from any existing config -- this same activity handles
        // both initial placement (defaults) and later reconfiguration.
        val existingConfig = WidgetUpdateUtil.loadTrendWidgetConfig(this, appWidgetId)
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

    private fun onSaveClicked() {
        val chartStyle = if (chartStyleRadioGroup.checkedRadioButtonId == R.id.chart_style_bar_radio) {
            PriceChartRenderer.ChartStyle.BAR
        } else {
            PriceChartRenderer.ChartStyle.LINE
        }
        val config = WidgetUpdateUtil.TrendWidgetConfig(chartStyle, graphHoursPicker.value)
        WidgetUpdateUtil.saveTrendWidgetConfig(this, appWidgetId, config)

        // Renders immediately from whatever price data is already cached
        // (instant, no network wait) and also kicks off a real fetch in
        // case this is the very first widget placed and nothing has been
        // cached yet -- enqueueImmediateRefresh is idempotent/deduplicated,
        // so this is safe to call regardless.
        WidgetUpdateUtil.renderSingleTrendWidget(this, appWidgetId)
        WidgetUpdateUtil.initializeWidgetUpdates(this)

        val resultValue = android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
