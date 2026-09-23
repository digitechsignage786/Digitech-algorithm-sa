package com.digitech.algorithm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

data class Candle(
    val time: Long,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float
)

enum class DrawingTool {
    CROSSHAIR,
    TREND_LINE,
    RAY,
    HORIZONTAL_LINE,
    VERTICAL_LINE,
    RECTANGLE,
    CIRCLE,
    ARROW,
    PARALLEL_CHANNEL,
    FIBONACCI,
    BRUSH,
    TEXT,
    MEASURE
}

class CandlestickChartView(context: Context) : View(context) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val candles = mutableListOf<Candle>()

    private var zoom = 1f
    private var offsetX = 0f

    // Independent vertical price-axis controls. Horizontal zoom remains the
    // candle zoom; vertical zoom changes the visible price range without
    // changing the locked live-price/countdown placement logic.
    private var priceZoom = 1f
    private var pricePan = 0f
    private var lastPanY = 0f
    private var priceScaleDragging = false

    // Drawings are anchored to the chart's candle/time and price coordinate
    // system, not to raw screen pixels. This keeps every drawing attached to
    // the same candle/price when the chart is panned or vertically zoomed.
    private var drawingSyncInitialized = false
    private var drawingSyncOffsetX = 0f
    private var drawingSyncStep = 1f
    private var drawingSyncHigh = Float.NaN
    private var drawingSyncLow = Float.NaN
    private var drawingsTransformNeedsSave = false

    private val drawingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("digitech_chart_drawings_v2", Context.MODE_PRIVATE)
    }
    private var drawingsLoaded = false
    private var lastLoadedWidth = 0
    private var lastLoadedHeight = 0

    // Live-follow state: the chart advances smoothly through the current
    // candle interval, then snaps to the next candle when that interval starts.
    private var followLive = true
    private var lastCandleIntervalMillis = 5L * 60L * 1000L

    // Live chart clock. This advances every second so the open candle/time
    // scale behaves like a live trading chart even between price ticks.
    private var liveNowMillis: Long = System.currentTimeMillis()

    // Latest real market tick supplied by MainActivity. The chart never
    // generates a synthetic price; this value is only used to position the
    // live price line/label at the exact current price level.
    private var livePriceOverride = Float.NaN
    private var livePriceCountdownMillis = 0L
    private val liveClockRunnable = object : Runnable {
        override fun run() {
            liveNowMillis = System.currentTimeMillis()
            invalidate()
            postDelayed(this, 1000L)
        }
    }

    private var crossX = -1f
    private var crossY = -1f
    private var lastX = 0f
    private var dragging = false

    private var activeTool = DrawingTool.CROSSHAIR

    private var startX = -1f
    private var startY = -1f
    private var currentX = -1f
    private var currentY = -1f

    private data class LineData(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

    private data class RectData(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class MeasureData(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

    private val trendLines = mutableListOf<LineData>()
    private val rayLines = mutableListOf<LineData>()
    private val horizontalLines = mutableListOf<Float>()
    private val verticalLines = mutableListOf<Float>()
    private val rectangles = mutableListOf<RectData>()
    private val circles = mutableListOf<RectData>()
    private val arrows = mutableListOf<LineData>()
    private val parallelChannels = mutableListOf<LineData>()
    private val fibonacciLines = mutableListOf<LineData>()
    private val measurements = mutableListOf<MeasureData>()
    private val brushStrokes = mutableListOf<List<Pair<Float, Float>>>()
    private val textMarkers = mutableListOf<Pair<Float, Float>>()

    private var activeBrush = mutableListOf<Pair<Float, Float>>()

    private var scaleInProgress = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(
                detector: ScaleGestureDetector
            ): Boolean {

                scaleInProgress = true
                return true
            }

            override fun onScale(
                detector: ScaleGestureDetector
            ): Boolean {

                // Dampen the raw pinch factor so small finger movements do not
                // cause visible jumps in the chart.
                val rawFactor = detector.scaleFactor

                if (!rawFactor.isFinite()) {
                    return true
                }

                val delta = rawFactor - 1f

                // Ignore extremely tiny noise and apply only part of each
                // gesture update for a smoother zoom.
                if (kotlin.math.abs(delta) < 0.002f) {
                    return true
                }

                val smoothFactor =
                    1f + delta.coerceIn(-0.12f, 0.12f) * 0.45f

                val oldZoom = zoom
                val newZoom =
                    (oldZoom * smoothFactor)
                        .coerceIn(0.45f, 5f)

                val oldPriceZoom = priceZoom
                val newPriceZoom =
                    (oldPriceZoom * smoothFactor)
                        .coerceIn(0.55f, 6f)

                val focusX = detector.focusX
                val focusY = detector.focusY

                // Keep the candle under the fingers anchored while zooming.
                if (newZoom != oldZoom) {
                    val zoomRatio = newZoom / oldZoom
                    offsetX = focusX - (focusX - offsetX) * zoomRatio
                    zoom = newZoom
                }

                // Keep the price under the fingers anchored while vertical
                // price zoom changes. pricePan is expressed in price units.
                if (newPriceZoom != oldPriceZoom) {
                    drawingsTransformNeedsSave = true
                    val before = visibleRange()
                    val center = (before.first + before.second) / 2f
                    val oldHalf = (before.first - before.second) / 2f
                    val newHalf = oldHalf / (newPriceZoom / oldPriceZoom)
                    val focusFraction = ((focusY - chartTop()) /
                        (chartBottom() - chartTop()).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val focusPrice = before.first - focusFraction * (before.first - before.second)
                    val newCenter = focusPrice + (0.5f - focusFraction) * (newHalf * 2f)
                    pricePan = newCenter - center
                    priceZoom = newPriceZoom
                }

                invalidate()

                return true
            }

            override fun onScaleEnd(
                detector: ScaleGestureDetector
            ) {

                scaleInProgress = false
            }
        }
    )

    init {

        setBackgroundColor(Color.rgb(5, 9, 13))
        liveNowMillis = System.currentTimeMillis()
        loadDrawings()
        post(liveClockRunnable)

        gridPaint.color =
            Color.rgb(27, 37, 49)

        gridPaint.strokeWidth = 1f

        textPaint.color =
            Color.rgb(145, 158, 175)

        textPaint.textSize = 14f

        upPaint.color =
            Color.rgb(22, 190, 145)

        downPaint.color =
            Color.rgb(235, 65, 85)

        crosshairPaint.color =
            Color.rgb(110, 125, 145)

        crosshairPaint.strokeWidth = 1f

        crosshairPaint.pathEffect =
            DashPathEffect(
                floatArrayOf(7f, 7f),
                0f
            )

        drawingPaint.color =
            Color.rgb(55, 130, 235)

        drawingPaint.strokeWidth = 2.5f

        drawingPaint.style =
            Paint.Style.STROKE

        labelPaint.color =
            Color.WHITE

        labelPaint.textSize = 12f

        boxPaint.color =
            Color.rgb(25, 34, 46)

        boxPaint.style =
            Paint.Style.FILL
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        updateLiveFollowOffset()
        syncDrawingsToChart()
        drawGrid(canvas)

        if (candles.isEmpty()) {

            drawEmptyChart(canvas)

        } else {

            drawCandles(canvas)
            drawCurrentPrice(canvas)
            drawPriceScale(canvas)
            drawTimeScale(canvas)
        }

        drawStoredDrawings(canvas)
        drawActiveDrawing(canvas)

        if (
            activeTool == DrawingTool.CROSSHAIR &&
            crossX >= 0f &&
            crossY >= 0f
        ) {

            drawCrosshair(canvas)
            drawCrosshairLabels(canvas)
        }

        drawBranding(canvas)
    }

    private fun drawGrid(
        canvas: Canvas
    ) {

        val spacing =
            100f * zoom

        var x =
            offsetX % spacing

        while (x < width) {

            if (x >= 0f) {

                canvas.drawLine(
                    x,
                    0f,
                    x,
                    height.toFloat(),
                    gridPaint
                )
            }

            x += spacing
        }

        var y = 0f

        while (y < height) {

            canvas.drawLine(
                0f,
                y,
                width.toFloat(),
                y,
                gridPaint
            )

            y += 75f
        }
    }

    private fun drawEmptyChart(
        canvas: Canvas
    ) {

        textPaint.textAlign =
            Paint.Align.CENTER

        textPaint.textSize = 25f

        canvas.drawText(
            "LIVE MARKET CHART",
            width / 2f,
            height / 2f - 25f,
            textPaint
        )

        textPaint.textSize = 16f

        canvas.drawText(
            "Waiting for real OHLC market data...",
            width / 2f,
            height / 2f + 12f,
            textPaint
        )

        textPaint.textSize = 13f

        canvas.drawText(
            "No demo candles",
            width / 2f,
            height / 2f + 40f,
            textPaint
        )
    }

    private fun chartLeft(): Float {
        return 30f
    }

    private fun chartRight(): Float {
        return width - 70f
    }

    // Keep the plotted area almost edge-to-edge. Only a tiny inset is
    // reserved so price/time labels never get clipped.
    private fun chartTop(): Float {
        return 0f
    }

    private fun chartBottom(): Float {
        // Minimum practical bottom inset: keep the time labels visible while
        // giving the candles/grid virtually all remaining vertical space.
        return (height - 8f).coerceAtLeast(24f)
    }

    private fun candleWidth(): Float {
        return (14f * zoom).coerceIn(4f, 34f)
    }

    private fun candleGap(): Float {
        return (4f * zoom).coerceIn(1f, 10f)
    }

    private fun candleStep(): Float {
        return candleWidth() + candleGap()
    }

    private fun candleX(index: Int): Float {
        return chartLeft() +
            index * candleStep() +
            offsetX
    }

    private fun visibleRange(): Pair<Float, Float> {

        if (candles.isEmpty()) {
            return Pair(1f, 0f)
        }

        val left = chartLeft()
        val right = chartRight()
        val step = candleStep()
        val widthOfCandle = candleWidth()

        var highest =
            Float.NEGATIVE_INFINITY

        var lowest =
            Float.POSITIVE_INFINITY

        var found = false

        for (i in candles.indices) {

            val x =
                left +
                i * step +
                offsetX

            if (
                x + widthOfCandle < left ||
                x > right
            ) {
                continue
            }

            val candle =
                candles[i]

            highest =
                max(
                    highest,
                    candle.high
                )

            lowest =
                min(
                    lowest,
                    candle.low
                )

            found = true
        }

        if (!found) {

            for (candle in candles) {

                highest =
                    max(
                        highest,
                        candle.high
                    )

                lowest =
                    min(
                        lowest,
                        candle.low
                    )
            }
        }

        // Keep the REAL current tick inside the visible price scale so the
        // live price marker stays exactly on its actual price level.
        if (livePriceOverride.isFinite()) {
            highest = max(highest, livePriceOverride)
            lowest = min(lowest, livePriceOverride)
        }

        val rawRange =
            max(
                highest - lowest,
                0.000001f
            )

        val padding = rawRange * 0.08f
        val baseHigh = highest + padding
        val baseLow = lowest - padding
        val center = (baseHigh + baseLow) / 2f + pricePan
        val halfRange = ((baseHigh - baseLow) / 2f / priceZoom)
            .coerceAtLeast(0.0000005f)

        return Pair(
            center + halfRange,
            center - halfRange
        )
    }

    private fun priceToY(
        price: Float,
        highest: Float,
        lowest: Float
    ): Float {

        val range =
            max(
                highest - lowest,
                0.000001f
            )

        val top =
            chartTop()

        val bottom =
            chartBottom()

        val chartHeight =
            max(
                bottom - top,
                1f
            )

        return top +
            (highest - price) /
            range *
            chartHeight
    }

    private fun drawCandles(
        canvas: Canvas
    ) {

        if (candles.isEmpty()) {
            return
        }

        val widthOfCandle =
            candleWidth()

        val step =
            candleStep()

        val left =
            chartLeft()

        val right =
            chartRight()

        val range =
            visibleRange()

        val highest =
            range.first

        val lowest =
            range.second

        for (i in candles.indices) {

            val candle =
                candles[i]

            val x =
                left +
                i * step +
                offsetX

            if (
                x + widthOfCandle < left ||
                x > right
            ) {
                continue
            }

            val highY =
                priceToY(
                    candle.high,
                    highest,
                    lowest
                )

            val lowY =
                priceToY(
                    candle.low,
                    highest,
                    lowest
                )

            val openY =
                priceToY(
                    candle.open,
                    highest,
                    lowest
                )

            val closeY =
                priceToY(
                    candle.close,
                    highest,
                    lowest
                )

            val paint =
                if (
                    candle.close >= candle.open
                ) {
                    upPaint
                } else {
                    downPaint
                }

            val centerX =
                x + widthOfCandle / 2f

            canvas.drawLine(
                centerX,
                highY,
                centerX,
                lowY,
                paint
            )

            val bodyTop =
                min(
                    openY,
                    closeY
                )

            val bodyBottom =
                max(
                    openY,
                    closeY
                )

            val bodyHeight =
                max(
                    bodyBottom - bodyTop,
                    2f
                )

            canvas.drawRect(
                x,
                bodyTop,
                x + widthOfCandle,
                bodyTop + bodyHeight,
                paint
            )
        }
    }

    private fun drawCurrentPrice(canvas: Canvas) {

        val candle = candles.lastOrNull() ?: return

        val livePrice = if (livePriceOverride.isFinite()) {
            livePriceOverride
        } else {
            candle.close
        }

        val range = visibleRange()
        val highest = range.first
        val lowest = range.second
        val priceRange = max(highest - lowest, 0.000001f)

        // The live marker is anchored to the exact same Y coordinate used
        // by the price scale. It is NOT a floating header/overlay.
        val y = chartTop() +
            ((highest - livePrice) / priceRange) *
            (chartBottom() - chartTop())

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        linePaint.color = Color.rgb(75, 85, 98)
        linePaint.strokeWidth = 1f
        linePaint.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)

        // Current-price guide line ends exactly at the price-scale column.
        canvas.drawLine(chartLeft(), y, chartRight(), y, linePaint)

        val interval = lastCandleIntervalMillis.coerceAtLeast(1L)
        val nextBoundary = ((liveNowMillis / interval) + 1L) * interval
        val clockRemaining = (nextBoundary - liveNowMillis).coerceAtLeast(0L)
        val remaining = if (livePriceCountdownMillis > 0L) {
            min(livePriceCountdownMillis, clockRemaining)
        } else {
            clockRemaining
        }

        val totalSeconds = (remaining + 999L) / 1000L
        val countdownText = if (interval >= 60L * 60L * 1000L) {
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

        val priceText = String.format(Locale.US, "%.5f", livePrice)

        // IMPORTANT: this marker occupies the SAME right-hand column where
        // all normal price-scale values are printed. Therefore the live
        // price and candle countdown appear exactly beside the current
        // price level, not somewhere else on the chart.
        val axisLeft = chartRight() + 1f
        val axisRight = width - 1f
        val labelWidth = (axisRight - axisLeft).coerceAtLeast(60f)
        val labelHeight = 30f
        val labelTop = (y - labelHeight / 2f)
            .coerceIn(chartTop(), chartBottom() - labelHeight)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = Color.rgb(55, 65, 80)
        canvas.drawRect(
            axisLeft,
            labelTop,
            axisRight,
            labelTop + labelHeight,
            bg
        )

        val pricePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        pricePaint.color = Color.WHITE
        pricePaint.textSize = 9.5f
        pricePaint.textAlign = Paint.Align.CENTER

        canvas.drawText(
            priceText,
            axisLeft + labelWidth / 2f,
            labelTop + 11.5f,
            pricePaint
        )

        val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        countdownPaint.color = Color.WHITE
        countdownPaint.textSize = 8.5f
        countdownPaint.textAlign = Paint.Align.CENTER

        canvas.drawText(
            countdownText,
            axisLeft + labelWidth / 2f,
            labelTop + 24f,
            countdownPaint
        )
    }

    private fun drawPriceScale(
        canvas: Canvas
    ) {

        if (candles.isEmpty()) {
            return
        }

        val range =
            visibleRange()

        val highest =
            range.first

        val lowest =
            range.second

        val priceRange =
            max(
                highest - lowest,
                0.000001f
            )

        textPaint.textAlign =
            Paint.Align.RIGHT

        textPaint.textSize = 11f

        for (i in 0..6) {

            val fraction =
                i.toFloat() / 6f

            val price =
                highest -
                priceRange * fraction

            val y =
                chartTop() +
                fraction *
                (chartBottom() - chartTop())

            val label =
                String.format(
                    java.util.Locale.US,
                    "%.5f",
                    price
                )

            // The current live-price row is rendered by drawCurrentPrice()
            // in this exact price-scale column. Do not draw the normal tick
            // label underneath it.
            val live = livePriceOverride
            val liveY = if (live.isFinite()) {
                chartTop() +
                    ((highest - live) / priceRange) *
                    (chartBottom() - chartTop())
            } else {
                Float.NaN
            }

            if (!liveY.isFinite() || kotlin.math.abs(y - liveY) > 18f) {
                canvas.drawText(
                    label,
                    width - 7f,
                    y + 4f,
                    textPaint
                )
            }
        }
    }

    private fun updateLiveFollowOffset() {

        if (!followLive || candles.isEmpty()) {
            return
        }

        val lastIndex = candles.lastIndex
        val lastTime = candles[lastIndex].time

        val interval = if (candles.size >= 2) {
            (candles[lastIndex].time - candles[lastIndex - 1].time)
                .coerceAtLeast(1L)
        } else {
            lastCandleIntervalMillis
        }

        lastCandleIntervalMillis = interval

        // Fraction of the currently forming candle that has elapsed.
        // This is used only for visual time-axis movement; it never creates
        // or changes OHLC values.
        val elapsed = (liveNowMillis - lastTime).coerceAtLeast(0L)
        val fraction = ((elapsed % interval).toFloat() / interval.toFloat())
            .coerceIn(0f, 0.999f)

        val latestX =
            chartLeft() +
            lastIndex * candleStep() +
            candleWidth()

        // Keep a small live margin at the right and let the timeline drift
        // smoothly with the forming candle.
        val rightAnchor = chartRight() - 8f
        val baseOffset = rightAnchor - latestX
        offsetX = baseOffset - (fraction * candleStep())
    }

    private fun drawTimeScale(
        canvas: Canvas
    ) {

        if (candles.isEmpty()) {
            return
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 11f
        textPaint.color = Color.rgb(145, 158, 175)

        val widthOfCandle = candleWidth()
        val step = candleStep()
        val every = max(1, (90f / step).toInt())

        val sdf = java.text.SimpleDateFormat(
            "HH:mm",
            java.util.Locale.US
        )

        // Historical labels stay attached to their candles.
        for (i in candles.indices step every) {
            val x = candleX(i) + widthOfCandle / 2f
            if (x < chartLeft() || x > chartRight()) continue

            canvas.drawText(
                sdf.format(java.util.Date(candles[i].time)),
                x,
                height - 14f,
                textPaint
            )
        }

        // Live timeline: show the current time at the live edge and let it
        // move continuously with price/time progression.
        val liveX = chartRight() - 4f
        // The live edge is intentionally shown with seconds. This is the
        // part that visibly advances every second, while historical labels
        // remain compact at minute resolution.
        val liveSdf = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.US
        )
        val liveText = liveSdf.format(java.util.Date(liveNowMillis))

        textPaint.color = Color.rgb(205, 215, 225)
        textPaint.textSize = 10f
        textPaint.textAlign = Paint.Align.RIGHT

        // Small live-time badge at the right edge, similar to a trading
        // chart's moving time marker. It updates every second without
        // changing OHLC data.
        val timeLabelWidth = 58f
        val timeLabelHeight = 17f
        val timeLabelLeft = (chartRight() - timeLabelWidth).coerceAtLeast(chartLeft())
        val timeLabelTop = (height - 20f).coerceAtLeast(chartTop())

        val timeBg = Paint(Paint.ANTI_ALIAS_FLAG)
        timeBg.color = Color.rgb(25, 34, 46)
        canvas.drawRect(
            timeLabelLeft,
            timeLabelTop,
            chartRight(),
            timeLabelTop + timeLabelHeight,
            timeBg
        )

        canvas.drawText(
            liveText,
            chartRight() - 3f,
            timeLabelTop + 12f,
            textPaint
        )

        textPaint.color = Color.rgb(145, 158, 175)
        textPaint.textSize = 11f
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawStoredDrawings(
        canvas: Canvas
    ) {
        drawingPaint.style = Paint.Style.STROKE
        drawingPaint.strokeWidth = 2.5f

        drawingPaint.color = Color.rgb(55, 130, 235)
        for (line in trendLines) {
            canvas.drawLine(line.x1, line.y1, line.x2, line.y2, drawingPaint)
        }

        // Rays extend from the first point through the second point to the
        // right edge of the chart.
        drawingPaint.color = Color.rgb(120, 165, 255)
        for (line in rayLines) {
            drawRay(canvas, line, drawingPaint)
        }

        drawingPaint.color = Color.rgb(235, 190, 55)
        for (y in horizontalLines) {
            canvas.drawLine(0f, y, width.toFloat(), y, drawingPaint)
        }

        drawingPaint.color = Color.rgb(235, 160, 80)
        for (x in verticalLines) {
            canvas.drawLine(x, 0f, x, height.toFloat(), drawingPaint)
        }

        drawingPaint.color = Color.rgb(55, 180, 235)
        for (rect in rectangles) {
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, drawingPaint)
        }

        drawingPaint.color = Color.rgb(120, 210, 180)
        for (circle in circles) {
            canvas.drawOval(circle.left, circle.top, circle.right, circle.bottom, drawingPaint)
        }

        drawingPaint.color = Color.rgb(255, 150, 70)
        for (line in arrows) {
            drawArrow(canvas, line, drawingPaint)
        }

        drawingPaint.color = Color.rgb(180, 120, 240)
        for (line in parallelChannels) {
            canvas.drawLine(line.x1, line.y1, line.x2, line.y2, drawingPaint)
            val dx = line.x2 - line.x1
            val dy = line.y2 - line.y1
            val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val nx = -dy / length * 24f
            val ny = dx / length * 24f
            canvas.drawLine(
                line.x1 + nx, line.y1 + ny,
                line.x2 + nx, line.y2 + ny,
                drawingPaint
            )
        }

        drawingPaint.color = Color.rgb(80, 210, 255)
        for (line in fibonacciLines) {
            drawFibonacci(canvas, line, drawingPaint)
        }

        drawingPaint.color = Color.rgb(235, 235, 235)
        for (stroke in brushStrokes) {
            for (i in 1 until stroke.size) {
                val a = stroke[i - 1]
                val b = stroke[i]
                canvas.drawLine(a.first, a.second, b.first, b.second, drawingPaint)
            }
        }

        drawingPaint.color = Color.rgb(190, 120, 235)
        for (measure in measurements) {
            canvas.drawLine(measure.x1, measure.y1, measure.x2, measure.y2, drawingPaint)
            drawMeasureLabel(canvas, measure.x1, measure.y1, measure.x2, measure.y2)
        }

        labelPaint.color = Color.WHITE
        labelPaint.textSize = 13f
        labelPaint.textAlign = Paint.Align.LEFT
        for (point in textMarkers) {
            canvas.drawText("TEXT", point.first, point.second, labelPaint)
        }
    }

    private fun drawRay(
        canvas: Canvas,
        line: LineData,
        paint: Paint
    ) {
        val dx = line.x2 - line.x1
        val dy = line.y2 - line.y1
        if (kotlin.math.abs(dx) < 0.001f) {
            canvas.drawLine(line.x1, line.y1, line.x1, height.toFloat(), paint)
            return
        }
        val endX = width.toFloat()
        val endY = line.y1 + (endX - line.x1) * dy / dx
        canvas.drawLine(line.x1, line.y1, endX, endY, paint)
    }

    private fun drawArrow(
        canvas: Canvas,
        line: LineData,
        paint: Paint
    ) {
        canvas.drawLine(line.x1, line.y1, line.x2, line.y2, paint)
        val angle = kotlin.math.atan2(
            (line.y2 - line.y1).toDouble(),
            (line.x2 - line.x1).toDouble()
        )
        val size = 14f
        val a1 = angle + Math.PI * 0.82
        val a2 = angle - Math.PI * 0.82
        val p1x = line.x2 + size * kotlin.math.cos(a1).toFloat()
        val p1y = line.y2 + size * kotlin.math.sin(a1).toFloat()
        val p2x = line.x2 + size * kotlin.math.cos(a2).toFloat()
        val p2y = line.y2 + size * kotlin.math.sin(a2).toFloat()
        canvas.drawLine(line.x2, line.y2, p1x, p1y, paint)
        canvas.drawLine(line.x2, line.y2, p2x, p2y, paint)
    }

    private fun drawFibonacci(
        canvas: Canvas,
        line: LineData,
        paint: Paint
    ) {
        val top = min(line.y1, line.y2)
        val bottom = max(line.y1, line.y2)
        val levels = floatArrayOf(0f, 0.236f, 0.382f, 0.5f, 0.618f, 0.786f, 1f)
        for (level in levels) {
            val y = top + (bottom - top) * level
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            labelPaint.color = Color.rgb(120, 210, 255)
            labelPaint.textSize = 10f
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                String.format(Locale.US, "%.3f", level),
                width - 4f,
                y - 2f,
                labelPaint
            )
        }
        canvas.drawLine(line.x1, line.y1, line.x2, line.y2, paint)
    }

    private fun drawMeasureLabel(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ) {
        val distance = kotlin.math.sqrt(
            (x2 - x1) * (x2 - x1) +
            (y2 - y1) * (y2 - y1)
        )
        labelPaint.color = Color.rgb(220, 180, 255)
        labelPaint.textSize = 11f
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            String.format(Locale.US, "%.0f px", distance),
            (x1 + x2) / 2f,
            (y1 + y2) / 2f - 6f,
            labelPaint
        )
    }


    private fun drawActiveDrawing(
        canvas: Canvas
    ) {
        if (startX < 0f || startY < 0f || currentX < 0f || currentY < 0f) {
            if (activeTool == DrawingTool.BRUSH && activeBrush.size > 1) {
                drawingPaint.color = Color.WHITE
                for (i in 1 until activeBrush.size) {
                    val a = activeBrush[i - 1]
                    val b = activeBrush[i]
                    canvas.drawLine(a.first, a.second, b.first, b.second, drawingPaint)
                }
            }
            return
        }

        drawingPaint.style = Paint.Style.STROKE

        when (activeTool) {
            DrawingTool.TREND_LINE -> {
                drawingPaint.color = Color.rgb(55, 130, 235)
                canvas.drawLine(startX, startY, currentX, currentY, drawingPaint)
            }

            DrawingTool.RAY -> {
                drawingPaint.color = Color.rgb(120, 165, 255)
                drawRay(canvas, LineData(startX, startY, currentX, currentY), drawingPaint)
            }

            DrawingTool.HORIZONTAL_LINE -> {
                drawingPaint.color = Color.rgb(235, 190, 55)
                canvas.drawLine(0f, currentY, width.toFloat(), currentY, drawingPaint)
            }

            DrawingTool.VERTICAL_LINE -> {
                drawingPaint.color = Color.rgb(235, 160, 80)
                canvas.drawLine(currentX, 0f, currentX, height.toFloat(), drawingPaint)
            }

            DrawingTool.RECTANGLE -> {
                drawingPaint.color = Color.rgb(55, 180, 235)
                canvas.drawRect(
                    min(startX, currentX),
                    min(startY, currentY),
                    max(startX, currentX),
                    max(startY, currentY),
                    drawingPaint
                )
            }

            DrawingTool.CIRCLE -> {
                drawingPaint.color = Color.rgb(120, 210, 180)
                canvas.drawOval(
                    min(startX, currentX),
                    min(startY, currentY),
                    max(startX, currentX),
                    max(startY, currentY),
                    drawingPaint
                )
            }

            DrawingTool.ARROW -> {
                drawingPaint.color = Color.rgb(255, 150, 70)
                drawArrow(
                    canvas,
                    LineData(startX, startY, currentX, currentY),
                    drawingPaint
                )
            }

            DrawingTool.PARALLEL_CHANNEL -> {
                drawingPaint.color = Color.rgb(180, 120, 240)
                val line = LineData(startX, startY, currentX, currentY)
                canvas.drawLine(line.x1, line.y1, line.x2, line.y2, drawingPaint)
                val dx = line.x2 - line.x1
                val dy = line.y2 - line.y1
                val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val nx = -dy / length * 24f
                val ny = dx / length * 24f
                canvas.drawLine(
                    line.x1 + nx, line.y1 + ny,
                    line.x2 + nx, line.y2 + ny,
                    drawingPaint
                )
            }

            DrawingTool.FIBONACCI -> {
                drawingPaint.color = Color.rgb(80, 210, 255)
                drawFibonacci(
                    canvas,
                    LineData(startX, startY, currentX, currentY),
                    drawingPaint
                )
            }

            DrawingTool.MEASURE -> {
                drawingPaint.color = Color.rgb(190, 120, 235)
                canvas.drawLine(startX, startY, currentX, currentY, drawingPaint)
                drawMeasureLabel(canvas, startX, startY, currentX, currentY)
            }

            DrawingTool.TEXT -> {
                labelPaint.color = Color.WHITE
                labelPaint.textSize = 13f
                labelPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("TEXT", currentX, currentY, labelPaint)
            }

            DrawingTool.BRUSH -> {
                drawingPaint.color = Color.WHITE
                if (activeBrush.size > 1) {
                    for (i in 1 until activeBrush.size) {
                        val a = activeBrush[i - 1]
                        val b = activeBrush[i]
                        canvas.drawLine(a.first, a.second, b.first, b.second, drawingPaint)
                    }
                }
            }

            DrawingTool.CROSSHAIR -> {
                // Crosshair handled separately.
            }
        }
    }


    private fun drawCrosshair(
        canvas: Canvas
    ) {

        canvas.drawLine(
            crossX,
            0f,
            crossX,
            height.toFloat(),
            crosshairPaint
        )

        canvas.drawLine(
            0f,
            crossY,
            width.toFloat(),
            crossY,
            crosshairPaint
        )
    }

    private fun drawCrosshairLabels(
        canvas: Canvas
    ) {

        val safeY =
            crossY.coerceIn(
                14f,
                height - 14f
            )

        val safeX =
            crossX.coerceIn(
                32f,
                width - 32f
            )

        canvas.drawRect(
            width - 78f,
            safeY - 14f,
            width.toFloat(),
            safeY + 14f,
            boxPaint
        )

        labelPaint.textAlign =
            Paint.Align.CENTER

        canvas.drawText(
            "Price",
            width - 39f,
            safeY + 4f,
            labelPaint
        )

        canvas.drawRect(
            safeX - 32f,
            height - 31f,
            safeX + 32f,
            height.toFloat(),
            boxPaint
        )

        canvas.drawText(
            "Time",
            safeX,
            height - 11f,
            labelPaint
        )
    }

    private fun drawBranding(
        canvas: Canvas
    ) {

        textPaint.textAlign =
            Paint.Align.LEFT

        textPaint.textSize = 13f

        textPaint.color =
            Color.rgb(80, 96, 112)

        canvas.drawText(
            "DA",
            12f,
            height - 42f,
            textPaint
        )
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                // Dragging directly on the right price-scale area moves the
                // entire candle field vertically. This is independent from
                // the drawing tool, so tools remain usable while the chart is
                // being re-centered.
                priceScaleDragging = event.x >= chartRight()
                if (priceScaleDragging) {
                    followLive = false
                    lastPanY = event.y
                    invalidate()
                    return true
                }

                if (activeTool != DrawingTool.CROSSHAIR) {

                    startX = event.x
                    startY = event.y
                    currentX = event.x
                    currentY = event.y

                    if (activeTool == DrawingTool.BRUSH) {
                        activeBrush = mutableListOf(event.x to event.y)
                    }

                } else {

                    crossX =
                        event.x

                    crossY =
                        event.y

                    lastX =
                        event.x

                    dragging =
                        true
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (scaleDetector.isInProgress || scaleInProgress) {
                    invalidate()
                    return true
                }

                if (priceScaleDragging) {
                    val dy = event.y - lastPanY
                    val range = visibleRange()
                    val pricePerPixel = (range.first - range.second) /
                        (chartBottom() - chartTop()).coerceAtLeast(1f)
                    pricePan -= dy * pricePerPixel
                    drawingsTransformNeedsSave = true
                    lastPanY = event.y
                    invalidate()
                    return true
                }

                if (activeTool != DrawingTool.CROSSHAIR) {

                    currentX = event.x
                    currentY = event.y

                    if (activeTool == DrawingTool.BRUSH) {
                        activeBrush.add(event.x to event.y)
                    }

                } else {

                    if (
                        event.pointerCount == 1 &&
                        dragging &&
                        !scaleDetector.isInProgress
                    ) {

                        followLive = false

                        val dx = event.x - lastX
                        offsetX += dx
                        drawingsTransformNeedsSave = true

                        lastX =
                            event.x
                    }

                    crossX =
                        event.x

                    crossY =
                        event.y
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP -> {

                if (priceScaleDragging) {
                    priceScaleDragging = false
                    invalidate()
                    return true
                }

                if (scaleInProgress) {
                    scaleInProgress = false
                    dragging = false
                    startX = -1f
                    startY = -1f
                    currentX = -1f
                    currentY = -1f
                    activeBrush.clear()
                    invalidate()
                    return true
                }

                if (activeTool != DrawingTool.CROSSHAIR &&
                    startX >= 0f && startY >= 0f
                ) {
                    val endX = event.x
                    val endY = event.y

                    when (activeTool) {
                        DrawingTool.TREND_LINE ->
                            trendLines.add(LineData(startX, startY, endX, endY))

                        DrawingTool.RAY ->
                            rayLines.add(LineData(startX, startY, endX, endY))

                        DrawingTool.HORIZONTAL_LINE ->
                            horizontalLines.add(endY)

                        DrawingTool.VERTICAL_LINE ->
                            verticalLines.add(endX)

                        DrawingTool.RECTANGLE ->
                            rectangles.add(
                                RectData(
                                    min(startX, endX),
                                    min(startY, endY),
                                    max(startX, endX),
                                    max(startY, endY)
                                )
                            )

                        DrawingTool.CIRCLE ->
                            circles.add(
                                RectData(
                                    min(startX, endX),
                                    min(startY, endY),
                                    max(startX, endX),
                                    max(startY, endY)
                                )
                            )

                        DrawingTool.ARROW ->
                            arrows.add(LineData(startX, startY, endX, endY))

                        DrawingTool.PARALLEL_CHANNEL ->
                            parallelChannels.add(LineData(startX, startY, endX, endY))

                        DrawingTool.FIBONACCI ->
                            fibonacciLines.add(LineData(startX, startY, endX, endY))

                        DrawingTool.MEASURE ->
                            measurements.add(MeasureData(startX, startY, endX, endY))

                        DrawingTool.BRUSH -> {
                            if (activeBrush.size > 1) {
                                brushStrokes.add(activeBrush.toList())
                            }
                            activeBrush.clear()
                        }

                        DrawingTool.TEXT ->
                            textMarkers.add(endX to endY)

                        DrawingTool.CROSSHAIR -> Unit
                    }

                    startX = -1f
                    startY = -1f
                    currentX = -1f
                    currentY = -1f
                    resetDrawingSyncBaseline()
                    saveDrawings()
                } else {
                    dragging = false
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                dragging = false
                priceScaleDragging = false

                startX = -1f
                startY = -1f
                currentX = -1f
                currentY = -1f
                activeBrush.clear()

                invalidate()

                return true
            }
        }

        return true
    }

    private fun syncDrawingsToChart() {
        if (candles.isEmpty()) return

        val range = visibleRange()
        val newHigh = range.first
        val newLow = range.second
        val newStep = candleStep()
        val newOffset = offsetX

        if (!drawingSyncInitialized ||
            !drawingSyncHigh.isFinite() ||
            !drawingSyncLow.isFinite() ||
            drawingSyncStep <= 0f
        ) {
            drawingSyncOffsetX = newOffset
            drawingSyncStep = newStep
            drawingSyncHigh = newHigh
            drawingSyncLow = newLow
            drawingSyncInitialized = true
            return
        }

        val oldOffset = drawingSyncOffsetX
        val oldStep = drawingSyncStep
        val oldHigh = drawingSyncHigh
        val oldLow = drawingSyncLow

        val oldHeight = (chartBottom() - chartTop()).coerceAtLeast(1f)
        val newHeight = oldHeight

        fun mapX(x: Float): Float {
            val logicalX = (x - chartLeft() - oldOffset) / oldStep
            return chartLeft() + logicalX * newStep + newOffset
        }

        fun mapY(y: Float): Float {
            val oldRange = (oldHigh - oldLow).coerceAtLeast(0.000001f)
            val fraction = ((y - chartTop()) / oldHeight).coerceIn(-10f, 10f)
            val price = oldHigh - fraction * oldRange
            return chartTop() + ((newHigh - price) /
                (newHigh - newLow).coerceAtLeast(0.000001f)) * newHeight
        }

        fun mapLine(l: LineData) = LineData(
            mapX(l.x1), mapY(l.y1), mapX(l.x2), mapY(l.y2)
        )
        fun mapRect(r: RectData) = RectData(
            mapX(r.left), mapY(r.top), mapX(r.right), mapY(r.bottom)
        )
        fun mapMeasure(m: MeasureData) = MeasureData(
            mapX(m.x1), mapY(m.y1), mapX(m.x2), mapY(m.y2)
        )

        for (i in trendLines.indices) trendLines[i] = mapLine(trendLines[i])
        for (i in rayLines.indices) rayLines[i] = mapLine(rayLines[i])
        for (i in horizontalLines.indices) horizontalLines[i] = mapY(horizontalLines[i])
        for (i in verticalLines.indices) verticalLines[i] = mapX(verticalLines[i])
        for (i in rectangles.indices) rectangles[i] = mapRect(rectangles[i])
        for (i in circles.indices) circles[i] = mapRect(circles[i])
        for (i in arrows.indices) arrows[i] = mapLine(arrows[i])
        for (i in parallelChannels.indices) parallelChannels[i] = mapLine(parallelChannels[i])
        for (i in fibonacciLines.indices) fibonacciLines[i] = mapLine(fibonacciLines[i])
        for (i in measurements.indices) measurements[i] = mapMeasure(measurements[i])
        for (i in brushStrokes.indices) {
            brushStrokes[i] = brushStrokes[i].map { (x, y) -> mapX(x) to mapY(y) }
        }
        for (i in textMarkers.indices) {
            val point = textMarkers[i]
            textMarkers[i] = mapX(point.first) to mapY(point.second)
        }

        drawingSyncOffsetX = newOffset
        drawingSyncStep = newStep
        drawingSyncHigh = newHigh
        drawingSyncLow = newLow
        if (drawingsTransformNeedsSave) {
            saveDrawings()
            drawingsTransformNeedsSave = false
        }
    }

    private fun resetDrawingSyncBaseline() {
        if (candles.isEmpty()) {
            drawingSyncInitialized = false
            return
        }
        val range = visibleRange()
        drawingSyncOffsetX = offsetX
        drawingSyncStep = candleStep()
        drawingSyncHigh = range.first
        drawingSyncLow = range.second
        drawingSyncInitialized = true
    }

    private fun saveDrawings() {
        try {
            val root = JSONObject()
            root.put("width", width)
            root.put("height", height)
            fun lineArray(list: List<LineData>): JSONArray {
                val a = JSONArray()
                list.forEach { l -> a.put(JSONArray().put(l.x1).put(l.y1).put(l.x2).put(l.y2)) }
                return a
            }
            fun rectArray(list: List<RectData>): JSONArray {
                val a = JSONArray()
                list.forEach { r -> a.put(JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom)) }
                return a
            }
            fun measureArray(list: List<MeasureData>): JSONArray {
                val a = JSONArray()
                list.forEach { m -> a.put(JSONArray().put(m.x1).put(m.y1).put(m.x2).put(m.y2)) }
                return a
            }
            root.put("trend", lineArray(trendLines))
            root.put("ray", lineArray(rayLines))
            root.put("h", JSONArray().apply { horizontalLines.forEach { put(it) } })
            root.put("v", JSONArray().apply { verticalLines.forEach { put(it) } })
            root.put("rect", rectArray(rectangles))
            root.put("circle", rectArray(circles))
            root.put("arrow", lineArray(arrows))
            root.put("channel", lineArray(parallelChannels))
            root.put("fib", lineArray(fibonacciLines))
            root.put("measure", measureArray(measurements))
            root.put("text", JSONArray().apply { textMarkers.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            val brushes = JSONArray()
            brushStrokes.forEach { stroke ->
                brushes.put(JSONArray().apply { stroke.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            }
            root.put("brush", brushes)
            drawingPrefs.edit().putString("state", root.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun loadDrawings() {
        if (drawingsLoaded || width <= 0 || height <= 0) return
        drawingsLoaded = true
        lastLoadedWidth = width
        lastLoadedHeight = height
        try {
            val raw = drawingPrefs.getString("state", null) ?: return
            val root = JSONObject(raw)
            val oldW = root.optDouble("width", 0.0).toFloat()
            val oldH = root.optDouble("height", 0.0).toFloat()
            if (oldW <= 0f || oldH <= 0f) return
            val sx = if (width > 0) width / oldW else 1f
            val sy = if (height > 0) height / oldH else 1f
            fun readLines(key: String, target: MutableList<LineData>) {
                val a = root.optJSONArray(key) ?: return
                for (i in 0 until a.length()) {
                    val v = a.optJSONArray(i) ?: continue
                    if (v.length() >= 4) target.add(LineData(v.optDouble(0).toFloat()*sx, v.optDouble(1).toFloat()*sy, v.optDouble(2).toFloat()*sx, v.optDouble(3).toFloat()*sy))
                }
            }
            fun readRects(key: String, target: MutableList<RectData>) {
                val a = root.optJSONArray(key) ?: return
                for (i in 0 until a.length()) {
                    val v = a.optJSONArray(i) ?: continue
                    if (v.length() >= 4) target.add(RectData(v.optDouble(0).toFloat()*sx, v.optDouble(1).toFloat()*sy, v.optDouble(2).toFloat()*sx, v.optDouble(3).toFloat()*sy))
                }
            }
            fun readMeasures(key: String, target: MutableList<MeasureData>) {
                val a = root.optJSONArray(key) ?: return
                for (i in 0 until a.length()) {
                    val v = a.optJSONArray(i) ?: continue
                    if (v.length() >= 4) target.add(MeasureData(v.optDouble(0).toFloat()*sx, v.optDouble(1).toFloat()*sy, v.optDouble(2).toFloat()*sx, v.optDouble(3).toFloat()*sy))
                }
            }
            readLines("trend", trendLines); readLines("ray", rayLines); readLines("arrow", arrows)
            readLines("channel", parallelChannels); readLines("fib", fibonacciLines); readMeasures("measure", measurements)
            readRects("rect", rectangles); readRects("circle", circles)
            root.optJSONArray("h")?.let { a -> for (i in 0 until a.length()) horizontalLines.add(a.optDouble(i).toFloat()*sy) }
            root.optJSONArray("v")?.let { a -> for (i in 0 until a.length()) verticalLines.add(a.optDouble(i).toFloat()*sx) }
            root.optJSONArray("text")?.let { a -> for (i in 0 until a.length()) { val v=a.optJSONArray(i); if(v!=null && v.length()>=2) textMarkers.add(v.optDouble(0).toFloat()*sx to v.optDouble(1).toFloat()*sy) } }
            root.optJSONArray("brush")?.let { a -> for (i in 0 until a.length()) { val st=a.optJSONArray(i) ?: continue; val out=mutableListOf<Pair<Float,Float>>(); for(j in 0 until st.length()){val v=st.optJSONArray(j); if(v!=null&&v.length()>=2) out.add(v.optDouble(0).toFloat()*sx to v.optDouble(1).toFloat()*sy)}; if(out.size>1) brushStrokes.add(out) } }
        } catch (_: Exception) { }
    }

    fun setDrawingTool(
        tool: DrawingTool
    ) {

        activeTool = tool

        startX = -1f
        startY = -1f
        currentX = -1f
        currentY = -1f

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!drawingsLoaded && w > 0 && h > 0) {
            loadDrawings()
            drawingSyncInitialized = false
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(liveClockRunnable)
        super.onDetachedFromWindow()
    }

    fun clearDrawings() {

        trendLines.clear()
        rayLines.clear()
        horizontalLines.clear()
        verticalLines.clear()
        rectangles.clear()
        circles.clear()
        arrows.clear()
        parallelChannels.clear()
        fibonacciLines.clear()
        measurements.clear()
        brushStrokes.clear()
        textMarkers.clear()
        activeBrush.clear()
        saveDrawings()

        invalidate()
    }

    fun setCandles(
        newCandles: List<Candle>
    ) {

        candles.clear()
        candles.addAll(newCandles)

        if (candles.isEmpty()) {
            offsetX = 0f
        } else if (followLive) {
            // The exact live-follow position is calculated every frame from
            // liveNowMillis, so incoming ticks do not reset the timeline.
            updateLiveFollowOffset()
        }

        if (!drawingSyncInitialized) {
            resetDrawingSyncBaseline()
        }

        invalidate()
    }

    /** Receives the real wall-clock time and selected timeframe interval. */
    fun setLiveTime(
        nowMillis: Long,
        intervalMillis: Long
    ) {
        liveNowMillis = nowMillis
        lastCandleIntervalMillis = intervalMillis.coerceAtLeast(1L)
        invalidate()
    }

    /**
     * Receives the latest REAL market tick and the remaining time in the
     * currently forming candle. The price is not generated here.
     */
    fun setLivePriceCountdown(
        price: Float,
        remainingMillis: Long
    ) {
        if (price.isFinite()) {
            livePriceOverride = price
        }
        livePriceCountdownMillis = remainingMillis.coerceAtLeast(0L)
        invalidate()
    }

    fun resumeLiveFollow() {
        followLive = true
        updateLiveFollowOffset()
        invalidate()
    }

    fun clearCandles() {

        candles.clear()

        invalidate()
    }
}
