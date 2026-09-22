package com.github.csande.comedpricewidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Draws the time series chart shown by the large, resizable widget onto a
 * Bitmap, which PriceTimeSeriesWidgetProvider then pushes into a RemoteViews
 * ImageView -- RemoteViews has no way to host arbitrary custom drawing
 * directly (unlike the KDE widget's QML Canvas, which paints straight
 * into the popup), so a bitmap rendered here each refresh/resize/config
 * change is the standard substitute.
 *
 * Unlike the KDE original, this renderer draws only the chart's geometry
 * (gridlines, zero line, axis line, and the plotted line/bars) into the
 * bitmap -- no text. The bitmap gets stretched to fill its ImageView via
 * scaleType="fitXY", and RemoteViews has no way to learn that ImageView's
 * true final pixel size before rendering into it, so the estimated size
 * this is rendered at (see WidgetUpdateUtil.computeChartPixelSize) can be
 * slightly off; a slight stretch of straight lines/gridlines is
 * essentially invisible, but the same stretch visibly distorted axis-label
 * text when text used to be drawn into this same bitmap. Axis labels are
 * now real TextViews laid out around this ImageView in
 * price_time_series_widget.xml (immune to bitmap scaling, exactly like the
 * price/title text already was), and this function returns the label
 * strings alongside the bitmap so the caller can populate them.
 *
 * Two chart styles, matching the KDE port's PriceGraph.qml:
 *  - LINE: each segment between two points is split at the exact
 *    point(s) where the interpolated price crosses a band threshold (8c
 *    or 14c), so a green stretch of line can never be drawn higher than
 *    an orange one -- color and height stay mathematically tied together
 *    everywhere along the line, not just at the plotted points.
 *  - BAR: each point is its own bar, colored by its own price, drawn
 *    from a zero baseline (ComEd prices occasionally go negative).
 *
 * The color bands (GREEN under 8c, ORANGE 8-14c inclusive, RED above)
 * and their colors must stay in sync with WidgetUpdateUtil's PriceBand
 * and colors.xml -- see BAND_THRESHOLD_LOW/HIGH below.
 */
object PriceChartRenderer {

    enum class ChartStyle { LINE, BAR }

    /** Matches config/main.xml's chartStyle index in the KDE port: 0 = Line, 1 = Bar. */
    fun chartStyleFromIndex(index: Int): ChartStyle = if (index == 1) ChartStyle.BAR else ChartStyle.LINE
    fun indexFromChartStyle(style: ChartStyle): Int = if (style == ChartStyle.BAR) 1 else 0

    // Must match WidgetUpdateUtil's private PriceBand.bandFor thresholds.
    private const val BAND_THRESHOLD_LOW = 8.0
    private const val BAND_THRESHOLD_HIGH = 14.0

    // Small breathing-room margins only, now that no text is drawn into
    // the bitmap -- just enough that a data point or gridline right at an
    // extreme isn't clipped by the bitmap edge or the stroke's own width.
    private const val MARGIN_LEFT_PX = 6f
    private const val MARGIN_RIGHT_PX = 3f
    private const val MARGIN_TOP_PX = 0f
    private const val MARGIN_BOTTOM_PX = 0f
    private const val TICK_MARK_LENGTH_PX = 4f

    /** Number of Y-axis gridlines/labels -- fixed, matching the 4 fixed label slots in price_time_series_widget.xml. */
    private const val Y_AXIS_LABEL_COUNT = 4

    /**
     * The bitmap plus every axis label string the caller needs to
     * populate the surrounding TextViews with. yAxisLabelsTopToBottom
     * always has exactly Y_AXIS_LABEL_COUNT entries, top value first,
     * matching price_time_series_widget.xml's time_series_y_label_top/upper_mid/
     * lower_mid/bottom order. All strings are empty and bitmap is null
     * when there's nothing to draw (see render()'s early return).
     */
    data class ChartRenderResult(
        val bitmap: Bitmap?,
        val yAxisLabelsTopToBottom: List<String>,
        val xAxisLabelStart: String,
        val xAxisLabelMid: String,
        val xAxisLabelEnd: String
    )

    private val EMPTY_RESULT = ChartRenderResult(
        bitmap = null,
        yAxisLabelsTopToBottom = List(Y_AXIS_LABEL_COUNT) { "" },
        xAxisLabelStart = "",
        xAxisLabelMid = "",
        xAxisLabelEnd = ""
    )

