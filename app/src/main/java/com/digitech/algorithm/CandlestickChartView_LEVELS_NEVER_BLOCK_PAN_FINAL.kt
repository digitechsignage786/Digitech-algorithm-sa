package com.digitech.algorithm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
    private var timeScaleDragging = false
    private var lastTimePanX = 0f

    private val drawingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("digitech_chart_drawings_v2", Context.MODE_PRIVATE)
    }
    private var drawingsLoaded = false
    private var lastLoadedWidth = 0
    private var lastLoadedHeight = 0

    // Live-follow state: the chart advances smoothly through the current
    // candle interval, then snaps to the next candle when that interval starts.
    private var followLive = true
    // True only while a one-finger chart gesture is still a tap candidate.
    // This prevents incoming live ticks from snapping the view back to the
    // latest candle before the user has actually started scrolling history.
    private var manualPanCandidate = false
    private var lastCandleIntervalMillis = 5L * 60L * 1000L

    // Live chart clock. This advances every second so the open candle/time
    // scale behaves like a live trading chart even between price ticks.
    private var liveNowMillis: Long = System.currentTimeMillis()
    private var liveSymbol: String = ""
    private var liveTickTimeMillis: Long = 0L

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
    private var crosshairVisible = false
    private enum class CrosshairDragMode { NONE, VERTICAL, HORIZONTAL, BOTH }
    private var crosshairDragMode = CrosshairDragMode.NONE
    private var crosshairDownX = 0f
    private var crosshairDownY = 0f
    private var crosshairCreatedThisGesture = false
    private var lastY = 0f
    private var lastX = 0f
    private var dragging = false
    private var liveJumpPressed = false

    // Crosshair appears only after a short press-and-hold.
    // A quick finger drag remains a normal chart-pan gesture.
    private var fingerDown = false
    private var panGestureStarted = false
    private var crosshairHoldPending = false
    private var crosshairHoldRunnable: Runnable? = null

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

    private var pinchAnchorX = 0f
    private var pinchAnchorIndex = 0f
    private var pinchStartZoom = 1f
    private var pinchStartSpan = 1f

    // Drawing/chart synchronization: drawings are stored in screen coordinates,
    // so every chart transform (pan, zoom, live-follow or price-scale change)
    // must apply the exact same transform to the stored drawings.
    private var transformInitialized = false
    private var lastTransformOffsetX = 0f
    private var lastTransformStep = 0f
    private var lastTransformHigh = 0f
    private var lastTransformLow = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(
                detector: ScaleGestureDetector
            ): Boolean {
                scaleInProgress = true
                followLive = false

                // Capture the exact candle/time position under the two-finger
                // midpoint. During the whole pinch that position stays under
                // the same screen X, so the chart cannot run away toward an
                // edge or create an artificial horizontal jump.
                pinchAnchorX = detector.focusX.coerceIn(chartLeft(), chartRight())
                val step = candleStep().coerceAtLeast(0.0001f)
                pinchAnchorIndex =
                    (pinchAnchorX - chartLeft() - offsetX) / step
                pinchStartZoom = zoom
                pinchStartSpan = detector.currentSpan.coerceAtLeast(1f)
                return true
            }

            override fun onScale(
                detector: ScaleGestureDetector
            ): Boolean {
                val rawFactor = detector.scaleFactor
                if (!rawFactor.isFinite() || rawFactor <= 0f) return true

                // IMPORTANT: calculate zoom from the TOTAL pinch distance
                // since the gesture started, instead of multiplying every
                // detector callback into the previous zoom. This prevents
                // repeated callbacks from making the chart suddenly shrink
                // to a tiny/miniature size or explode off-screen.
                val startSpan = pinchStartSpan.coerceAtLeast(1f)
                val currentSpan = detector.currentSpan.coerceAtLeast(1f)
                val totalFactor = (currentSpan / startSpan).coerceIn(0.55f, 1.80f)

                // Keep pinch zoom deliberately moderate. The dedicated
                // time-axis gesture remains available for finer zooming.
                val newZoom = (pinchStartZoom * totalFactor)
                    .coerceIn(0.70f, 3.50f)
                val oldZoom = zoom
                if (kotlin.math.abs(newZoom - oldZoom) < 0.0001f) return true

                val newStep = (14f * newZoom).coerceIn(4f, 34f) +
                    (4f * newZoom).coerceIn(1f, 10f)

                // Keep the exact time/candle coordinate beneath the pinch
                // midpoint. No vertical scaling and no forced centering.
                val desiredOffset =
                    pinchAnchorX - chartLeft() - pinchAnchorIndex * newStep

                offsetX = desiredOffset
                zoom = newZoom

                // Scale screen-space drawings with the same horizontal factor.
                val ratio = newZoom / oldZoom
                // Horizontal safety only. Do not clamp pricePan here because
                // pinch zoom must not alter the vertical chart position.
                val oldOffset = offsetX
                clampHorizontalPanOnly()
                val correctionDx = offsetX - oldOffset

                invalidate()
                return true
            }

            override fun onScaleEnd(
                detector: ScaleGestureDetector
            ) {
                scaleInProgress = false
                pinchStartSpan = 1f
                pinchStartZoom = zoom
                saveDrawings()
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
        syncDrawingsToChartTransform()
        drawGrid(canvas)

        if (candles.isEmpty()) {

            drawEmptyChart(canvas)

        } else {

            drawCandles(canvas)
            drawCurrentPrice(canvas)
            drawPriceScale(canvas)
            drawTimeScale(canvas)
            drawHistoryLiveButton(canvas)
        }

        drawStoredDrawings(canvas)
        drawActiveDrawing(canvas)

        if (
            activeTool == DrawingTool.CROSSHAIR &&
            crosshairVisible &&
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
        // Temporary inspection crosshair: draw only inside the actual chart
        // area so it never covers the toolbar, price-scale controls, or bottom UI.
        val left = chartLeft()
        val right = chartRight()
        val top = chartTop()
        val bottom = chartBottom()

        val x = crossX.coerceIn(left, right)
        val y = crossY.coerceIn(top, bottom)

        canvas.drawLine(x, top, x, bottom, crosshairPaint)
        canvas.drawLine(left, y, right, y, crosshairPaint)
    }

    private fun crosshairPrice(): Float {
        val range = visibleRange()
        val high = range.first
        val low = range.second
        val h = (chartBottom() - chartTop()).coerceAtLeast(1f)
        return high - ((crossY - chartTop()) / h).coerceIn(0f, 1f) * (high - low)
    }

    private fun crosshairTimeMillis(): Long {
        if (candles.isEmpty()) return liveNowMillis
        val step = candleStep().coerceAtLeast(0.0001f)
        val position = ((crossX - chartLeft() - offsetX) / step)
            .coerceIn(0f, (candles.lastIndex).toFloat())
        val i0 = position.toInt().coerceIn(0, candles.lastIndex)
        val i1 = (i0 + 1).coerceAtMost(candles.lastIndex)
        if (i0 == i1) return candles[i0].time
        val fraction = position - i0
        val t0 = candles[i0].time
        val t1 = candles[i1].time
        return (t0 + ((t1 - t0) * fraction).toLong()).coerceAtLeast(0L)
    }

    private fun drawCrosshairLabels(canvas: Canvas) {
        val left = chartLeft()
        val right = chartRight()
        val top = chartTop()
        val bottom = chartBottom()

        val safeY = crossY.coerceIn(top, bottom)
        val safeX = crossX.coerceIn(left, right)

        // Price label belongs to the horizontal price level.
        val priceText = String.format(Locale.US, "%.5f", crosshairPrice())
        val priceLeft = right + 2f
        val priceRight = width.toFloat()
        val priceTop = (safeY - 15f).coerceAtLeast(0f)
        val priceBottom = (safeY + 15f).coerceAtMost(height.toFloat())

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = 11f
        labelPaint.color = Color.WHITE
        boxPaint.color = Color.rgb(35, 42, 52)
        boxPaint.style = Paint.Style.FILL

        canvas.drawRect(priceLeft, priceTop, priceRight, priceBottom, boxPaint)
        canvas.drawText(
            priceText,
            (priceLeft + priceRight) / 2f,
            (priceTop + priceBottom) / 2f + 4f,
            labelPaint
        )

        // Time label belongs to the vertical time level.
        // Show the actual candle date + time, not just seconds.
        val timeText = java.text.SimpleDateFormat(
            "EEE dd MMM ''yy hh:mm a",
            Locale.US
        ).format(java.util.Date(crosshairTimeMillis()))

        val timeLeft = (safeX - 78f).coerceAtLeast(left)
        val timeRight = (safeX + 78f).coerceAtMost(right)
        val timeTop = bottom + 2f
        val timeBottom = height.toFloat()

        canvas.drawRect(timeLeft, timeTop, timeRight, timeBottom, boxPaint)
        canvas.drawText(
            timeText,
            (timeLeft + timeRight) / 2f,
            timeBottom - 8f,
            labelPaint
        )
    }

    private fun historyLiveButtonRect(): RectF {
        val live = if (livePriceOverride.isFinite()) {
            livePriceOverride
        } else {
            candles.lastOrNull()?.close ?: Float.NaN
        }

        val range = visibleRange()
        val highest = range.first
        val lowest = range.second
        val priceRange = max(highest - lowest, 0.000001f)
        val liveY = if (live.isFinite()) {
            chartTop() +
                ((highest - live) / priceRange) *
                (chartBottom() - chartTop())
        } else {
            chartTop() + (chartBottom() - chartTop()) / 2f
        }

        // Small TradingView-style jump-to-live control immediately to the
        // left of the price-scale column. It appears only while browsing
        // history (followLive == false).
        val buttonW = 48f
        val buttonH = 42f
        val right = chartRight() - 4f
        val left = right - buttonW
        val top = (liveY - buttonH / 2f)
            .coerceIn(chartTop() + 4f, chartBottom() - buttonH - 4f)
        return RectF(left, top, right, top + buttonH)
    }

    private fun isInsideHistoryLiveButton(x: Float, y: Float): Boolean {
        if (followLive || candles.isEmpty()) return false
        return historyLiveButtonRect().contains(x, y)
    }

    private fun drawHistoryLiveButton(canvas: Canvas) {
        if (followLive || candles.isEmpty()) return

        val rect = historyLiveButtonRect()

        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = if (liveJumpPressed) {
            Color.rgb(70, 82, 98)
        } else {
            Color.rgb(42, 52, 66)
        }
        bg.style = Paint.Style.FILL

        canvas.drawRoundRect(rect, 10f, 10f, bg)

        val border = Paint(Paint.ANTI_ALIAS_FLAG)
        border.color = Color.rgb(105, 120, 138)
        border.style = Paint.Style.STROKE
        border.strokeWidth = 1f
        canvas.drawRoundRect(rect, 10f, 10f, border)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = 24f
        p.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(
            "»",
            rect.centerX(),
            rect.centerY() - (p.ascent() + p.descent()) / 2f,
            p
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

                // When browsing historical candles, this small right-edge
                // button returns directly to the latest live candle/price.
                // It is checked before chart dragging so a tap cannot start a
                // pan gesture underneath the button.
                if (isInsideHistoryLiveButton(event.x, event.y)) {
                    liveJumpPressed = true
                    resumeLiveFollow()
                    liveJumpPressed = false
                    invalidate()
                    return true
                }

                // Dragging directly on the right price-scale area moves the
                // entire candle field vertically. This is independent from
                // the drawing tool, so tools remain usable while the chart is
                // being re-centered.
                // Price-axis scroll: vertical swipe on the right price scale
                // changes ONLY vertical scale. The chart expands/contracts from
                // its center, so the top and bottom price levels move together.
                priceScaleDragging = event.x >= chartRight()
                if (priceScaleDragging) {
                    followLive = false
                    manualPanCandidate = false
                    lastPanY = event.y
                    invalidate()
                    return true
                }

                // Time-axis scroll: horizontal swipe on the bottom time scale
                // changes ONLY horizontal scale; the touch target is slightly expanded. The newest/right edge stays
                // anchored, so candles zoom toward/away from one side.
                timeScaleDragging = event.y >= chartBottom() - 36f
                if (timeScaleDragging) {
                    followLive = false
                    manualPanCandidate = false
                    lastTimePanX = event.x
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
                    val touchX = event.x
                    val touchY = event.y

                    fingerDown = true
                    panGestureStarted = false
                    crosshairHoldPending = false

                    if (!crosshairVisible) {
                        // Normal chart movement always has priority. A quick
                        // drag pans the chart; the hold gesture can still bring
                        // the crosshair in when the user actually needs it.
                        crosshairDownX = touchX
                        crosshairDownY = touchY
                        lastX = touchX
                        lastY = touchY
                        dragging = true
                        crosshairDragMode = CrosshairDragMode.NONE
                        crosshairCreatedThisGesture = false
                        manualPanCandidate = true
                        followLive = false

                        crosshairHoldPending = true
                        val runnable = Runnable {
                            if (
                                fingerDown &&
                                crosshairHoldPending &&
                                !panGestureStarted &&
                                !crosshairVisible &&
                                activeTool == DrawingTool.CROSSHAIR
                            ) {
                                crossX = crosshairDownX.coerceIn(chartLeft(), chartRight())
                                crossY = crosshairDownY.coerceIn(chartTop(), chartBottom())
                                crosshairVisible = true
                                crosshairCreatedThisGesture = true
                                crosshairDragMode = CrosshairDragMode.NONE
                                panGestureStarted = false
                                manualPanCandidate = false
                                dragging = true
                                crosshairHoldPending = false
                                invalidate()
                            }
                        }
                        crosshairHoldRunnable?.let { removeCallbacks(it) }
                        crosshairHoldRunnable = runnable
                        postDelayed(runnable, 350L)
                    } else {
                        // IMPORTANT: crosshair lines NEVER capture a normal
                        // one-finger drag. Whether both levels are visible or
                        // not, dragging anywhere on the chart always pans the
                        // chart. This keeps chart navigation independent from
                        // crosshair visibility.
                        crosshairCreatedThisGesture = false
                        crosshairDragMode = CrosshairDragMode.NONE
                        dragging = true
                        panGestureStarted = false
                        manualPanCandidate = true
                        lastX = touchX
                        lastY = touchY
                    }

                    crosshairDownX = touchX
                    crosshairDownY = touchY
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (scaleDetector.isInProgress || scaleInProgress) {
                    invalidate()
                    return true
                }

                // Quick one-finger movement = chart pan.
                // If the finger stays down long enough, the hold runnable above
                // switches this same gesture into the temporary crosshair.
                if (
                    activeTool == DrawingTool.CROSSHAIR &&
                    !crosshairVisible &&
                    fingerDown &&
                    !panGestureStarted
                ) {
                    val totalDx = event.x - crosshairDownX
                    val totalDy = event.y - crosshairDownY

                    if (kotlin.math.hypot(totalDx.toDouble(), totalDy.toDouble()) > 1.5) {
                        crosshairHoldPending = false
                        crosshairHoldRunnable?.let { removeCallbacks(it) }
                        crosshairHoldRunnable = null

                        panGestureStarted = true
                        manualPanCandidate = false
                        followLive = false

                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val oldOffsetX = offsetX
                        val oldPricePan = pricePan

                        offsetX += dx
                        pricePan += screenDyToPricePan(dy)
                        clampFreePan()

                        val appliedDx = offsetX - oldOffsetX
                        val appliedDy = pricePanToScreenDy(pricePan - oldPricePan)
                        if (appliedDx != 0f || appliedDy != 0f) {
                            translateDrawings(appliedDx, appliedDy)
                        }

                        lastX = event.x
                        lastY = event.y
                    }

                    invalidate()
                    return true
                }

                if (priceScaleDragging) {
                    val dy = event.y - lastPanY
                    // Vertical axis scroll is a true price zoom: keep the
                    // midpoint fixed, moving the top and bottom together.
                    val factor = (1f + dy * 0.0008f).coerceIn(0.985f, 1.015f)
                    val oldPriceZoom = priceZoom
                    val newPriceZoom = (oldPriceZoom * factor).coerceIn(0.55f, 6f)
                    if (newPriceZoom != oldPriceZoom) {
                        priceZoom = newPriceZoom
                    }
                    lastPanY = event.y
                    invalidate()
                    return true
                }

                if (timeScaleDragging) {
                    val dx = event.x - lastTimePanX
                    // Horizontal/time-axis scroll is one-sided: the right edge
                    // (latest candle/live area) stays fixed while candle spacing
                    // changes toward the left.
                    val factor = (1f - dx * 0.0008f).coerceIn(0.985f, 1.015f)
                    val oldZoom = zoom
                    val newZoom = (oldZoom * factor).coerceIn(0.45f, 5f)
                    if (newZoom != oldZoom) {
                        val ratio = newZoom / oldZoom
                        val anchorX = chartRight()
                        offsetX = anchorX - (anchorX - offsetX) * ratio
                        zoom = newZoom
                    }
                    lastTimePanX = event.x
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

                    // Crosshair visibility NEVER blocks chart movement.
                    // Any one-finger drag pans the chart in both X and Y.
                    if (
                        event.pointerCount == 1 &&
                        dragging &&
                        crosshairVisible &&
                        !scaleDetector.isInProgress
                    ) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val totalDx = event.x - crosshairDownX
                        val totalDy = event.y - crosshairDownY

                        if (kotlin.math.hypot(totalDx.toDouble(), totalDy.toDouble()) > 1.5) {
                            crosshairHoldPending = false
                            panGestureStarted = true
                            manualPanCandidate = false
                            followLive = false

                            val oldOffsetX = offsetX
                            val oldPricePan = pricePan

                            offsetX += dx
                            pricePan += screenDyToPricePan(dy)
                            clampFreePan()

                            val appliedDx = offsetX - oldOffsetX
                            val appliedDy = pricePanToScreenDy(pricePan - oldPricePan)
                            if (appliedDx != 0f || appliedDy != 0f) {
                                translateDrawings(appliedDx, appliedDy)
                            }

                            lastX = event.x
                            lastY = event.y
                        }
                    }
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP -> {

                fingerDown = false
                crosshairHoldPending = false
                crosshairHoldRunnable?.let { removeCallbacks(it) }
                crosshairHoldRunnable = null

                if (liveJumpPressed) {
                    liveJumpPressed = false
                    resumeLiveFollow()
                    invalidate()
                    return true
                }

                if (activeTool == DrawingTool.CROSSHAIR && crosshairVisible) {
                    dragging = false
                    crosshairDragMode = CrosshairDragMode.NONE
                    manualPanCandidate = false
                    val moved = kotlin.math.abs(event.x - crosshairDownX) > 6f ||
                        kotlin.math.abs(event.y - crosshairDownY) > 6f
                    if (!moved && !crosshairCreatedThisGesture) {
                        // A tap is only for placing the levels. It must never
                        // interfere with normal chart panning.
                        crossX = event.x.coerceIn(chartLeft(), chartRight())
                        crossY = event.y.coerceIn(chartTop(), chartBottom())
                        crosshairVisible = true
                    }
                    crosshairCreatedThisGesture = false
                    panGestureStarted = false
                    dragging = false
                    manualPanCandidate = false
                    invalidate()
                    return true
                }

                if (panGestureStarted && activeTool == DrawingTool.CROSSHAIR && !crosshairVisible) {
                    panGestureStarted = false
                    dragging = false
                    manualPanCandidate = false
                    saveDrawings()
                    invalidate()
                    return true
                }

                if (priceScaleDragging) {
                    priceScaleDragging = false
                    saveDrawings()
                    invalidate()
                    return true
                }

                if (timeScaleDragging) {
                    timeScaleDragging = false
                    saveDrawings()
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
                    // One-shot drawing tool: after one completed drawing,
                    // return to Crosshair. Select the tool again for the next drawing.
                    activeTool = DrawingTool.CROSSHAIR
                    saveDrawings()
                } else {
                    dragging = false
                    manualPanCandidate = false
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                fingerDown = false
                crosshairHoldPending = false
                crosshairHoldRunnable?.let { removeCallbacks(it) }
                crosshairHoldRunnable = null
                panGestureStarted = false
                dragging = false
                manualPanCandidate = false
                priceScaleDragging = false
                timeScaleDragging = false
                liveJumpPressed = false
                crosshairDragMode = CrosshairDragMode.NONE
                crosshairCreatedThisGesture = false

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

    private fun clampHorizontalPanOnly() {
        if (candles.isEmpty()) return

        val left = chartLeft()
        val right = chartRight()
        val step = candleStep()
        val width = candleWidth()

        // Keep a small number of candles reachable at all times, without
        // changing the vertical price position during a pinch.
        val keepCandles = minOf(3, candles.size)
        val lastIndex = candles.lastIndex

        val minOffset =
            left - ((lastIndex - keepCandles + 1).coerceAtLeast(0) * step + width)
        val maxOffset =
            right - ((keepCandles - 1) * step + width)

        offsetX = offsetX.coerceIn(minOffset, maxOffset)
    }

    private fun clampFreePan() {
        if (candles.isEmpty()) return

        val left = chartLeft()
        val right = chartRight()
        val step = candleStep()
        val width = candleWidth()

        // Horizontal: keep at least a small group of candles reachable.
        val keepCandles = minOf(3, candles.size)
        val lastIndex = candles.lastIndex

        val minOffset =
            left - ((lastIndex - keepCandles + 1).coerceAtLeast(0) * step + width)
        val maxOffset =
            right - ((keepCandles - 1) * step + width)

        offsetX = offsetX.coerceIn(minOffset, maxOffset)

        // Vertical: allow generous free movement, but keep the overall market
        // range within reach so the chart cannot become visually empty.
        var high = Float.NEGATIVE_INFINITY
        var low = Float.POSITIVE_INFINITY

        for (candle in candles) {
            high = maxOf(high, candle.high)
            low = minOf(low, candle.low)
        }

        if (livePriceOverride.isFinite()) {
            high = maxOf(high, livePriceOverride)
            low = minOf(low, livePriceOverride)
        }

        if (!high.isFinite() || !low.isFinite() || high <= low) return

        val fullRange = (high - low).coerceAtLeast(0.0000001f)
        val allowedPan = (fullRange / priceZoom) * 0.75f

        pricePan = pricePan.coerceIn(-allowedPan, allowedPan)
    }

    private fun pricePanToScreenDy(deltaPrice: Float): Float {
        val top = chartTop()
        val bottom = chartBottom()
        val h = (bottom - top).coerceAtLeast(1f)

        val visible = visibleRange()
        val range = (visible.first - visible.second).coerceAtLeast(0.0000001f)

        return (deltaPrice / range) * h
    }

    private fun screenDyToPricePan(dy: Float): Float {
        val h = (chartBottom() - chartTop()).coerceAtLeast(1f)
        val range = visibleRange()
        val visiblePriceRange = (range.first - range.second)
            .coerceAtLeast(0.0000005f)

        // Screen Y grows downward, while price grows upward.
        return (dy / h) * visiblePriceRange
    }

    private fun syncDrawingsToChartTransform() {
        if (candles.isEmpty()) {
            transformInitialized = false
            return
        }

        val range = visibleRange()
        val newHigh = range.first
        val newLow = range.second
        val newStep = candleStep().coerceAtLeast(0.0001f)

        if (!transformInitialized) {
            lastTransformOffsetX = offsetX
            lastTransformStep = newStep
            lastTransformHigh = newHigh
            lastTransformLow = newLow
            transformInitialized = true
            return
        }

        val oldOffset = lastTransformOffsetX
        val oldStep = lastTransformStep.coerceAtLeast(0.0001f)
        val oldHigh = lastTransformHigh
        val oldLow = lastTransformLow
        val oldRange = (oldHigh - oldLow).coerceAtLeast(0.0000001f)
        val newRange = (newHigh - newLow).coerceAtLeast(0.0000001f)

        val chartL = chartLeft()
        val chartT = chartTop()
        val chartH = (chartBottom() - chartT).coerceAtLeast(1f)

        // Drawings are stored as screen coordinates. Convert their old
        // screen position -> chart candle/price coordinates, then project
        // those same coordinates into the NEW chart transform.
        //
        // This is the important part: drawings follow the same candle/time
        // and price level as the candles, instead of being independently
        // translated/scaled in screen space.
        fun tx(x: Float): Float {
            val candlePosition = (x - chartL - oldOffset) / oldStep
            return chartL + candlePosition * newStep + offsetX
        }

        fun ty(y: Float): Float {
            val price = oldHigh - ((y - chartT) / chartH) * oldRange
            return chartT + ((newHigh - price) / newRange) * chartH
        }

        fun line(l: LineData) =
            LineData(tx(l.x1), ty(l.y1), tx(l.x2), ty(l.y2))

        fun rect(r: RectData) =
            RectData(tx(r.left), ty(r.top), tx(r.right), ty(r.bottom))

        fun measure(m: MeasureData) =
            MeasureData(tx(m.x1), ty(m.y1), tx(m.x2), ty(m.y2))

        for (i in trendLines.indices) trendLines[i] = line(trendLines[i])
        for (i in rayLines.indices) rayLines[i] = line(rayLines[i])
        for (i in horizontalLines.indices) horizontalLines[i] = ty(horizontalLines[i])
        for (i in verticalLines.indices) verticalLines[i] = tx(verticalLines[i])
        for (i in rectangles.indices) rectangles[i] = rect(rectangles[i])
        for (i in circles.indices) circles[i] = rect(circles[i])
        for (i in arrows.indices) arrows[i] = line(arrows[i])
        for (i in parallelChannels.indices) parallelChannels[i] = line(parallelChannels[i])
        for (i in fibonacciLines.indices) fibonacciLines[i] = line(fibonacciLines[i])
        for (i in measurements.indices) measurements[i] = measure(measurements[i])

        for (i in brushStrokes.indices) {
            brushStrokes[i] = brushStrokes[i].map { (x, y) ->
                tx(x) to ty(y)
            }
        }

        for (i in textMarkers.indices) {
            val p = textMarkers[i]
            textMarkers[i] = tx(p.first) to ty(p.second)
        }

        lastTransformOffsetX = offsetX
        lastTransformStep = newStep
        lastTransformHigh = newHigh
        lastTransformLow = newLow
    }

    private fun translateDrawings(dx: Float, dy: Float) {
        fun line(l: LineData) = LineData(l.x1 + dx, l.y1 + dy, l.x2 + dx, l.y2 + dy)
        fun rect(r: RectData) = RectData(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
        fun measure(m: MeasureData) = MeasureData(m.x1 + dx, m.y1 + dy, m.x2 + dx, m.y2 + dy)

        for (i in trendLines.indices) trendLines[i] = line(trendLines[i])
        for (i in rayLines.indices) rayLines[i] = line(rayLines[i])
        for (i in horizontalLines.indices) horizontalLines[i] += dy
        for (i in verticalLines.indices) verticalLines[i] += dx
        for (i in rectangles.indices) rectangles[i] = rect(rectangles[i])
        for (i in circles.indices) circles[i] = rect(circles[i])
        for (i in arrows.indices) arrows[i] = line(arrows[i])
        for (i in parallelChannels.indices) parallelChannels[i] = line(parallelChannels[i])
        for (i in fibonacciLines.indices) fibonacciLines[i] = line(fibonacciLines[i])
        for (i in measurements.indices) measurements[i] = measure(measurements[i])
        for (i in brushStrokes.indices) {
            brushStrokes[i] = brushStrokes[i].map { (x, y) -> (x + dx) to (y + dy) }
        }
        for (i in textMarkers.indices) {
            val p = textMarkers[i]
            textMarkers[i] = (p.first + dx) to (p.second + dy)
        }
        if (activeBrush.isNotEmpty()) {
            activeBrush = activeBrush.map { (x, y) -> (x + dx) to (y + dy) }.toMutableList()
        }
        saveDrawings()
    }

    private fun scaleDrawings(sx: Float, sy: Float, fx: Float, fy: Float) {
        fun tx(x: Float) = fx + (x - fx) * sx
        fun ty(y: Float) = fy + (y - fy) * sy
        fun line(l: LineData) = LineData(tx(l.x1), ty(l.y1), tx(l.x2), ty(l.y2))
        fun rect(r: RectData) = RectData(tx(r.left), ty(r.top), tx(r.right), ty(r.bottom))
        fun measure(m: MeasureData) = MeasureData(tx(m.x1), ty(m.y1), tx(m.x2), ty(m.y2))

        for (i in trendLines.indices) trendLines[i] = line(trendLines[i])
        for (i in rayLines.indices) rayLines[i] = line(rayLines[i])
        for (i in horizontalLines.indices) horizontalLines[i] = ty(horizontalLines[i])
        for (i in verticalLines.indices) verticalLines[i] = tx(verticalLines[i])
        for (i in rectangles.indices) rectangles[i] = rect(rectangles[i])
        for (i in circles.indices) circles[i] = rect(circles[i])
        for (i in arrows.indices) arrows[i] = line(arrows[i])
        for (i in parallelChannels.indices) parallelChannels[i] = line(parallelChannels[i])
        for (i in fibonacciLines.indices) fibonacciLines[i] = line(fibonacciLines[i])
        for (i in measurements.indices) measurements[i] = measure(measurements[i])
        for (i in brushStrokes.indices) {
            brushStrokes[i] = brushStrokes[i].map { (x, y) -> tx(x) to ty(y) }
        }
        for (i in textMarkers.indices) {
            val p = textMarkers[i]
            textMarkers[i] = tx(p.first) to ty(p.second)
        }
        saveDrawings()
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
        } else if (followLive && !manualPanCandidate) {
            // The exact live-follow position is calculated every frame from
            // liveNowMillis, so incoming ticks do not reset the timeline.
            updateLiveFollowOffset()
        }

        invalidate()
    }

    /**
     * Compatibility API used by MainActivity. The timeframe is supplied as
     * the already-converted interval in milliseconds. Keeping this method
     * separate avoids changing the locked live price/countdown rendering.
     */
    fun setLiveTimeframe(intervalMillis: Long) {
        lastCandleIntervalMillis = intervalMillis.coerceAtLeast(1L)
        invalidate()
    }

    /** Stores the currently selected market symbol for live-chart state. */
    fun setLiveSymbol(symbol: String) {
        liveSymbol = symbol
        invalidate()
    }

    /** Receives the timestamp of the latest real market tick. */
    fun setLiveTickTime(tickTimeMillis: Long) {
        liveTickTimeMillis = tickTimeMillis
        invalidate()
    }

    /** Clears the live price/countdown override without changing chart UI. */
    fun clearLivePriceCountdown() {
        livePriceOverride = Float.NaN
        livePriceCountdownMillis = 0L
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
        manualPanCandidate = false
        followLive = true
        updateLiveFollowOffset()
        invalidate()
    }

    fun clearCandles() {

        candles.clear()

        invalidate()
    }
}

// Compile-fix: drawing helper methods drawRay/drawArrow/drawFibonacci/drawMeasureLabel are defined in this class.
