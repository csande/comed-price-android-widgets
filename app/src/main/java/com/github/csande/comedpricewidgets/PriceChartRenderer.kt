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
 *    everywhere along the line, not just at the plotted points. A
 *    segment spanning a Missing slot (see TimeSeriesSlot below) is
 *    skipped entirely rather than connected through, leaving a visible
 *    break where the feed actually has no data.
 *  - BAR: each Present slot is its own bar, colored by its own price,
 *    drawn from a zero baseline (ComEd prices occasionally go
 *    negative); a Missing slot still reserves its place in the bar
 *    sequence (so the gap doesn't just compress away) but draws a small
 *    "no data" marker instead of a bar -- see drawMissingMarker.
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

    /**
     * One position along the time series chart's X axis: either a real
     * feed reading (Present) or an expected-but-absent one (Missing),
     * built by WidgetUpdateUtil.buildTimeSeriesSlots from the raw feed
     * points before this renders anything. Keeping both cases in a
     * single ordered list (rather than, say, just a list of the present
     * points plus a separate list of gap ranges) is what lets the BAR
     * style give each slot -- present or missing -- its own reserved
     * position via xForBar(index), and what lets the LINE style decide,
     * segment by segment, whether to draw a connecting line or leave a
     * break.
     */
    sealed class TimeSeriesSlot {
        abstract val millisUtc: Long
        data class Present(override val millisUtc: Long, val price: Double) : TimeSeriesSlot()
        data class Missing(override val millisUtc: Long) : TimeSeriesSlot()
    }

    // Must match WidgetUpdateUtil's private PriceBand.bandFor thresholds.
    private const val BAND_THRESHOLD_LOW = 8.0
    private const val BAND_THRESHOLD_HIGH = 14.0

    // Small breathing-room margins only, now that no text is drawn into
    // the bitmap -- just enough that a data point or gridline right at an
    // extreme isn't clipped by the bitmap edge or the stroke's own width.
    // MARGIN_TOP_PX exists specifically for the Y-axis gridlines below:
    // the top gridline sits exactly at plotTop, so with zero margin its
    // stroke would have half its width drawn past the bitmap's own edge
    // and clipped off. The margin only needs to clear half the stroke
    // width there, with a small buffer for anti-aliasing feathering at
    // the line's edge.
    //
    // MARGIN_BOTTOM_PX has the same job for the bottom gridline, but is
    // sized by a taller requirement: drawMissingMarker below centers its
    // glyph vertically *on* plotBottom (so it visibly crosses that line
    // -- see its own doc comment), which means roughly half the glyph's
    // line height extends below plotBottom into this margin.
    private const val MARGIN_LEFT_PX = 6f
    private const val MARGIN_RIGHT_PX = 3f
    private const val MARGIN_TOP_PX = 2f
    private const val MARGIN_BOTTOM_PX = 5f
    private const val TICK_MARK_LENGTH_PX = 4f

    /**
     * The character(s) drawn in place of a bar for a Missing slot: two
     * backslashes rather than a single italicized "N" or a lone
     * backslash -- doesn't require checking that a device's font renders
     * italics distinctly, doesn't ask the reader to parse it as a letter/
     * abbreviation ("N" for "no data") versus a symbol, and reads more
     * clearly as a deliberate "gap" mark than a single stroke does.
     */
    private const val MISSING_MARKER_GLYPH = "\\\\"
    private const val MISSING_MARKER_TEXT_SIZE_DP = 7f

    /** Number of Y-axis gridlines/labels -- fixed, matching the 4 fixed label slots in price_time_series_widget.xml. */
    private const val Y_AXIS_LABEL_COUNT = 4

    /**
     * Fixed Y-axis upper bound used only when there's no real price data
     * to derive one from (presentSlots is empty -- see render()). There's
     * no "nice" min/max to compute here the way niceAxis does for real
     * data, so this is hardcoded directly: with a 0 minimum and
     * Y_AXIS_LABEL_COUNT (4) evenly-spaced labels, a max of 15 puts
     * labels at exactly 0/5/10/15 cents -- round numbers, including the
     * 0/5/10 marks a person would expect on an otherwise-blank price
     * axis, with 15 filling out the 4th slot the layout's fixed label
     * column requires.
     */
    private const val NO_DATA_AXIS_MAX_CENTS = 15.0

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
     * Renders the chart at the given pixel size. slots must be sorted
     * oldest-first and already built for the widget's configured time
     * series window (see WidgetUpdateUtil.buildTimeSeriesSlots) -- this
     * function draws exactly what it's given, with no filtering or gap-
     * detection of its own; it only decides how to draw a Missing slot
     * once it's told one is there. Returns EMPTY_RESULT (null bitmap,
     * blank labels) only if there is truly nothing to draw at all (an
     * empty slots list, or a non-positive size) -- an empty *chart*
     * (real axes/gridlines/labels but no data on it) is instead drawn
     * when slots is non-empty but entirely Missing, e.g. when
     * WidgetUpdateUtil.buildTimeSeriesSlots is given no price data to
     * work with at all; see the yAxis/presentSlots handling below and
     * NO_DATA_AXIS_MAX_CENTS.
     */
    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        slots: List<TimeSeriesSlot>,
        chartStyle: ChartStyle,
        timeFormatter: (Long) -> String
    ): ChartRenderResult {
        if (slots.isEmpty() || widthPx <= 0 || heightPx <= 0) return EMPTY_RESULT
        val presentSlots = slots.filterIsInstance<TimeSeriesSlot.Present>()

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

        // Short tick marks at the axis and the left axis line itself --
        // not the zero line or the full-width horizontal gridlines, each
        // of which has its own paint below so all three can be tuned
        // independently.
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            strokeWidth = density // ~1dp
            style = Paint.Style.STROKE
        }
        // Full-width faint solid lines at each Y-axis tick, so the eye
        // can trace a label straight across to where it crosses the
        // data. Thinner and fainter than gridPaint's tick marks (~1dp at
        // alpha 128/255) and than zeroLinePaint below, so these read as
        // background texture the data line sits on top of rather than
        // competing with it.
        val horizontalGridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            alpha = 45
            strokeWidth = 0.25f * density // ~0.25dp
            style = Paint.Style.STROKE
        }
        // The single dedicated zero baseline (see its drawing site
        // below for when it's shown) -- kept separate from gridPaint
        // rather than reusing it with a toggled alpha, so its width and
        // alpha can be tuned without also affecting the tick marks/axis
        // line.
        val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            alpha = 100
            strokeWidth = 0.5f * density // ~0.5dp
            style = Paint.Style.STROKE
        }
        // The plotted line/bar data itself -- see colorForPrice above for
        // how its color is chosen; strokeWidth here controls the line
        // chart's line thickness (bars use FILL style instead, set at
        // their own drawing site, so this width only matters for LINE).
        val dataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1f * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val isBar = chartStyle == ChartStyle.BAR

        // ---- Y axis (price, in cents): rounded to "nice" min/max bounds.
        // Bars get a forced zero baseline to grow from; the line chart
        // stays tight to the actual data range. When there's no real
        // price data at all (every slot is Missing -- the "no data
        // available" case), there's nothing to derive bounds from, so a
        // fixed axis is used instead -- see NO_DATA_AXIS_MAX_CENTS. ----
        val yAxis = if (presentSlots.isEmpty()) {
            NiceAxis(0.0, NO_DATA_AXIS_MAX_CENTS, decimals = 0)
        } else {
            var rawMinPrice = presentSlots.minOf { it.price }
            var rawMaxPrice = presentSlots.maxOf { it.price }
            if (isBar) {
                rawMinPrice = min(0.0, rawMinPrice)
                rawMaxPrice = max(0.0, rawMaxPrice)
            }
            niceAxis(rawMinPrice, rawMaxPrice, Y_AXIS_LABEL_COUNT)
        }

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

        // ---- X axis positioning: bars sit in evenly-sized index slots,
        // one per entry in `slots` including Missing ones (so a missing
        // reading reserves its own place and the surrounding bars don't
        // compress to fill the gap -- see drawMissingMarker for what
        // draws there instead); the line uses time-proportional position
        // (so a gap in readings shows as a longer segment rather than
        // distorting spacing) and simply isn't drawn across a Missing
        // slot -- see the LINE branch below. ----
        val n = slots.size
        val minTime = slots.first().millisUtc
        val maxTime = slots.last().millisUtc
        val timeSpan = max(1L, maxTime - minTime)
        val slotWidth = plotWidth / n
        val barWidth = max(1f, slotWidth * 0.6f)

        fun xForBar(index: Int): Float = plotLeft + slotWidth * (index + 0.5f)
        fun xForTime(millis: Long): Float =
            plotLeft + ((millis - minTime).toFloat() / timeSpan.toFloat()) * plotWidth

        // ---- Y axis tick marks: Y_AXIS_LABEL_COUNT short dashes at the
        // axis, evenly spaced by PRICE between yAxis.min and yAxis.max,
        // each paired with a full-width faint solid gridline at the same
        // y (horizontalGridLinePaint, above) so the eye can trace a label
        // straight across to where it crosses the data, not just see a
        // mark at the axis. Since yFor() is a linear mapping anchored
        // exactly on yAxis.min/max, points evenly spaced by price are
        // also evenly spaced by pixel row -- which is what lets the fixed
        // weight-based label column in price_time_series_widget.xml line up
        // with these without knowing any pixel coordinates itself.
        // yAxisValues is ordered top (max) first to match that column's
        // top-to-bottom label order.
        //
        // The full-width line is skipped for whichever tick lands exactly
        // on zero (common for the bottom row, e.g. the bar chart's forced
        // zero baseline): the dedicated zero line below (zeroLinePaint)
        // already draws a solid reference line at that same y, so drawing
        // both on top of each other would be redundant regardless of
        // whether the two paints' widths/alphas happen to match. ----
        val yAxisValues = List(Y_AXIS_LABEL_COUNT) { i ->
            yAxis.max - (i.toDouble() / (Y_AXIS_LABEL_COUNT - 1)) * (yAxis.max - yAxis.min)
        }
        for (value in yAxisValues) {
            val y = yFor(value)
            if (abs(value) > 0.0001) {
                canvas.drawLine(plotLeft, y, plotRight, y, horizontalGridLinePaint)
            }
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
            xAxisLabelStart = timeFormatter(slots[0].millisUtc)
            xAxisLabelMid = timeFormatter(slots[(n - 1) / 2].millisUtc)
            xAxisLabelEnd = timeFormatter(slots[n - 1].millisUtc)
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
            canvas.drawLine(plotLeft, zeroY, plotRight, zeroY, zeroLinePaint)
        }

        // ---- Axis line: left edge only. ----
        gridPaint.alpha = 128
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, gridPaint)
        gridPaint.alpha = 255

        // ---- The data itself ----
        if (isBar) {
            val baselineY = yFor(0.0)
            for (k in 0 until n) {
                when (val slot = slots[k]) {
                    is TimeSeriesSlot.Present -> {
                        val topY = yFor(slot.price)
                        val barTop = min(baselineY, topY)
                        val barHeight = max(1f, abs(baselineY - topY))
                        val barLeft = xForBar(k) - barWidth / 2f
                        dataPaint.style = Paint.Style.FILL
                        dataPaint.color = colorForPrice(slot.price)
                        canvas.drawRect(barLeft, barTop, barLeft + barWidth, barTop + barHeight, dataPaint)
                    }
                    is TimeSeriesSlot.Missing -> {
                        drawMissingMarker(canvas, xForBar(k), plotBottom, density, gridColor)
                    }
                }
            }
            dataPaint.style = Paint.Style.STROKE
        } else {
            if (n == 1) {
                // Nothing to connect -- draw a single dot so a lone slot
                // is still visible, but only if it actually has data;
                // a lone Missing slot (a one-slot window with nothing in
                // it) has nothing to draw.
                val soloSlot = slots[0]
                if (soloSlot is TimeSeriesSlot.Present) {
                    val soloX = xForTime(soloSlot.millisUtc)
                    val soloY = yFor(soloSlot.price)
                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = colorForPrice(soloSlot.price)
                    }
                    canvas.drawCircle(soloX, soloY, 2.2f * density, dotPaint)
                }
            } else {
                for (seg in 0 until n - 1) {
                    val slotA = slots[seg]
                    val slotB = slots[seg + 1]
                    // Only connect two slots that both actually have
                    // data -- if either side of this segment is Missing,
                    // skip drawing it entirely rather than connecting
                    // straight through the gap, leaving a visible break
                    // in the line where the feed actually has no data.
                    if (slotA is TimeSeriesSlot.Present && slotB is TimeSeriesSlot.Present) {
                        drawSegment(
                            canvas, dataPaint, slotA, slotB,
                            ::xForTime, ::yFor, ::colorForPrice
                        )
                    }
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
     * Only ever called with two Present slots -- render()'s LINE branch
     * skips the segment entirely rather than calling this when either
     * side is Missing.
     */
    private fun drawSegment(
        canvas: Canvas,
        paint: Paint,
        pointA: TimeSeriesSlot.Present,
        pointB: TimeSeriesSlot.Present,
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

    /**
     * Draws the "no data" marker for a Missing bar slot: two backslashes
     * (see MISSING_MARKER_GLYPH), centered horizontally on the slot and
     * centered *vertically on plotBottom* -- which is always exactly
     * where the bottom-most Y-axis tick sits (yFor(yAxis.min) ==
     * plotBottom by construction, regardless of what that value actually
     * is -- see yFor below), and in the common case where the bar
     * chart's Y axis bottoms out at zero, exactly where the solid zero
     * line is drawn too. Centering on that line rather than sitting
     * above it means the marker visibly crosses it instead of floating
     * as a separate mark near the bottom.
     */
    private fun drawMissingMarker(canvas: Canvas, centerX: Float, plotBottom: Float, density: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = MISSING_MARKER_TEXT_SIZE_DP * density
            textAlign = Paint.Align.CENTER
        }
        val fontMetrics = paint.fontMetrics
        val baselineY = plotBottom - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(MISSING_MARKER_GLYPH, centerX, baselineY, paint)
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