    /**
     * Renders the chart at the given pixel size. points must be sorted
     * oldest-first and already filtered to the widget's configured
     * time series window (see WidgetUpdateUtil.filterTimeSeries) -- this
     * function draws exactly what it's given, with no filtering of its
     * own. Returns EMPTY_RESULT (null bitmap, blank labels) if there is
     * nothing to draw (empty points, or a non-positive size), so the
     * caller can safely push it straight into the widget's views either
     * way.
     */
    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        points: List<ComedApiClient.PricePoint>,
        chartStyle: ChartStyle,
        timeFormatter: (Long) -> String
    ): ChartRenderResult {
        if (points.isEmpty() || widthPx <= 0 || heightPx <= 0) return EMPTY_RESULT

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val density = context.resources.displayMetrics.density
        val marginLeftPx = MARGIN_LEFT_PX * density
        val marginRightPx = MARGIN_RIGHT_PX * density
        val marginTopPx = MARGIN_TOP_PX * density
        val marginBottomPx = MARGIN_BOTTOM_PX * density
        val tickMarkLengthPx = TICK_MARK_LENGTH_PX * density

        // Theme-adaptive gridline color, matching the KDE port's use of
        // the desktop theme's disabled-text color. The time series widget's
        // background is a plain light/dark card (see
        // widget_background_neutral.xml), not a saturated band color, so
        // this needs to track the system's light/dark state the same way
        // the surrounding RemoteViews text does -- resolved here instead,
        // since a Bitmap's Paint colors are plain ints and can't
        // reference theme attributes directly the way XML layout
        // attributes can.
        val gridColor = withAlpha(
            resolveThemeColor(context, android.R.attr.textColorSecondary, Color.GRAY), 160
        )

        val greenColor = ContextCompat.getColor(context, R.color.price_low_color)
        val orangeColor = ContextCompat.getColor(context, R.color.price_medium_color)
        val redColor = ContextCompat.getColor(context, R.color.price_high_color)

        fun colorForPrice(price: Double): Int = when {
            price < BAND_THRESHOLD_LOW -> greenColor
            price <= BAND_THRESHOLD_HIGH -> orangeColor
            else -> redColor
        }

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            strokeWidth = density // ~1dp
            style = Paint.Style.STROKE
        }
        val dataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 0.75f * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val isBar = chartStyle == ChartStyle.BAR

        // ---- Y axis (price, in cents): rounded to "nice" min/max bounds.
        // Bars get a forced zero baseline to grow from; the line chart
        // stays tight to the actual data range. ----
        var rawMinPrice = points.minOf { it.price }
        var rawMaxPrice = points.maxOf { it.price }
        if (isBar) {
            rawMinPrice = min(0.0, rawMinPrice)
            rawMaxPrice = max(0.0, rawMaxPrice)
        }
        val yAxis = niceAxis(rawMinPrice, rawMaxPrice, Y_AXIS_LABEL_COUNT)

        fun formatCents(value: Double, decimals: Int): String =
            String.format(java.util.Locale.US, "%.${decimals}f\u00A2", value)

        val plotLeft = marginLeftPx
        val plotRight = widthPx - marginRightPx
        val plotTop = marginTopPx
        val plotBottom = heightPx - marginBottomPx
        val plotWidth = max(1f, plotRight - plotLeft)
        val plotHeight = max(1f, plotBottom - plotTop)

        fun yFor(price: Double): Float {
            return (plotTop + plotHeight -
                    ((price - yAxis.min) / (yAxis.max - yAxis.min) * plotHeight)).toFloat()
        }

        // ---- X axis positioning: bars sit in evenly-sized index slots
        // (so they stay evenly spaced even if a reading is missing from
        // the feed); the line uses time-proportional position (so a gap
        // in readings shows as a longer segment rather than distorting
        // spacing). ----
        val n = points.size
        val minTime = points.first().millisUtc
        val maxTime = points.last().millisUtc
        val timeSpan = max(1L, maxTime - minTime)
        val slotWidth = plotWidth / n
        val barWidth = max(1f, slotWidth * 0.6f)

        fun xForBar(index: Int): Float = plotLeft + slotWidth * (index + 0.5f)
        fun xForTime(millis: Long): Float =
            plotLeft + ((millis - minTime).toFloat() / timeSpan.toFloat()) * plotWidth

        // ---- Y axis tick marks: Y_AXIS_LABEL_COUNT short dashes at the
        // axis, evenly spaced by PRICE between yAxis.min and yAxis.max.
        // Since yFor() is a linear mapping anchored exactly on
        // yAxis.min/max, points evenly spaced by price are also evenly
        // spaced by pixel row -- which is what lets the fixed
        // weight-based label column in price_time_series_widget.xml line up
        // with these without knowing any pixel coordinates itself.
        // yAxisValues is ordered top (max) first to match that column's
        // top-to-bottom label order. These are short dashes rather than
        // full-width gridlines -- a gridline at every label was never
        // part of the original design (only the zero line, below, spans
        // the full width), so adding them for every label wasn't
        // intentional here. ----
        val yAxisValues = List(Y_AXIS_LABEL_COUNT) { i ->
            yAxis.max - (i.toDouble() / (Y_AXIS_LABEL_COUNT - 1)) * (yAxis.max - yAxis.min)
        }
        for (value in yAxisValues) {
            val y = yFor(value)
            gridPaint.alpha = 128
            canvas.drawLine(plotLeft - tickMarkLengthPx, y, plotLeft, y, gridPaint)
            gridPaint.alpha = 255
        }
        val yAxisLabels = yAxisValues.map { formatCents(it, yAxis.decimals) }

        // ---- X axis: start / middle / end label values (returned as
        // strings, not drawn -- see time_series_x_label_start/mid/end). ----
        val xAxisLabelStart: String
        val xAxisLabelMid: String
        val xAxisLabelEnd: String
        if (isBar) {
            xAxisLabelStart = timeFormatter(points[0].millisUtc)
            xAxisLabelMid = timeFormatter(points[(n - 1) / 2].millisUtc)
            xAxisLabelEnd = timeFormatter(points[n - 1].millisUtc)
        } else {
            xAxisLabelStart = timeFormatter(minTime)
            xAxisLabelMid = timeFormatter(minTime + timeSpan / 2)
            xAxisLabelEnd = timeFormatter(maxTime)
        }

        // ---- Zero line, drawn whenever zero falls anywhere within the
        // visible price range, including right at the bottom edge, which
        // is the common case for the bar chart (its Y axis always
        // includes zero) when every price is non-negative. This is the
        // *only* full-width horizontal line the chart draws -- it must
        // never appear unless zero is actually within [yAxis.min,
        // yAxis.max], so it can't be confused with an ordinary axis
        // bound (the bottom tick mark above, at yAxis.min, is drawn
        // unconditionally regardless of its value, but only as a short
        // dash, never as a bar spanning the chart). ----
        if (yAxis.min <= 0 && yAxis.max >= 0) {
            val zeroY = yFor(0.0)
            gridPaint.alpha = 128
            canvas.drawLine(plotLeft, zeroY, plotRight, zeroY, gridPaint)
            gridPaint.alpha = 255
        }

        // ---- Axis line: left edge only. ----
        gridPaint.alpha = 128
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, gridPaint)
        gridPaint.alpha = 255

        // ---- The data itself ----
        if (isBar) {
            val baselineY = yFor(0.0)
            for (k in 0 until n) {
                val price = points[k].price
                val topY = yFor(price)
                val barTop = min(baselineY, topY)
                val barHeight = max(1f, abs(baselineY - topY))
                val barLeft = xForBar(k) - barWidth / 2f
                dataPaint.style = Paint.Style.FILL
                dataPaint.color = colorForPrice(price)
                canvas.drawRect(barLeft, barTop, barLeft + barWidth, barTop + barHeight, dataPaint)
            }
            dataPaint.style = Paint.Style.STROKE
        } else {
            if (n == 1) {
                // Nothing to connect -- draw a single dot so a lone point
                // is still visible.
                val soloX = xForTime(points[0].millisUtc)
                val soloY = yFor(points[0].price)
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = colorForPrice(points[0].price)
                }
                canvas.drawCircle(soloX, soloY, 2.2f * density, dotPaint)
            } else {
                for (seg in 0 until n - 1) {
                    drawSegment(
                        canvas, dataPaint, points[seg], points[seg + 1],
                        ::xForTime, ::yFor, ::colorForPrice
                    )
                }
            }
        }

        return ChartRenderResult(bitmap, yAxisLabels, xAxisLabelStart, xAxisLabelMid, xAxisLabelEnd)
    }

    /**
     * Draws one point-to-point line segment, split into sub-segments at
     * any point where the interpolated price crosses a band threshold,
     * each sub-segment stroked in the color for its own (constant, by
     * construction) band. Mirrors PriceGraph.qml's drawSegment exactly.
     */
    private fun drawSegment(
        canvas: Canvas,
        paint: Paint,
        pointA: ComedApiClient.PricePoint,
        pointB: ComedApiClient.PricePoint,
        xFor: (Long) -> Float,
        yFor: (Double) -> Float,
        colorForPrice: (Double) -> Int
    ) {
        val priceA = pointA.price
        val priceB = pointB.price

        // t=0 at pointA, t=1 at pointB; price and time are both linear in
        // t, so a threshold crossing is just where the linear
        // interpolation of price equals that threshold.
        val boundaries = mutableListOf(0.0, 1.0)
        for (threshold in listOf(BAND_THRESHOLD_LOW, BAND_THRESHOLD_HIGH)) {
            if (priceA == priceB) continue
            val crossT = (threshold - priceA) / (priceB - priceA)
            if (crossT > 0.0 && crossT < 1.0) {
                boundaries.add(crossT)
            }
        }
        boundaries.sort()

        for (s in 0 until boundaries.size - 1) {
            val tStart = boundaries[s]
            val tEnd = boundaries[s + 1]
            if (tEnd - tStart < 0.0001) continue

            val tMid = (tStart + tEnd) / 2.0
            val midPrice = priceA + tMid * (priceB - priceA)

            val startMillis = pointA.millisUtc + (tStart * (pointB.millisUtc - pointA.millisUtc)).toLong()
            val startPrice = priceA + tStart * (priceB - priceA)
            val endMillis = pointA.millisUtc + (tEnd * (pointB.millisUtc - pointA.millisUtc)).toLong()
            val endPrice = priceA + tEnd * (priceB - priceA)

            paint.color = colorForPrice(midPrice)
            canvas.drawLine(
                xFor(startMillis), yFor(startPrice),
                xFor(endMillis), yFor(endPrice),
                paint
            )
        }
    }

    private data class NiceAxis(val min: Double, val max: Double, val decimals: Int)

    /**
     * Classic "nice numbers for graph labels" approach (Heckbert): picks
     * a tick spacing that's 1, 2, or 5 times a power of ten, so labels
     * land on round values instead of arbitrary fractions. Ported
     * directly from PriceGraph.qml's niceNumber/niceAxis.
     */
    private fun niceNumber(range: Double, round: Boolean): Double {
        if (range <= 0) return 1.0
        val exponent = floor(ln(range) / ln(10.0))
        val fraction = range / 10.0.pow(exponent)
        val niceFraction = if (round) {
            when {
                fraction < 1.5 -> 1.0
                fraction < 3.0 -> 2.0
                fraction < 7.0 -> 5.0
                else -> 10.0
            }
        } else {
            when {
                fraction <= 1.0 -> 1.0
                fraction <= 2.0 -> 2.0
                fraction <= 5.0 -> 5.0
                else -> 10.0
            }
        }
        return niceFraction * 10.0.pow(exponent)
    }

    /**
     * Computes clean min/max axis bounds (e.g. 0/6 rather than the raw
     * 0.4/5.7) and how many decimal places their spacing needs. Unlike
     * the KDE original, this no longer generates an intermediate tick
     * list -- render() now always shows exactly Y_AXIS_LABEL_COUNT labels,
     * evenly spaced between min and max (see yAxisValues above), rather
     * than however many "nice" ticks happen to fall in range; in the
     * common case (range divides evenly into maxTicks-1 nice steps) the
     * two approaches produce identical values anyway.
     */
    private fun niceAxis(rawMinIn: Double, rawMaxIn: Double, maxTicks: Int): NiceAxis {
        var rawMin = rawMinIn
        var rawMax = rawMaxIn
        if (rawMin == rawMax) {
            rawMin -= 1
            rawMax += 1
        }
        val range = niceNumber(rawMax - rawMin, false)
        val spacing = niceNumber(range / (maxTicks - 1), true)
        val niceMin = floor(rawMin / spacing) * spacing
        val niceMax = ceil(rawMax / spacing) * spacing
        // Sub-cent spacing (e.g. 0.5, 0.2) needs a decimal place, or
        // adjacent ticks round to the same displayed label.
        val decimals = if (spacing < 1) 1 else 0
        return NiceAxis(niceMin, niceMax, decimals)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Resolves a framework text-color theme attribute (e.g.
     * android.R.attr.textColorSecondary) against the given context's
     * current theme into a plain color int usable by Paint. This tracks
     * system light/dark mode the same way the RemoteViews layout's own
     * ?android:attr/... references do, since both are resolved from the
     * same underlying theme/configuration. Falls back to the given color
     * if resolution fails for any reason (e.g. an unexpected theme setup),
     * rather than crashing a widget refresh over a gridline color.
     */
    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        val resolved = try {
            context.theme.resolveAttribute(attr, typedValue, true)
        } catch (e: Exception) {
            false
        }
        if (!resolved) return fallback
        return if (typedValue.resourceId != 0) {
            try {
                ContextCompat.getColor(context, typedValue.resourceId)
            } catch (e: Exception) {
                fallback
            }
        } else {
            typedValue.data
        }
    }
}
