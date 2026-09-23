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
    private var timeScaleDragging = false
    private var lastTimePanX = 0f

    private val drawingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("digitech_chart_drawings_v3", Context.MODE_PRIVATE)
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
    private var lastY = 0f
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

    private var pinchAnchorX = 0f
    private var pinchAnchorIndex = 0f
    private var pinchStartZoom = 1f
    private var pinchStartSpan = 1f

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
                scaleDrawings(
                    ratio,
                    1f,
                    pinchAnchorX,
                    (chartTop() + chartBottom()) * 0.5f
                )

                // Horizontal safety only. Do not clamp pricePan here because
                // pinch zoom must not alter the vertical chart position.
                val oldOffset = offsetX
                clampHorizontalPanOnly()
                val correctionDx = offsetX - oldOffset
                if (kotlin.math.abs(correctionDx) > 0.001f) {
                    translateDrawings(correctionDx, 0f)
                }

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
        for (line in trendLines) canvas.drawLine(
            screenXFromAnchor(line.x1), screenYFromPrice(line.y1),
            screenXFromAnchor(line.x2), screenYFromPrice(line.y2), drawingPaint
        )

        drawingPaint.color = Color.rgb(120, 165, 255)
        for (line in rayLines) drawRay(canvas, lineOnScreen(line), drawingPaint)

        drawingPaint.color = Color.rgb(235, 190, 55)
        for (price in horizontalLines) {
            val y = screenYFromPrice(price)
            canvas.drawLine(0f, y, width.toFloat(), y, drawingPaint)
        }

        drawingPaint.color = Color.rgb(235, 160, 80)
        for (xIndex in verticalLines) {
            val x = screenXFromAnchor(xIndex)
            canvas.drawLine(x, 0f, x, height.toFloat(), drawingPaint)
        }

        drawingPaint.color = Color.rgb(55, 180, 235)
        for (rect in rectangles) canvas.drawRect(rectOnScreen(rect).let {
            android.graphics.RectF(it.left, it.top, it.right, it.bottom)
        }, drawingPaint)

        drawingPaint.color = Color.rgb(120, 210, 180)
        for (circle in circles) {
            val r = rectOnScreen(circle)
            canvas.drawOval(r.left, r.top, r.right, r.bottom, drawingPaint)
        }

        drawingPaint.color = Color.rgb(255, 150, 70)
        for (line in arrows) drawArrow(canvas, lineOnScreen(line), drawingPaint)

        drawingPaint.color = Color.rgb(180, 120, 240)
        for (line in parallelChannels) {
            val on = lineOnScreen(line)
            canvas.drawLine(on.x1, on.y1, on.x2, on.y2, drawingPaint)
            val dx = on.x2 - on.x1
            val dy = on.y2 - on.y1
            val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val nx = -dy / length * 24f
            val ny = dx / length * 24f
            canvas.drawLine(on.x1 + nx, on.y1 + ny, on.x2 + nx, on.y2 + ny, drawingPaint)
        }

        drawingPaint.color = Color.rgb(80, 210, 255)
        for (line in fibonacciLines) drawFibonacci(canvas, lineOnScreen(line), drawingPaint)

        drawingPaint.color = Color.WHITE
        for (stroke in brushStrokes) {
            if (stroke.size < 2) continue
            for (i in 1 until stroke.size) {
                val a = stroke[i - 1]
                val b = stroke[i]
                canvas.drawLine(
                    screenXFromAnchor(a.first), screenYFromPrice(a.second),
                    screenXFromAnchor(b.first), screenYFromPrice(b.second), drawingPaint
                )
            }
        }

        for (measure in measurements) {
            val on = measureOnScreen(measure)
            drawingPaint.color = Color.rgb(190, 120, 235)
            canvas.drawLine(on.x1, on.y1, on.x2, on.y2, drawingPaint)
            drawMeasureLabel(canvas, on.x1, on.y1, on.x2, on.y2)
        }

        labelPaint.color = Color.WHITE
        labelPaint.textSize = 13f
        labelPaint.textAlign = Paint.Align.LEFT
        for (p in textMarkers) {
            canvas.drawText("TEXT", screenXFromAnchor(p.first), screenYFromPrice(p.second), labelPaint)
        }
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
                // Price-axis scroll: vertical swipe on the right price scale
                // changes ONLY vertical scale. The chart expands/contracts from
                // its center, so the top and bottom price levels move together.
                priceScaleDragging = event.x >= chartRight()
                if (priceScaleDragging) {
                    followLive = false
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

                    crossX =
                        event.x

                    crossY =
                        event.y

                    lastX =
                        event.x

                    lastY =
                        event.y

                    dragging =
                        true
                    followLive =
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
                    // Vertical axis scroll is a true price zoom: keep the
                    // midpoint fixed, moving the top and bottom together.
                    val factor = (1f + dy * 0.0008f).coerceIn(0.985f, 1.015f)
                    val oldPriceZoom = priceZoom
                    val newPriceZoom = (oldPriceZoom * factor).coerceIn(0.55f, 6f)
                    if (newPriceZoom != oldPriceZoom) {
                        priceZoom = newPriceZoom
                        val ratio = newPriceZoom / oldPriceZoom
                        val centerY = (chartTop() + chartBottom()) * 0.5f
                        scaleDrawings(1f, ratio, chartRight(), centerY)
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
                        scaleDrawings(ratio, 1f, anchorX, chartTop())
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

                    if (
                        event.pointerCount == 1 &&
                        dragging &&
                        !scaleDetector.isInProgress
                    ) {

                        followLive = false

                        val dx = event.x - lastX
                        val dy = event.y - lastY

                        // FREE one-finger chart navigation:
                        // horizontal + vertical movement are applied together,
                        // so the chart follows the finger in any direction,
                        // including diagonal movement.
                        val oldOffsetX = offsetX
                        val oldPricePan = pricePan

                        // Direct "grab and move" behavior:
                        // finger left  -> chart left
                        // finger right -> chart right
                        // finger up    -> chart up
                        // finger down  -> chart down
                        offsetX += dx
                        pricePan += screenDyToPricePan(dy)

                        // Keep the free-pan gesture, but never allow the chart
                        // to be dragged into a completely empty screen.
                        clampFreePan()

                        val appliedDx = offsetX - oldOffsetX
                        val appliedDy = pricePanToScreenDy(pricePan - oldPricePan)
                        translateDrawings(appliedDx, appliedDy)

                        lastX = event.x
                        lastY = event.y
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

                    val aX = anchorXFromScreen(startX)
                    val aY = anchorPriceFromScreen(startY)
                    val bX = anchorXFromScreen(endX)
                    val bY = anchorPriceFromScreen(endY)

                    when (activeTool) {
                        DrawingTool.TREND_LINE -> trendLines.add(LineData(aX, aY, bX, bY))
                        DrawingTool.RAY -> rayLines.add(LineData(aX, aY, bX, bY))
                        DrawingTool.HORIZONTAL_LINE -> horizontalLines.add(bY)
                        DrawingTool.VERTICAL_LINE -> verticalLines.add(bX)
                        DrawingTool.RECTANGLE -> rectangles.add(
                            RectData(min(aX,bX), max(aY,bY), max(aX,bX), min(aY,bY))
                        )
                        DrawingTool.CIRCLE -> circles.add(
                            RectData(min(aX,bX), max(aY,bY), max(aX,bX), min(aY,bY))
                        )
                        DrawingTool.ARROW -> arrows.add(LineData(aX, aY, bX, bY))
                        DrawingTool.PARALLEL_CHANNEL -> parallelChannels.add(LineData(aX, aY, bX, bY))
                        DrawingTool.FIBONACCI -> fibonacciLines.add(LineData(aX, aY, bX, bY))
                        DrawingTool.MEASURE -> measurements.add(MeasureData(aX, aY, bX, bY))
                        DrawingTool.BRUSH -> {
                            if (activeBrush.size > 1) {
                                brushStrokes.add(activeBrush.map { (x,y) -> anchorXFromScreen(x) to anchorPriceFromScreen(y) })
                            }
                            activeBrush.clear()
                        }
                        DrawingTool.TEXT -> textMarkers.add(bX to bY)
                        DrawingTool.CROSSHAIR -> Unit
                    }

                    startX = -1f
                    startY = -1f
                    currentX = -1f
                    currentY = -1f
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
                timeScaleDragging = false

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

    // Drawing coordinates are stored in chart space, not screen pixels.
    // X = fractional candle index/time position; Y = actual price.
    // This is the key invariant that keeps drawings attached to candles
    // through pan, zoom and device rotation.
    private fun screenXFromAnchor(xIndex: Float): Float {
        return chartLeft() + xIndex * candleStep() + offsetX
    }

    private fun screenYFromPrice(price: Float): Float {
        val range = visibleRange()
        return priceToY(price, range.first, range.second)
    }

    private fun anchorXFromScreen(x: Float): Float {
        return (x - chartLeft() - offsetX) / candleStep().coerceAtLeast(0.0001f)
    }

    private fun anchorPriceFromScreen(y: Float): Float {
        val range = visibleRange()
        val h = (chartBottom() - chartTop()).coerceAtLeast(1f)
        return range.first - ((y - chartTop()) / h) * (range.first - range.second)
    }

    private fun lineOnScreen(l: LineData): LineData = LineData(
        screenXFromAnchor(l.x1), screenYFromPrice(l.y1),
        screenXFromAnchor(l.x2), screenYFromPrice(l.y2)
    )

    private fun rectOnScreen(r: RectData): RectData = RectData(
        screenXFromAnchor(r.left), screenYFromPrice(r.top),
        screenXFromAnchor(r.right), screenYFromPrice(r.bottom)
    )

    private fun measureOnScreen(m: MeasureData): MeasureData = MeasureData(
        screenXFromAnchor(m.x1), screenYFromPrice(m.y1),
        screenXFromAnchor(m.x2), screenYFromPrice(m.y2)
    )

    private fun translateDrawings(dx: Float, dy: Float) {
        // No-op by design. Drawings live in candle-index/price coordinates,
        // so chart pan automatically moves them through the renderer.
    }

    private fun scaleDrawings(sx: Float, sy: Float, fx: Float, fy: Float) {
        // No-op by design. Zoom changes the chart transform; anchored drawings
        // are reprojected from candle-index/price coordinates automatically.
    }

    private fun saveDrawings() {
        try {
            val root = JSONObject()
            root.put("version", 3)
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
            val version = root.optInt("version", 2)
            if (version < 3) return
            val sx = 1f
            val sy = 1f
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
        } else if (followLive) {
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
        followLive = true
        updateLiveFollowOffset()
        invalidate()
    }

    fun clearCandles() {

        candles.clear()

        invalidate()
    }
}
