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
import kotlin.math.abs

data class Candle(
    val time: Long,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float
)

/**
 * Source-timeframe candle used only by the additive ICT MTF layer.
 * It never replaces the locked live chart candle list.
 */
private data class IctSourceCandle(
    val time: Long,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float
)

private data class IctMtfPivot(
    val sourceIndex: Int,
    val sourceTime: Long,
    val price: Float,
    val high: Boolean,
    val sourceIntervalMillis: Long,
    val kind: String
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

    // ICT structure overlay foundation (additive only; existing chart logic remains locked).
    private val ictStructurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ictStructureTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ictStructureLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // ICT V27: additive liquidity overlay. This is intentionally separate
    // from the locked structure renderer so existing structure/live logic is
    // not modified.
    private val ictLiquidityLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ictLiquidityTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // V27.5: shared ICT label-collision registry. Labels from structure,
    // classification, INT/EXT and BSL/SSL all register here during one
    // draw pass, so collision handling works across different ICT layers.
    private data class IctLabelBox(val rect: RectF)
    private val ictOccupiedLabelBoxes = mutableListOf<IctLabelBox>()

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
    // Closed-market lock: freezes live visual time/follow movement so the
    // last valid candle cannot appear to keep forming while the market is closed.
    private var marketOpen = true
    // Weekend/closed-market countdown must remain exactly frozen across app reopen.
    private var frozenCountdownMode = false

    // Independent safety gate for the currently locked weekend behavior.
    // This prevents the chart from visually advancing even if MainActivity
    // has not yet delivered the market-state update.
    private fun isWeekendClosedNow(): Boolean {
        val utc = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC")
        )
        val day = utc.get(java.util.Calendar.DAY_OF_WEEK)
        return day == java.util.Calendar.SATURDAY ||
            day == java.util.Calendar.SUNDAY
    }

    private val liveClockRunnable = object : Runnable {
        override fun run() {
            if (marketOpen && !isWeekendClosedNow()) {
                liveNowMillis = System.currentTimeMillis()
                // Countdown and the visual candle/time position must advance
                // together continuously between real provider ticks.
                if (followLive && candles.isNotEmpty() && !manualPanCandidate) {
                    updateLiveFollowOffset()
                    invalidate()
                    postDelayed(this, 16L)
                    return
                }
                invalidate()
            }
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

    private data class AnchorPoint(
        val time: Long,
        val price: Float
    )

    private data class LineData(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val t1: Long = 0L,
        val t2: Long = 0L
    )

    private data class RectData(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val tLeft: Long = 0L,
        val tRight: Long = 0L
    )

    private data class MeasureData(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val t1: Long = 0L,
        val t2: Long = 0L
    )

    private val trendLines = mutableListOf<LineData>()
    private val rayLines = mutableListOf<LineData>()
    private val horizontalLines = mutableListOf<Float>()
    private val verticalLines = mutableListOf<Long>()
    private val rectangles = mutableListOf<RectData>()
    private val circles = mutableListOf<RectData>()
    private val arrows = mutableListOf<LineData>()
    private val parallelChannels = mutableListOf<LineData>()
    private val fibonacciLines = mutableListOf<LineData>()
    private val measurements = mutableListOf<MeasureData>()
    private val brushStrokes = mutableListOf<List<AnchorPoint>>()
    private val textMarkers = mutableListOf<AnchorPoint>()

    private var activeBrush = mutableListOf<Pair<Float, Float>>()

    private var scaleInProgress = false

    private var pinchAnchorX = 0f
    private var pinchAnchorIndex = 0f
    private var pinchStartZoom = 1f
    private var pinchStartSpan = 1f

    // Drawing/chart synchronization is handled from immutable time + price
    // anchors. Chart transforms remap those anchors every frame, so drawings
    // stay attached to the same market time and price.
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

        ictStructurePaint.style = Paint.Style.STROKE
        ictStructurePaint.strokeWidth = 1.4f
        ictStructureTextPaint.textAlign = Paint.Align.CENTER
        ictStructureTextPaint.textSize = 14f
        ictStructureLinePaint.style = Paint.Style.STROKE
        ictStructureLinePaint.strokeWidth = 1.1f
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
            // ICT structure overlays: confirmed pivots only, directly on the main chart.
            drawIctStructureOverlays(canvas)
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

    // TradingView-style drawing anchors: store each point as a continuous
    // candle/time position plus an actual price. Never store the final screen
    // X/Y as the drawing's permanent position.
    private fun screenToCandlePos(x: Float): Float {
        val step = candleStep().coerceAtLeast(0.0001f)
        return (x - chartLeft() - offsetX) / step
    }

    private fun candlePosToScreenX(pos: Float): Float {
        return chartLeft() + pos * candleStep() + offsetX
    }

    private fun screenToPrice(y: Float): Float {
        val range = visibleRange()
        val high = range.first
        val low = range.second
        val h = (chartBottom() - chartTop()).coerceAtLeast(1f)
        return high - ((y - chartTop()) / h) * (high - low)
    }

    private fun priceToScreenY(price: Float): Float {
        val range = visibleRange()
        val high = range.first
        val low = range.second
        val r = (high - low).coerceAtLeast(0.0000001f)
        val h = (chartBottom() - chartTop()).coerceAtLeast(1f)
        return chartTop() + ((high - price) / r) * h
    }

    private fun screenPointToAnchor(x: Float, y: Float): Pair<Float, Float> =
        screenToCandlePos(x) to screenToPrice(y)

    private fun candlePosToTime(pos: Float): Long {
        if (candles.isEmpty()) return liveNowMillis
        if (candles.size == 1) return candles[0].time
        if (pos <= 0f) {
            val dt = candles[1].time - candles[0].time
            return candles[0].time + (pos * dt).toLong()
        }
        val last = candles.lastIndex.toFloat()
        if (pos >= last) {
            val dt = candles.last().time - candles[candles.lastIndex - 1].time
            return candles.last().time + ((pos - last) * dt).toLong()
        }
        val i = kotlin.math.floor(pos.toDouble()).toInt().coerceIn(0, candles.lastIndex - 1)
        val f = pos - i
        return (candles[i].time + ((candles[i + 1].time - candles[i].time) * f).toLong())
    }

    private fun timeToCandlePos(time: Long): Float {
        if (candles.isEmpty()) return 0f
        if (candles.size == 1) return 0f
        if (time <= candles[0].time) {
            val dt = (candles[1].time - candles[0].time).coerceAtLeast(1L)
            return (time - candles[0].time).toFloat() / dt.toFloat()
        }
        val last = candles.lastIndex
        if (time >= candles[last].time) {
            val dt = (candles[last].time - candles[last - 1].time).coerceAtLeast(1L)
            return last + (time - candles[last].time).toFloat() / dt.toFloat()
        }
        var lo = 0
        var hi = last
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (candles[mid].time <= time) lo = mid else hi = mid
        }
        val dt = (candles[hi].time - candles[lo].time).coerceAtLeast(1L)
        return lo + (time - candles[lo].time).toFloat() / dt.toFloat()
    }

    private fun screenPointToTimedAnchor(x: Float, y: Float): AnchorPoint =
        AnchorPoint(candlePosToTime(screenToCandlePos(x)), screenToPrice(y))

    private fun anchorX(pos: Float, time: Long): Float =
        candlePosToScreenX(if (time != 0L) timeToCandlePos(time) else pos)

    private fun anchorLineToScreen(line: LineData): LineData =
        LineData(
            anchorX(line.x1, line.t1), priceToScreenY(line.y1),
            anchorX(line.x2, line.t2), priceToScreenY(line.y2),
            line.t1, line.t2
        )

    private fun anchorRectToScreen(rect: RectData): RectData =
        RectData(
            anchorX(rect.left, rect.tLeft), priceToScreenY(rect.top),
            anchorX(rect.right, rect.tRight), priceToScreenY(rect.bottom),
            rect.tLeft, rect.tRight
        )

    private fun anchorMeasureToScreen(m: MeasureData): MeasureData =
        MeasureData(
            anchorX(m.x1, m.t1), priceToScreenY(m.y1),
            anchorX(m.x2, m.t2), priceToScreenY(m.y2),
            m.t1, m.t2
        )

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

    /**
     * ICT structure overlay foundation.
     *
     * Rules:
     * - confirmed pivots only; no forming/live pivot is drawn
     * - levels are anchored to candle index + actual price
     * - every confirmed pivot in the currently visible chart window is eligible for its assigned lane
     * - each hierarchy is calculated from its own source timeframe
     * - STH/STL = short-term, ITH/ITL = intermediate-term, SH/SL = higher/swing
     * - LTH/LTL = long-term structure derived from confirmed SH/SL
     * - higher structure is projected only as source-timeframe references
     * - the three hierarchy levels are never calculated from the same candle series
     * - source timeframe is preserved when a higher-timeframe level is projected
     * - larger readable labels remain anchored to the source candle
     * - this method does not alter candles, live follow, countdown, or drawings
     */
    private data class IctPivot(val index: Int, val price: Float, val high: Boolean)

    // True-MTF ICT source layer. Higher-timeframe candles are aggregated from
    // the already supplied real candle stream; no synthetic prices are created.
    // This keeps the locked live candle/countdown path untouched while giving
    // ICT concepts a real source timeframe instead of relabeling the same candles.
    private val ictMtfSourceCache = mutableMapOf<Long, List<IctSourceCandle>>()
    private var ictMtfCacheSize = -1
    private var ictMtfCacheLastTime = Long.MIN_VALUE
    private var ictMtfCacheLastOpen = Float.NaN
    private var ictMtfCacheLastHigh = Float.NaN
    private var ictMtfCacheLastLow = Float.NaN
    private var ictMtfCacheLastClose = Float.NaN
    private var ictMtfEnabled = true
    private var ictMtfProjectionEnabled = false

    // ICT Visibility Engine V25: balanced readable visibility.
    // Detection and rendering are deliberately separate. All concepts may be
    // calculated by their own engines, but the chart only renders concepts
    // that are relevant to the active timeframe/context. This prevents the
    // completed ICT master list from turning the chart into a wall of labels.
    private enum class IctVisibilityMode {
        CLEAN_CHART,
        SETUP_CONTEXT
    }

    private data class IctVisibilityPolicy(
        val showPrimaryStructure: Boolean,
        val showInternalStructure: Boolean,
        val showStructureClassification: Boolean,
        val showExternalTag: Boolean,
        val maxPrimaryLabels: Int,
        val maxInternalLabels: Int,
        val maxClassificationLabels: Int
    )

    private var ictVisibilityMode = IctVisibilityMode.CLEAN_CHART

    private fun ictVisibilityPolicy(role: IctStructureRole): IctVisibilityPolicy {
        return when (ictVisibilityMode) {
            IctVisibilityMode.CLEAN_CHART -> IctVisibilityPolicy(
                // Balanced mode: enough information to understand the current
                // structure, but never the entire ICT history as labels.
                showPrimaryStructure = true,
                showInternalStructure = true,
                showStructureClassification = true,
                showExternalTag = false,
                maxPrimaryLabels = 4,
                maxInternalLabels = 1,
                maxClassificationLabels = 1
            )
            IctVisibilityMode.SETUP_CONTEXT -> IctVisibilityPolicy(
                showPrimaryStructure = true,
                showInternalStructure = true,
                showStructureClassification = true,
                showExternalTag = true,
                maxPrimaryLabels = 6,
                maxInternalLabels = 2,
                maxClassificationLabels = 2
            )
        }
    }

    private fun drawIctStructureOverlays(canvas: Canvas) {
        // One shared registry per frame; this allows all ICT labels to avoid
        // each other instead of resolving only within their own layer.
        ictOccupiedLabelBoxes.clear()
        if (!ictMtfEnabled || candles.size < 5) return

        val range = visibleRange()
        val highest = range.first
        val lowest = range.second
        val left = chartLeft()
        val right = chartRight()
        val step = candleStep()
        val candleW = candleWidth()
        val current = lastCandleIntervalMillis.coerceAtLeast(1L)

        // The ICT hierarchy is displayed in its own structural role. We do
        // NOT draw all three hierarchies on every timeframe because that is
        // exactly what caused the chart clutter in the previous versions.
        //
        // Standard Digitech ICT structure role map:
        // 15m and below -> Short-Term Structure (STH/STL)
        // 1H             -> Intermediate-Term Structure (ITH/ITL)
        // 4H             -> Long-Term Structure (LTH/LTL)
        // The SH/SL layer remains an intermediate hierarchy used to derive
        // LTH/LTL; it is not forced onto every chart as a separate visual lane.
        // Above 4H, the long-term structure remains the relevant higher-timeframe
        // context rather than creating duplicate lower-hierarchy drawings.
        //
        // A higher-timeframe reference can be projected later by the Trade
        // Scan/Context layer only when the active setup actually requires it.
        // It is therefore OFF here by default rather than copying whole HTF
        // structure histories onto the current chart.
        // IMPORTANT: the selected ICT role is now calculated from its OWN
        // source timeframe, not from the current chart candle list. The
        // current chart is only the display/projection surface.
        val role = ictVisibleStructureRole(current)
        val policy = ictVisibilityPolicy(role)
        val sourceInterval = ictSourceIntervalForRole(current, role)
        // Verification mapping: 15M and below = STH/STL, 1H = ITH/ITL,
        // 4H and above = LTH/LTL. Internal/External is derived from the
        // immediately lower confirmed hierarchy and is rendered sparsely.


        val source = aggregateIctSourceCandles(sourceInterval)
        if (source.size < 5) return
        val pivots = detectIctPivots(source, sourceInterval)

        // V28 — ICT Concept #7: Dealing Range.
        // Source is the SAME structural timeframe selected above. Only the
        // active range is rendered; no lower/other timeframe ranges are copied
        // onto the chart.
        drawIctDealingRange(canvas, pivots, role, highest, lowest, left, right, step, candleW)

        // V29 — ICT Concept #8: Equilibrium / 50%.
        // Uses the exact same confirmed dealing range as Concept #7. It is
        // intentionally rendered only for the active structural role so no
        // unrelated timeframe midpoint is projected onto the chart.
        drawIctEquilibrium(canvas, pivots, role, highest, lowest, left, right, step, candleW)

        // V30 — ICT Concept #9: Break of Structure (BOS).
        // BOS is confirmed only when a source-timeframe candle CLOSE breaks
        // a previously confirmed structural pivot. Wick-only penetration is
        // not treated as BOS. Only the active structural hierarchy is used,
        // preserving the locked timeframe-role/anti-clutter rule.
        drawIctBos(canvas, source, pivots, role, highest, lowest, left, right, step, candleW)

        when (role) {
            IctStructureRole.SHORT_TERM -> {
                val short = pivots.filter { it.kind == "STH" || it.kind == "STL" }
                drawIctMtfPivotSetWithClassification(
                    canvas, short, highest, lowest, left, right, step, candleW, 0, emptyList(), policy
                )
                // V27.3 MTF verification rule: BSL/SSL must remain available
                // on short-term charts even when the preferred STH/STL lane is
                // temporarily sparse. The fallback is still from the SAME
                // source timeframe; it never imports another timeframe.
                val liquiditySource = if (short.isNotEmpty()) short
                else pivots.filter { it.kind == "SH" || it.kind == "SL" }
                drawIctLiquidityOverlays(canvas, liquiditySource, highest, lowest, left, right, step, candleW, policy)
            }
            IctStructureRole.INTERMEDIATE_TERM -> {
                val intermediate = pivots.filter { it.kind == "ITH" || it.kind == "ITL" }
                val internal = pivots.filter { it.kind == "STH" || it.kind == "STL" }
                drawIctMtfPivotSetWithClassification(
                    canvas, intermediate, highest, lowest, left, right, step, candleW, 1, internal, policy
                )
                // V27.2: If the preferred ITH/ITL lane is temporarily sparse,
                // use the same-source STH/STL pivots as the liquidity pool source.
                // This prevents a blank liquidity layer without projecting an
                // unrelated timeframe onto the chart.
                val liquiditySource = if (intermediate.isNotEmpty()) intermediate
                else internal
                drawIctLiquidityOverlays(canvas, liquiditySource, highest, lowest, left, right, step, candleW, policy)
            }
            IctStructureRole.HIGHER_TERM -> {
                val higher = pivots.filter { it.kind == "SH" || it.kind == "SL" }
                val internal = pivots.filter { it.kind == "STH" || it.kind == "STL" }
                drawIctMtfPivotSetWithClassification(
                    canvas, higher, highest, lowest, left, right, step, candleW, 2, internal, policy
                )
                // V27.2: SH/SL is the same-source fallback when the higher lane
                // is sparse; the source timeframe remains unchanged.
                val liquiditySource = if (higher.isNotEmpty()) higher
                else pivots.filter { it.kind == "SH" || it.kind == "SL" || it.kind == "STH" || it.kind == "STL" }
                drawIctLiquidityOverlays(canvas, liquiditySource, highest, lowest, left, right, step, candleW, policy)
            }
            IctStructureRole.LONG_TERM -> {
                val longTerm = pivots.filter { it.kind == "LTH" || it.kind == "LTL" }
                val internal = pivots.filter { it.kind == "SH" || it.kind == "SL" }
                drawIctMtfPivotSetWithClassification(
                    canvas, longTerm, highest, lowest, left, right, step, candleW, 3, internal, policy
                )
                // V27.2: LTH/LTL is preferred. If it is not yet confirmed,
                // fall back to confirmed SH/SL from the SAME source timeframe
                // so BSL/SSL remain visible without importing another timeframe.
                val liquiditySource = if (longTerm.isNotEmpty()) longTerm
                else pivots.filter { it.kind == "SH" || it.kind == "SL" }
                drawIctLiquidityOverlays(canvas, liquiditySource, highest, lowest, left, right, step, candleW, policy)
            }
        }
    }

    private enum class IctStructureRole {
        SHORT_TERM,
        INTERMEDIATE_TERM,
        HIGHER_TERM,
        LONG_TERM
    }

    /**
     * ICT Concept #7 — Dealing Range.
     *
     * A dealing range is formed from the confirmed structural high and low
     * belonging to the currently active ICT hierarchy.  The calculation is
     * deliberately source-timeframe aware: the range is never rebuilt from
     * an unrelated chart timeframe.  Only the current confirmed range is
     * rendered; its midpoint/equilibrium is intentionally left for the next
     * concept in the sequence.
     */
    private fun drawIctDealingRange(
        canvas: Canvas,
        pivots: List<IctMtfPivot>,
        role: IctStructureRole,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float
    ) {
        if (pivots.isEmpty() || candles.isEmpty()) return

        val allowedKinds = when (role) {
            IctStructureRole.SHORT_TERM -> setOf("STH", "STL")
            IctStructureRole.INTERMEDIATE_TERM -> setOf("ITH", "ITL")
            IctStructureRole.HIGHER_TERM -> setOf("SH", "SL")
            IctStructureRole.LONG_TERM -> setOf("LTH", "LTL")
        }

        val confirmed = pivots
            .asSequence()
            .filter { it.kind in allowedKinds }
            .sortedBy { it.sourceTime }
            .toList()
        if (confirmed.size < 2) return

        // Use the latest confirmed high and latest confirmed low from the
        // selected hierarchy. Both are projected onto the locked chart by
        // their original market time and price.
        val rangeHigh = confirmed.lastOrNull { it.high } ?: return
        val rangeLow = confirmed.lastOrNull { !it.high } ?: return
        if (!rangeHigh.price.isFinite() || !rangeLow.price.isFinite()) return
        if (rangeHigh.price <= rangeLow.price) return

        val highIndex = nearestCurrentCandleIndex(rangeHigh.sourceTime)
        val lowIndex = nearestCurrentCandleIndex(rangeLow.sourceTime)
        if (highIndex < 0 || lowIndex < 0) return

        val xStartIndex = min(highIndex, lowIndex)
        val xEndIndex = max(highIndex, lowIndex)
        val x1 = (left + xStartIndex * step + offsetX).coerceIn(left, right)
        val x2 = (left + xEndIndex * step + candleW + offsetX).coerceIn(left, right)
        if (x2 <= x1) return

        val highY = priceToY(rangeHigh.price, highest, lowest)
        val lowY = priceToY(rangeLow.price, highest, lowest)
        if (!highY.isFinite() || !lowY.isFinite()) return

        ictStructureLinePaint.color = Color.argb(190, 255, 195, 70)
        ictStructureLinePaint.style = Paint.Style.STROKE
        ictStructureLinePaint.strokeWidth = 1.35f
        ictStructureLinePaint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        canvas.drawLine(x1, highY, x2, highY, ictStructureLinePaint)
        canvas.drawLine(x1, lowY, x2, lowY, ictStructureLinePaint)
        ictStructureLinePaint.pathEffect = null

        ictStructureTextPaint.color = Color.rgb(255, 210, 105)
        ictStructureTextPaint.textSize = 14f
        ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        ictStructureTextPaint.textAlign = Paint.Align.LEFT
        drawIctLabelCollisionSafe(
            canvas, "DRH", x1 + 4f, highY - 8f,
            ictStructureTextPaint, preferAbove = true
        )
        drawIctLabelCollisionSafe(
            canvas, "DRL", x1 + 4f, lowY + 18f,
            ictStructureTextPaint, preferAbove = false
        )
    }

    /**
     * ICT Concept #8 — Equilibrium / 50%.
     *
     * Equilibrium is the exact 50% midpoint of the currently active dealing
     * range. It is derived from the same confirmed structural high/low used by
     * Concept #7, preserving the source timeframe and range anchors. Only the
     * active range midpoint is rendered; no extra timeframe layers are added.
     */
    private fun drawIctEquilibrium(
        canvas: Canvas,
        pivots: List<IctMtfPivot>,
        role: IctStructureRole,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float
    ) {
        if (pivots.isEmpty() || candles.isEmpty()) return

        val allowedKinds = when (role) {
            IctStructureRole.SHORT_TERM -> setOf("STH", "STL")
            IctStructureRole.INTERMEDIATE_TERM -> setOf("ITH", "ITL")
            IctStructureRole.HIGHER_TERM -> setOf("SH", "SL")
            IctStructureRole.LONG_TERM -> setOf("LTH", "LTL")
        }

        val confirmed = pivots
            .asSequence()
            .filter { it.kind in allowedKinds }
            .sortedBy { it.sourceTime }
            .toList()
        if (confirmed.size < 2) return

        val rangeHigh = confirmed.lastOrNull { it.high } ?: return
        val rangeLow = confirmed.lastOrNull { !it.high } ?: return
        if (!rangeHigh.price.isFinite() || !rangeLow.price.isFinite()) return
        if (rangeHigh.price <= rangeLow.price) return

        val highIndex = nearestCurrentCandleIndex(rangeHigh.sourceTime)
        val lowIndex = nearestCurrentCandleIndex(rangeLow.sourceTime)
        if (highIndex < 0 || lowIndex < 0) return

        val xStartIndex = min(highIndex, lowIndex)
        val xEndIndex = max(highIndex, lowIndex)
        val x1 = (left + xStartIndex * step + offsetX).coerceIn(left, right)
        val x2 = (left + xEndIndex * step + candleW + offsetX).coerceIn(left, right)
        if (x2 <= x1) return

        // Exact arithmetic midpoint: no candle close/open is used.
        val equilibrium = (rangeHigh.price + rangeLow.price) * 0.5f
        if (!equilibrium.isFinite()) return
        val eqY = priceToY(equilibrium, highest, lowest)
        if (!eqY.isFinite()) return

        ictStructureLinePaint.color = Color.argb(205, 255, 235, 120)
        ictStructureLinePaint.style = Paint.Style.STROKE
        ictStructureLinePaint.strokeWidth = 1.15f
        ictStructureLinePaint.pathEffect = DashPathEffect(floatArrayOf(3f, 4f), 0f)
        canvas.drawLine(x1, eqY, x2, eqY, ictStructureLinePaint)
        ictStructureLinePaint.pathEffect = null

        ictStructureTextPaint.color = Color.rgb(255, 230, 135)
        ictStructureTextPaint.textSize = 13f
        ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        ictStructureTextPaint.textAlign = Paint.Align.LEFT
        drawIctLabelCollisionSafe(
            canvas, "EQ 50%", x1 + 4f, eqY - 7f,
            ictStructureTextPaint, preferAbove = true
        )
    }

    /**
     * ICT Concept #9 — Break of Structure (BOS).
     *
     * A bullish BOS is confirmed when a source-timeframe candle CLOSES above
     * a previously confirmed structural swing high. A bearish BOS is the
     * inverse: candle CLOSE below a confirmed structural swing low. A wick
     * through the level alone is deliberately ignored so a liquidity sweep
     * does not become a false BOS.
     */
    private fun drawIctBos(
        canvas: Canvas,
        source: List<IctSourceCandle>,
        pivots: List<IctMtfPivot>,
        role: IctStructureRole,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float
    ) {
        if (source.isEmpty() || pivots.isEmpty() || candles.isEmpty()) return

        val allowedKinds = when (role) {
            IctStructureRole.SHORT_TERM -> setOf("STH", "STL")
            IctStructureRole.INTERMEDIATE_TERM -> setOf("ITH", "ITL")
            IctStructureRole.HIGHER_TERM -> setOf("SH", "SL")
            IctStructureRole.LONG_TERM -> setOf("LTH", "LTL")
        }

        val structural = pivots
            .asSequence()
            .filter { it.kind in allowedKinds }
            .sortedBy { it.sourceIndex }
            .toList()
        if (structural.isEmpty()) return

        data class BosEvent(
            val pivot: IctMtfPivot,
            val breakIndex: Int,
            val bullish: Boolean
        )

        val events = ArrayList<BosEvent>()
        for (pivot in structural) {
            if (pivot.sourceIndex !in source.indices) continue
            var breakIndex = -1
            if (pivot.high) {
                for (j in pivot.sourceIndex + 1 until source.size) {
                    // Close-through confirmation: a wick above the pivot is
                    // not enough to create BOS.
                    if (source[j].close > pivot.price) {
                        breakIndex = j
                        break
                    }
                }
                if (breakIndex >= 0) events.add(BosEvent(pivot, breakIndex, true))
            } else {
                for (j in pivot.sourceIndex + 1 until source.size) {
                    if (source[j].close < pivot.price) {
                        breakIndex = j
                        break
                    }
                }
                if (breakIndex >= 0) events.add(BosEvent(pivot, breakIndex, false))
            }
        }

        // Keep the chart clean: detection retains the full historical set,
        // while rendering shows only recent visible BOS events.
        val visibleIndexRange = visibleCandleIndexRange()
        val visible = events.filter {
            val idx = nearestCurrentCandleIndex(source[it.breakIndex].time)
            idx in visibleIndexRange
        }.takeLast(4)

        for (event in visible) {
            val breakCandle = source[event.breakIndex]
            val pivotIndex = nearestCurrentCandleIndex(event.pivot.sourceTime)
            val breakIndex = nearestCurrentCandleIndex(breakCandle.time)
            if (pivotIndex < 0 || breakIndex < 0) continue

            val px = (left + pivotIndex * step + offsetX + candleW / 2f)
                .coerceIn(left, right)
            val bx = (left + breakIndex * step + offsetX + candleW / 2f)
                .coerceIn(left, right)
            if (abs(bx - px) < 2f) continue

            val y = priceToY(event.pivot.price, highest, lowest)
            if (!y.isFinite() || y < chartTop() - 40f || y > chartBottom() + 40f) continue

            ictStructureLinePaint.color = if (event.bullish) {
                Color.rgb(75, 215, 145)
            } else {
                Color.rgb(255, 105, 125)
            }
            ictStructureLinePaint.style = Paint.Style.STROKE
            ictStructureLinePaint.strokeWidth = 1.5f
            ictStructureLinePaint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            canvas.drawLine(min(px, bx), y, max(px, bx), y, ictStructureLinePaint)
            ictStructureLinePaint.pathEffect = null

            // Small confirmation marker at the actual close-break candle.
            val breakY = priceToY(breakCandle.close, highest, lowest)
            if (breakY.isFinite()) {
                canvas.drawCircle(bx, breakY, 3f, ictStructureLinePaint)
            }

            ictStructureTextPaint.color = ictStructureLinePaint.color
            ictStructureTextPaint.textAlign = Paint.Align.CENTER
            ictStructureTextPaint.textSize = 13f
            ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val labelY = if (event.bullish) y - 9f else y + 18f
            drawIctLabelCollisionSafe(
                canvas,
                if (event.bullish) "BOS ↑" else "BOS ↓",
                (px + bx) * 0.5f,
                labelY,
                ictStructureTextPaint,
                preferAbove = event.bullish
            )
        }
    }

    /**
     * Maps the current chart timeframe to the structural hierarchy that
     * belongs on that chart. This is a display/source-role rule, not a label
     * rename: the selected hierarchy is detected from the current timeframe's
     * candle series using its own structural definition.
     */
    private fun ictVisibleStructureRole(intervalMillis: Long): IctStructureRole {
        val m = 60_000L
        return when {
            intervalMillis <= 15L * m -> IctStructureRole.SHORT_TERM
            intervalMillis <= 60L * m -> IctStructureRole.INTERMEDIATE_TERM
            else -> IctStructureRole.LONG_TERM
        }
    }

    /**
     * ICT timeframe role map. This is a source-timeframe rule, not merely a
     * label-size/visibility rule. The current chart receives one structural lane,
     * while the selected lane is calculated from its actual source timeframe.
     */
    private fun ictCurrentStructureLane(): Int = 1

    /**
     * Returns the next meaningful structural timeframe. This is deliberately
     * relative to the current chart timeframe instead of hard-coding ITH to
     * one universal timeframe.
     */
    /**
     * Exact source-timeframe mapping for the visible ICT hierarchy.
     * The displayed lane is never calculated from a different lane's source.
     * 15m and below: STH/STL role.
     * 1H: ITH/ITL role.
     * 4H and above: LTH/LTL role.
     * SH/SL remains the structural hierarchy used to derive LTH/LTL and is
     * not rendered as a competing lane on every timeframe.
     */
    private fun ictSourceIntervalForRole(
        current: Long,
        role: IctStructureRole
    ): Long {
        // The visible ICT role belongs to the timeframe currently being
        // inspected.  Calculate that role from the real candles of that
        // timeframe instead of borrowing a higher source whose aggregation
        // may not exist in the loaded history.  This is especially important
        // for ITH/ITL on 15m and SH/SL on 4h: the chart must not go empty just
        // because an even higher aggregated source has too little history.
        // Higher-timeframe levels can still be projected later by the setup
        // context/Trade Scan layer when explicitly required.
        return current
    }

    private fun ictNextHigherStructureInterval(current: Long): Long {
        val m = 60_000L
        // Structural ladder used by the ICT MTF layer. These are role/source
        // relationships, not a claim that an ICT concept can never be used
        // on another timeframe. The active setup/context may request another
        // source later; this ladder prevents unrelated structures from being
        // calculated from the same chart series.
        return when {
            current <= 1L * m -> 5L * m
            current <= 3L * m -> 15L * m
            current <= 5L * m -> 15L * m
            current <= 15L * m -> 60L * m
            current <= 30L * m -> 60L * m
            current <= 60L * m -> 4L * 60L * m
            current <= 4L * 60L * m -> 24L * 60L * m
            current <= 24L * 60L * m -> 7L * 24L * 60L * m
            current <= 7L * 24L * 60L * m -> 30L * 24L * 60L * m
            else -> current * 2L
        }
    }

    /** Immediate HTF context required by the current execution timeframe. */
    private fun ictRequiredContextInterval(current: Long): Long {
        val m = 60_000L
        return when {
            current <= 1L * m -> 5L * m
            current <= 3L * m -> 15L * m
            current <= 5L * m -> 15L * m
            current <= 15L * m -> 60L * m
            current <= 60L * m -> 4L * 60L * m
            current <= 4L * 60L * m -> 24L * 60L * m
            current <= 24L * 60L * m -> 7L * 24L * 60L * m
            current <= 7L * 24L * 60L * m -> 30L * 24L * 60L * m
            else -> current
        }
    }

    private fun aggregateIctSourceCandles(intervalMillis: Long): List<IctSourceCandle> {
        ictMtfSourceCache[intervalMillis]?.let { return it }
        if (candles.isEmpty()) return emptyList()

        val sorted = candles.sortedBy { it.time }
        val out = ArrayList<IctSourceCandle>()
        var bucketStart = Long.MIN_VALUE
        var bucket: IctSourceCandle? = null

        for (c in sorted) {
            val start = Math.floorDiv(c.time, intervalMillis) * intervalMillis
            if (start != bucketStart) {
                bucket?.let(out::add)
                bucketStart = start
                bucket = IctSourceCandle(start, c.open, c.high, c.low, c.close)
            } else {
                val b = bucket!!
                bucket = b.copy(
                    high = max(b.high, c.high),
                    low = min(b.low, c.low),
                    close = c.close
                )
            }
        }
        bucket?.let(out::add)
        ictMtfSourceCache[intervalMillis] = out
        return out
    }

    private fun detectIctPivots(source: List<IctSourceCandle>, intervalMillis: Long): List<IctMtfPivot> {
        if (source.size < 5) return emptyList()
        val sth = ArrayList<IctMtfPivot>()
        val stl = ArrayList<IctMtfPivot>()
        val shsl = ArrayList<IctMtfPivot>()

        for (i in 1 until source.size - 1) {
            val c = source[i]
            if (c.high > source[i - 1].high && c.high > source[i + 1].high) {
                sth.add(IctMtfPivot(i, c.time, c.high, true, intervalMillis, "STH"))
            }
            if (c.low < source[i - 1].low && c.low < source[i + 1].low) {
                stl.add(IctMtfPivot(i, c.time, c.low, false, intervalMillis, "STL"))
            }
        }

        for (i in 2 until source.size - 2) {
            val c = source[i]
            if (c.high > source[i - 1].high && c.high >= source[i - 2].high &&
                c.high > source[i + 1].high && c.high >= source[i + 2].high) {
                shsl.add(IctMtfPivot(i, c.time, c.high, true, intervalMillis, "SH"))
            }
            if (c.low < source[i - 1].low && c.low <= source[i - 2].low &&
                c.low < source[i + 1].low && c.low <= source[i + 2].low) {
                shsl.add(IctMtfPivot(i, c.time, c.low, false, intervalMillis, "SL"))
            }
        }

        val ithitl = ArrayList<IctMtfPivot>()
        val highs = sth.sortedBy { it.sourceIndex }
        val lows = stl.sortedBy { it.sourceIndex }
        for (j in 1 until highs.size - 1) {
            val m = highs[j]
            if (m.price > highs[j - 1].price && m.price > highs[j + 1].price) {
                ithitl.add(m.copy(kind = "ITH"))
            }
        }
        for (j in 1 until lows.size - 1) {
            val m = lows[j]
            if (m.price < lows[j - 1].price && m.price < lows[j + 1].price) {
                ithitl.add(m.copy(kind = "ITL"))
            }
        }

        // Long-Term High/Low is derived only from confirmed SH/SL, preserving
        // the hierarchy instead of recalculating LTH/LTL from the raw candles.
        // A long-term high must be higher than adjacent confirmed SH levels;
        // a long-term low must be lower than adjacent confirmed SL levels.
        val lthltl = ArrayList<IctMtfPivot>()
        val majorHighs = shsl.filter { it.high }.sortedBy { it.sourceIndex }
        val majorLows = shsl.filter { !it.high }.sortedBy { it.sourceIndex }
        for (j in 1 until majorHighs.size - 1) {
            val m = majorHighs[j]
            if (m.price > majorHighs[j - 1].price && m.price > majorHighs[j + 1].price) {
                lthltl.add(m.copy(kind = "LTH"))
            }
        }
        for (j in 1 until majorLows.size - 1) {
            val m = majorLows[j]
            if (m.price < majorLows[j - 1].price && m.price < majorLows[j + 1].price) {
                lthltl.add(m.copy(kind = "LTL"))
            }
        }

        // Return confirmed hierarchy levels with explicit source kinds.
        // The renderer selects exactly one hierarchy role for the current
        // chart timeframe; it does not display all levels together.
        return shsl + sth + stl + ithitl + lthltl
    }

    private fun nearestCurrentCandleIndex(sourceTime: Long): Int {
        if (candles.isEmpty()) return -1
        var best = 0
        var bestDistance = Long.MAX_VALUE
        for (i in candles.indices) {
            val d = abs(candles[i].time - sourceTime)
            if (d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    private fun drawIctMtfReferenceSet(
        canvas: Canvas,
        pivots: List<IctMtfPivot>,
        sourceInterval: Long,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float,
        sourceLabel: String = ""
    ) {
        // Only the latest confirmed HTF structure references that intersect the
        // visible chart are projected. This prevents full-HTF-history clutter.
        val visiblePivots = pivots.takeLast(6)
        for (p in visiblePivots) {
            val currentIndex = nearestCurrentCandleIndex(p.sourceTime)
            if (currentIndex < 0) continue
            val x = left + currentIndex * step + offsetX + candleW / 2f
            if (x < left - 100f || x > right + 100f) continue
            val y = priceToY(p.price, highest, lowest)
            if (y < chartTop() - 20f || y > chartBottom() + 20f) continue

            val base = if (p.high) Color.rgb(235, 190, 55) else Color.rgb(80, 190, 235)
            ictStructureLinePaint.color = base
            ictStructureLinePaint.strokeWidth = 1.4f
            ictStructureLinePaint.pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
            val half = 65f
            canvas.drawLine(max(left, x - half), y, min(right, x + half), y, ictStructureLinePaint)
            ictStructureLinePaint.pathEffect = null

            ictStructureTextPaint.color = base
            ictStructureTextPaint.textAlign = Paint.Align.CENTER
            ictStructureTextPaint.textSize = 14f
            ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val sourceName = formatIctTimeframe(sourceInterval)
            val label = if (sourceLabel.isNotEmpty()) {
                if (p.high) "$sourceName $sourceLabel" else "$sourceName ${sourceLabel.replace("ITH", "ITL")}"
            } else {
                if (p.high) "$sourceName SH" else "$sourceName SL"
            }
            canvas.drawText(label, x, if (p.high) y - 10f else y + 18f, ictStructureTextPaint)
        }
    }

    /**
     * Adds HH/HL/LH/LL classification to the currently selected structure lane.
     * Classification is relative to the previous confirmed pivot of the same
     * high/low side, so it does not invent structure from raw candles.
     * The existing STH/ITH/SH/LTH label remains the primary hierarchy label;
     * HH/HL/LH/LL is a secondary structural classification.
     * The selected hierarchy is marked EXT (external structure), while the
     * immediately lower confirmed hierarchy may be rendered as INT (internal
     * structure) only when that context is relevant.
     */
    private fun visibleCandleIndexRange(): IntRange {
        if (candles.isEmpty()) return 1..0
        val left = chartLeft()
        val right = chartRight()
        val step = candleStep().coerceAtLeast(1f)
        val widthOfCandle = candleWidth()
        var first = -1
        var last = -1
        for (i in candles.indices) {
            val x = left + i * step + offsetX
            if (x + widthOfCandle < left || x > right) continue
            if (first < 0) first = i
            last = i
        }
        if (first < 0) {
            // Preserve a valid range even during a transient loading/scroll
            // state; callers will simply render nothing if it is empty.
            return 1..0
        }
        return first..last
    }

    // Visibility rule: all confirmed concepts remain available to the
    // detection/state engine, but the renderer is policy-driven. In clean
    // mode the user sees the primary structure and only a tiny amount of
    // internal context. Setup mode can temporarily reveal classifications
    // and EXT tags when a future setup/context engine requests it.

    /**
     * ICT V27.5 — Buy-Side Liquidity / Sell-Side Liquidity (shared label collision engine).
     *
     * Detection rule:
     * - BSL = confirmed structural swing high price (resting buy-side
     *   liquidity is represented above that high).
     * - SSL = confirmed structural swing low price (resting sell-side
     *   liquidity is represented below that low).
     *
     * Display rule:
     * - Uses only the active chart/setup timeframe's selected structure lane.
     * - V27.3: if that lane is sparse, fallback remains on the same source timeframe.
     * - V27.5: all ICT text labels share one collision registry and resolver.
     * - Clean mode shows the latest confirmed BSL and SSL only.
     * - Setup-context mode may show the latest two of each.
     * - No sweep/raid signal is generated here; this version only marks the
     *   liquidity pool. Sweep/confirmation remains a separate later concept.
     */
    /**
     * V27.5 shared ICT label collision resolver.
     *
     * Keeps the exact 14f font and exact price/line coordinates unchanged.
     * Only the text baseline is repositioned. The resolver first tries the
     * requested position, then nearby vertical lanes, then small horizontal
     * offsets. A candidate is accepted only when its text bounding box does
     * not overlap an already rendered ICT label and remains inside the chart.
     */
    private fun drawIctLabelCollisionSafe(
        canvas: Canvas,
        text: String,
        anchorX: Float,
        preferredY: Float,
        paint: Paint,
        preferAbove: Boolean,
        leftBound: Float = chartLeft() + 4f,
        rightBound: Float = chartRight() - 4f
    ) {
        if (text.isEmpty()) return

        val width = paint.measureText(text)
        val fm = paint.fontMetrics
        val halfW = width / 2f
        val minY = chartTop() + 6f - fm.top
        val maxY = chartBottom() - 6f - fm.bottom
        if (rightBound - leftBound <= width + 8f) return

        // V27.6 GLOBAL LABEL PLACEMENT:
        // The previous resolver searched only a small set of offsets. In a
        // dense ICT area that could still leave labels visually touching.
        // Now every ICT label gets a larger deterministic candidate grid and
        // an explicit minimum gap around every already-placed label.
        val verticalOffsets = if (preferAbove) {
            floatArrayOf(
                0f, -28f, -56f, -84f, -112f, -140f, -168f, -196f, -224f,
                28f, 56f, 84f, 112f, 140f, 168f, 196f, 224f
            )
        } else {
            floatArrayOf(
                0f, 28f, 56f, 84f, 112f, 140f, 168f, 196f, 224f,
                -28f, -56f, -84f, -112f, -140f, -168f, -196f, -224f
            )
        }
        val horizontalOffsets = floatArrayOf(
            0f, -32f, 32f, -64f, 64f, -96f, 96f, -128f, 128f
        )

        fun makeRect(x: Float, baselineY: Float): RectF {
            return RectF(
                x - halfW,
                baselineY + fm.top,
                x + halfW,
                baselineY + fm.bottom
            )
        }

        fun padded(rect: RectF): RectF {
            val r = RectF(rect)
            // Deliberate visual breathing room. This is larger than the
            // previous V27.5 padding so glyphs cannot appear to touch.
            r.inset(-6f, -5f)
            return r
        }

        fun overlapCount(candidate: RectF): Int {
            val c = padded(candidate)
            return ictOccupiedLabelBoxes.count { existing ->
                RectF.intersects(c, padded(existing.rect))
            }
        }

        fun totalDistance(x: Float, y: Float): Float {
            return kotlin.math.abs(x - anchorX) + kotlin.math.abs(y - preferredY)
        }

        var bestRect: RectF? = null
        var bestX = anchorX
        var bestY = preferredY.coerceIn(minY, maxY)
        var bestOverlap = Int.MAX_VALUE
        var bestDistance = Float.MAX_VALUE

        // First priority: zero collision. Second priority: stay close to the
        // requested location. This makes the engine deterministic while still
        // guaranteeing a clean layout whenever chart space permits it.
        for (dx in horizontalOffsets) {
            for (dy in verticalOffsets) {
                val x = (anchorX + dx).coerceIn(leftBound + halfW, rightBound - halfW)
                val y = (preferredY + dy).coerceIn(minY, maxY)
                val rect = makeRect(x, y)
                val overlaps = overlapCount(rect)
                val distance = totalDistance(x, y)
                if (overlaps < bestOverlap ||
                    (overlaps == bestOverlap && distance < bestDistance)
                ) {
                    bestOverlap = overlaps
                    bestDistance = distance
                    bestRect = rect
                    bestX = x
                    bestY = y
                }
                if (overlaps == 0 && distance < 1f) {
                    // Exact requested position is already clean.
                    break
                }
            }
            if (bestOverlap == 0 && bestDistance < 1f) break
        }

        // If the chart is exceptionally dense, search a second wider ring.
        // This is still only text movement; price/line coordinates never move.
        if (bestOverlap > 0) {
            val wideVertical = floatArrayOf(
                -252f, 252f, -280f, 280f, -308f, 308f, -336f, 336f
            )
            for (dy in wideVertical) {
                val y = (preferredY + dy).coerceIn(minY, maxY)
                for (dx in horizontalOffsets) {
                    val x = (anchorX + dx).coerceIn(leftBound + halfW, rightBound - halfW)
                    val rect = makeRect(x, y)
                    val overlaps = overlapCount(rect)
                    if (overlaps == 0) {
                        bestRect = rect
                        bestX = x
                        bestY = y
                        bestOverlap = 0
                        break
                    }
                }
                if (bestOverlap == 0) break
            }
        }

        bestRect?.let {
            ictOccupiedLabelBoxes += IctLabelBox(RectF(it))
            canvas.drawText(text, bestX, bestY, paint)
        }
    }

    private fun drawIctLiquidityOverlays(
        canvas: Canvas,
        pivots: List<IctMtfPivot>,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float,
        policy: IctVisibilityPolicy
    ) {
        if (pivots.isEmpty()) return

        val maxEach = if (ictVisibilityMode == IctVisibilityMode.SETUP_CONTEXT) 2 else 1
        val highs = pivots.asSequence()
            .filter { it.high && it.price.isFinite() }
            .sortedBy { it.sourceTime }
            .toList()
            .takeLast(maxEach)
        val lows = pivots.asSequence()
            .filter { !it.high && it.price.isFinite() }
            .sortedBy { it.sourceTime }
            .toList()
            .takeLast(maxEach)

        // V27.4 label-collision rule:
        // Liquidity labels use dedicated vertical lanes outside the primary
        // structure/INT/EXT/classification lanes. The price line itself stays
        // at the exact liquidity price; only the text is repositioned.
        // This preserves the 14f font while preventing BSL/SSL from sitting
        // directly on top of structure labels.
        fun drawLevel(p: IctMtfPivot, label: String, isBuySide: Boolean, laneIndex: Int) {
            val index = nearestCurrentCandleIndex(p.sourceTime)
            if (index < 0 || index >= candles.size) return

            val x = left + index * step + offsetX + candleW / 2f
            if (x < left - 120f || x > right + 120f) return

            val y = priceToY(p.price, highest, lowest)
            if (y < chartTop() - 30f || y > chartBottom() + 30f) return

            ictLiquidityLinePaint.color = if (isBuySide) {
                Color.rgb(255, 165, 70)
            } else {
                Color.rgb(80, 205, 235)
            }
            ictLiquidityLinePaint.strokeWidth = 1.5f
            ictLiquidityLinePaint.pathEffect = DashPathEffect(floatArrayOf(7f, 5f), 0f)
            canvas.drawLine(
                max(left, x - 135f),
                y,
                min(right, x + 135f),
                y,
                ictLiquidityLinePaint
            )
            ictLiquidityLinePaint.pathEffect = null

            ictLiquidityTextPaint.color = ictLiquidityLinePaint.color
            ictLiquidityTextPaint.textAlign = Paint.Align.CENTER
            ictLiquidityTextPaint.textSize = 14f
            ictLiquidityTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            // High-side structure currently occupies roughly y-10, y-28 and
            // y-44. Low-side structure occupies roughly y+18, y+26, y+36 and
            // y+52. Keep liquidity labels in their own lanes with additional
            // spacing for setup-context mode when two levels are visible.
            val laneStep = 22f
            val preferredLabelY = if (isBuySide) {
                y - 68f - laneIndex * laneStep
            } else {
                y + 74f + laneIndex * laneStep
            }

            // Keep the label inside the chart whenever possible. If the
            // preferred lane reaches an edge, move it toward the available
            // interior without moving the liquidity price line.
            val top = chartTop() + 14f
            val bottom = chartBottom() - 8f
            val labelY = preferredLabelY.coerceIn(top, bottom)

            drawIctLabelCollisionSafe(
                canvas, label, x, labelY, ictLiquidityTextPaint,
                preferAbove = isBuySide
            )
        }

        // Render oldest-to-newest. Each same-side liquidity label gets its own
        // lane so setup-context mode cannot stack BSL/BSL or SSL/SSL text.
        highs.forEachIndexed { lane, p -> drawLevel(p, "BSL", true, lane) }
        lows.forEachIndexed { lane, p -> drawLevel(p, "SSL", false, lane) }
    }

    private fun drawIctMtfPivotSetWithClassification(
        canvas: Canvas,
        pivots: List<IctMtfPivot>,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float,
        lane: Int,
        internalPivots: List<IctMtfPivot>,
        policy: IctVisibilityPolicy
    ) {
        if (pivots.isEmpty()) return

        // Internal/External structure is a hierarchy relationship, not a new
        // candle pattern. The selected lane is the external structure for the
        // active context; the immediately lower confirmed hierarchy is internal.
        // This keeps the chart clean and prevents every timeframe from being
        // filled with duplicate structure labels.
        if (policy.showInternalStructure && internalPivots.isNotEmpty()) {
            // Verification/anti-clutter rule: show only the most recent
            // confirmed internal pivots that are actually visible. The full
            // internal hierarchy is still calculated; rendering is limited so
            // INT labels do not cover the chart or duplicate every lower pivot.
            // Use the actual visible candle-index window. The previous
            // version compared a candle index with price values (highest/lowest),
            // which made INT selection unreliable. Keep only the most recent
            // visible internal pivots to preserve the clean chart rule.
            val visibleIndexRange = visibleCandleIndexRange()
            val internalOrdered = internalPivots
                .sortedBy { it.sourceTime }
                .filter {
                    val idx = nearestCurrentCandleIndex(it.sourceTime)
                    idx in visibleIndexRange
                }
                .takeLast(policy.maxInternalLabels)
            for (p in internalOrdered) {
                val index = nearestCurrentCandleIndex(p.sourceTime)
                if (index < 0 || index >= candles.size) continue
                val x = left + index * step + offsetX + candleW / 2f
                if (x < left - 100f || x > right + 100f) continue
                val y = priceToY(p.price, highest, lowest)
                if (y < chartTop() - 30f || y > chartBottom() + 30f) continue

                ictStructureLinePaint.color = Color.argb(185, 180, 180, 180)
                ictStructureLinePaint.strokeWidth = 1.15f
                ictStructureLinePaint.pathEffect = DashPathEffect(floatArrayOf(3f, 4f), 0f)
                canvas.drawLine(max(left, x - 45f), y, min(right, x + 45f), y, ictStructureLinePaint)
                ictStructureLinePaint.pathEffect = null

                ictStructureTextPaint.color = Color.LTGRAY
                ictStructureTextPaint.textAlign = Paint.Align.CENTER
                ictStructureTextPaint.textSize = 14f
                ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                drawIctLabelCollisionSafe(
                    canvas, "INT", x, if (p.high) y - 20f else y + 26f,
                    ictStructureTextPaint, preferAbove = p.high
                )
            }
        }

        val ordered = pivots.sortedBy { it.sourceTime }
        val visibleIndexRange = visibleCandleIndexRange()
        val visibleOrdered = ordered.filter {
            val idx = nearestCurrentCandleIndex(it.sourceTime)
            idx in visibleIndexRange
        }
        // Primary structure remains visible, but only the most recent confirmed
        // visible pivots receive labels/levels. Older structure stays in the
        // detection engine instead of turning the chart into a wall of text.
        val primaryVisibleTimes = visibleOrdered
            .takeLast(policy.maxPrimaryLabels)
            .map { it.sourceTime }
            .toSet()
        val classificationVisibleTimes = if (policy.showStructureClassification) {
            visibleOrdered.takeLast(policy.maxClassificationLabels).map { it.sourceTime }.toSet()
        } else emptySet()

        var lastHigh: IctMtfPivot? = null
        var lastLow: IctMtfPivot? = null

        for (p in ordered) {
            val classification = if (p.high) {
                val prev = lastHigh
                lastHigh = p
                if (prev == null) null else if (p.price > prev.price) "HH" else if (p.price < prev.price) "LH" else null
            } else {
                val prev = lastLow
                lastLow = p
                if (prev == null) null else if (p.price > prev.price) "HL" else if (p.price < prev.price) "LL" else null
            }

            if (p.sourceTime !in primaryVisibleTimes) continue
            val index = nearestCurrentCandleIndex(p.sourceTime)
            if (index < 0 || index >= candles.size - 2) continue
            val x = left + index * step + offsetX + candleW / 2f
            if (x < left - 120f || x > right + 120f) continue
            val y = priceToY(p.price, highest, lowest)
            if (y < chartTop() - 30f || y > chartBottom() + 30f) continue

            val base = when (p.kind) {
                "STH" -> Color.rgb(205, 170, 255)
                "STL" -> Color.rgb(255, 105, 125)
                "ITH", "ITL" -> Color.rgb(70, 225, 235)
                "SH", "SL" -> if (p.high) Color.rgb(235, 190, 55) else Color.rgb(80, 190, 235)
                "LTH", "LTL" -> Color.rgb(255, 195, 70)
                else -> Color.WHITE
            }
            val half = when (lane) { 0 -> 60f; 1 -> 70f; 2 -> 82f; else -> 95f }
            ictStructureLinePaint.color = base
            ictStructureLinePaint.strokeWidth = when (lane) { 0 -> 1.6f; 1 -> 1.8f; 2 -> 2.0f; else -> 2.2f }
            ictStructureLinePaint.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
            canvas.drawLine(max(left, x - half), y, min(right, x + half), y, ictStructureLinePaint)
            ictStructureLinePaint.pathEffect = null
            canvas.drawCircle(x, y, 3.5f, ictStructureLinePaint)

            ictStructureTextPaint.textAlign = Paint.Align.CENTER
            ictStructureTextPaint.textSize = 14f
            ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            ictStructureTextPaint.color = base
            ictStructureTextPaint.isFakeBoldText = true
            if (policy.showPrimaryStructure) {
                drawIctLabelCollisionSafe(
                    canvas, p.kind, x, if (p.high) y - 10f else y + 18f,
                    ictStructureTextPaint, preferAbove = p.high
                )
            }

            // Selected hierarchy is the external structure for the active context.
            if (policy.showExternalTag) {
                ictStructureTextPaint.color = Color.WHITE
                ictStructureTextPaint.textSize = 14f
                val extY = if (p.high) y - 28f else y + 36f
                drawIctLabelCollisionSafe(
                    canvas, "EXT", x, extY, ictStructureTextPaint,
                    preferAbove = p.high
                )
            }

            if (policy.showStructureClassification && p.sourceTime in classificationVisibleTimes && classification != null) {
                ictStructureTextPaint.color = Color.WHITE
                val classY = if (p.high) y - 44f else y + 52f
                drawIctLabelCollisionSafe(
                    canvas, classification, x, classY, ictStructureTextPaint,
                    preferAbove = p.high
                )
            }
        }
    }

    private fun drawIctMtfPivotSet(
        canvas: Canvas,
        pivots: List<IctMtfPivot>,
        label: String?,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float,
        lane: Int,
        reference: Boolean
    ) {
        if (pivots.isEmpty()) return
        for (p in pivots) {
            val index = nearestCurrentCandleIndex(p.sourceTime)
            if (index < 0 || index >= candles.size - 2) continue
            val x = left + index * step + offsetX + candleW / 2f
            if (x < left - 120f || x > right + 120f) continue
            val y = priceToY(p.price, highest, lowest)
            if (y < chartTop() - 30f || y > chartBottom() + 30f) continue

            val actualLabel = label ?: p.kind
            val base = when (actualLabel) {
                "SH" -> Color.rgb(235, 190, 55)
                "SL" -> Color.rgb(80, 190, 235)
                "STH" -> Color.rgb(205, 170, 255)
                "STL" -> Color.rgb(255, 105, 125)
                "ITH", "ITL" -> Color.rgb(70, 225, 235)
                "LTH", "LTL" -> Color.rgb(255, 195, 70)
                else -> Color.WHITE
            }
            val half = when (lane) { 0 -> 60f; 1 -> 70f; 2 -> 82f; else -> 95f }
            ictStructureLinePaint.color = base
            ictStructureLinePaint.strokeWidth = when (lane) { 0 -> 1.6f; 1 -> 1.8f; 2 -> 2.0f; else -> 2.2f }
            ictStructureLinePaint.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
            canvas.drawLine(max(left, x - half), y, min(right, x + half), y, ictStructureLinePaint)
            ictStructureLinePaint.pathEffect = null
            canvas.drawCircle(x, y, 3.5f, ictStructureLinePaint)

            ictStructureTextPaint.color = base
            ictStructureTextPaint.textAlign = Paint.Align.CENTER
            ictStructureTextPaint.textSize = 14f
            ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(actualLabel, x, if (p.high) y - 10f else y + 18f, ictStructureTextPaint)
        }
    }

    private fun formatIctTimeframe(intervalMillis: Long): String {
        val m = intervalMillis / 60_000L
        return when {
            m < 60 -> "${m}m"
            m % (24 * 60) == 0L -> "${m / (24 * 60)}D"
            m % 60 == 0L -> "${m / 60}H"
            else -> "${m}m"
        }
    }

    private fun drawIctPivotSet(
        canvas: Canvas,
        pivots: List<IctPivot>,
        label: String,
        highest: Float,
        lowest: Float,
        left: Float,
        right: Float,
        step: Float,
        candleW: Float,
        lane: Int
    ) {
        if (pivots.isEmpty()) return

        // Only draw pivots whose candle is actually in the visible horizontal window.
        // Keep a small off-screen margin so labels/levels do not pop in and out at the edge.
        val visible = pivots.filter { p ->
            val x = left + p.index * step + offsetX + candleW / 2f
            x >= left - 120f && x <= right + 120f
        }

        for (p in visible) {
            if (p.index >= candles.size - 2) continue

            val x = left + p.index * step + offsetX + candleW / 2f
            val y = priceToY(p.price, highest, lowest)
            if (y < chartTop() - 30f || y > chartBottom() + 30f) continue

            val isHigh = p.high
            val base = when (label) {
                "SH" -> Color.rgb(235, 190, 55)
                "SL" -> Color.rgb(80, 190, 235)
                "STH" -> Color.rgb(205, 170, 255)
                "STL" -> Color.rgb(255, 105, 125)
                "ITH" -> Color.rgb(70, 225, 235)
                "ITL" -> Color.rgb(70, 225, 235)
                "LTH" -> Color.rgb(255, 195, 70)
                "LTL" -> Color.rgb(255, 195, 70)
                else -> Color.WHITE
            }

            // Reference-style horizontal structure level. Keep levels short to reduce clutter,
            // while remaining attached to the source pivot candle.
            val lineHalf = when (lane) {
                0 -> 85f
                1 -> 105f
                else -> 125f
            }
            val drawY = when (lane) {
                0 -> y
                1 -> if (isHigh) y - 3f else y + 3f
                else -> if (isHigh) y - 6f else y + 6f
            }

            ictStructureLinePaint.color = base
            ictStructureLinePaint.strokeWidth = when (lane) { 0 -> 1.8f; 1 -> 2.1f; else -> 2.4f }
            ictStructureLinePaint.pathEffect = DashPathEffect(
                when (lane) {
                    0 -> floatArrayOf(3f, 3f)
                    1 -> floatArrayOf(6f, 4f)
                    else -> floatArrayOf(8f, 4f)
                },
                0f
            )

            val startX = max(left, x - lineHalf)
            val endX = min(right, x + lineHalf)
            canvas.drawLine(startX, drawY, endX, drawY, ictStructureLinePaint)
            ictStructureLinePaint.pathEffect = null

            // Small pivot marker exactly at the source candle.
            canvas.drawCircle(x, y, when (lane) { 0 -> 4.0f; 1 -> 4.5f; else -> 5.0f }, ictStructureLinePaint)

            ictStructureTextPaint.color = base
            ictStructureTextPaint.textAlign = Paint.Align.CENTER
            ictStructureTextPaint.textSize = 14f
            ictStructureTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val labelY = if (isHigh) drawY - 13f else drawY + 22f
            canvas.drawText(label, x, labelY, ictStructureTextPaint)
        }
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

        // The live marker is anchored to the ACTUAL forming candle.
        // The countdown keeps its existing independent refresh behavior, while
        // the price line/label follows the same real-tick candle close.
        val livePrice = candle.close

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
        val remaining = if (frozenCountdownMode) {
            // Closed-market/weekend: use the exact persisted snapshot.
            // Never recompute from the current app-open/device time.
            livePriceCountdownMillis.coerceAtLeast(0L)
        } else {
            // OPEN MARKET: the countdown is derived only from the wall-clock
            // timeframe boundary. Provider ticks are never allowed to hold,
            // reset, or clamp the countdown, so delayed WebSocket ticks cannot
            // make the timer look buffered/frozen.
            val nextBoundary = ((liveNowMillis / interval) + 1L) * interval
            (nextBoundary - liveNowMillis).coerceAtLeast(0L)
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

        // The live candle must move together with the countdown.  We use the
        // REAL wall-clock position inside the current timeframe as a fractional
        // candle position.  This moves the chart smoothly without generating
        // any synthetic/demo price or changing the candle OHLC.
        //
        // Example: at 50% of a 5-minute candle, the live position is
        // lastIndex + 0.50.  When the next real candle arrives, its index is
        // lastIndex + 1 and its fractional position resets to 0.00, so the
        // visual position is continuous and there is no jump.
        val clockTime = when {
            liveTickTimeMillis > 0L -> maxOf(liveNowMillis, liveTickTimeMillis)
            else -> liveNowMillis
        }

        val elapsedMillis = if (marketOpen && !isWeekendClosedNow()) {
            (clockTime - lastTime).coerceIn(0L, interval)
        } else {
            0L
        }
        val liveFraction =
            elapsedMillis.toFloat() / interval.toFloat()

        val virtualLivePos = lastIndex.toFloat() + liveFraction
        val latestX =
            chartLeft() +
            virtualLivePos * candleStep() +
            candleWidth()

        // Keep the virtual live position at the same right-side anchor.
        // Because virtualLivePos advances with the countdown, every visible
        // candle moves in sync with it once per live-clock refresh.
        val rightAnchor = chartRight() - 8f
        offsetX = rightAnchor - latestX
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
            val p = anchorLineToScreen(line)
            canvas.drawLine(p.x1, p.y1, p.x2, p.y2, drawingPaint)
        }

        drawingPaint.color = Color.rgb(120, 165, 255)
        for (line in rayLines) {
            drawRay(canvas, anchorLineToScreen(line), drawingPaint)
        }

        drawingPaint.color = Color.rgb(235, 190, 55)
        for (price in horizontalLines) {
            val y = priceToScreenY(price)
            canvas.drawLine(chartLeft(), y, chartRight(), y, drawingPaint)
        }

        drawingPaint.color = Color.rgb(235, 160, 80)
        for (pos in verticalLines) {
            val x = candlePosToScreenX(timeToCandlePos(pos))
            canvas.drawLine(x, chartTop(), x, chartBottom(), drawingPaint)
        }

        drawingPaint.color = Color.rgb(55, 180, 235)
        for (rect in rectangles) {
            val r = anchorRectToScreen(rect)
            canvas.drawRect(r.left, r.top, r.right, r.bottom, drawingPaint)
        }

        drawingPaint.color = Color.rgb(120, 210, 180)
        for (circle in circles) {
            val r = anchorRectToScreen(circle)
            canvas.drawOval(r.left, r.top, r.right, r.bottom, drawingPaint)
        }

        drawingPaint.color = Color.rgb(255, 150, 70)
        for (line in arrows) {
            drawArrow(canvas, anchorLineToScreen(line), drawingPaint)
        }

        drawingPaint.color = Color.rgb(180, 120, 240)
        for (line in parallelChannels) {
            val p = anchorLineToScreen(line)
            canvas.drawLine(p.x1, p.y1, p.x2, p.y2, drawingPaint)
            val dx = p.x2 - p.x1
            val dy = p.y2 - p.y1
            val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val nx = -dy / length * 24f
            val ny = dx / length * 24f
            canvas.drawLine(p.x1 + nx, p.y1 + ny, p.x2 + nx, p.y2 + ny, drawingPaint)
        }

        drawingPaint.color = Color.rgb(80, 210, 255)
        for (line in fibonacciLines) {
            drawFibonacci(canvas, anchorLineToScreen(line), drawingPaint)
        }

        drawingPaint.color = Color.rgb(235, 235, 235)
        for (stroke in brushStrokes) {
            for (i in 1 until stroke.size) {
                val a = stroke[i - 1]
                val b = stroke[i]
                canvas.drawLine(
                    candlePosToScreenX(timeToCandlePos(a.time)), priceToScreenY(a.price),
                    candlePosToScreenX(timeToCandlePos(b.time)), priceToScreenY(b.price),
                    drawingPaint
                )
            }
        }

        drawingPaint.color = Color.rgb(190, 120, 235)
        for (measure in measurements) {
            val m = anchorMeasureToScreen(measure)
            canvas.drawLine(m.x1, m.y1, m.x2, m.y2, drawingPaint)
            drawMeasureLabel(canvas, m.x1, m.y1, m.x2, m.y2)
        }

        labelPaint.color = Color.WHITE
        labelPaint.textSize = 13f
        labelPaint.textAlign = Paint.Align.LEFT
        for (point in textMarkers) {
            canvas.drawText("TEXT", candlePosToScreenX(timeToCandlePos(point.time)), priceToScreenY(point.price), labelPaint)
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

                if (isInsideHistoryLiveButton(event.x, event.y)) {
                    liveJumpPressed = true
                    resumeLiveFollow()
                    liveJumpPressed = false
                    invalidate()
                    return true
                }

                // Price scale = vertical zoom only.
                priceScaleDragging = event.x >= chartRight()
                if (priceScaleDragging) {
                    followLive = false
                    manualPanCandidate = false
                    lastPanY = event.y
                    invalidate()
                    return true
                }

                // Time scale = horizontal zoom only.
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
                    // CROSSHAIR RULE:
                    // A line being visible or hidden NEVER controls chart pan.
                    // DOWN only starts a normal chart-pan candidate.
                    // A simple TAP on ACTION_UP places/moves the two level lines.
                    // Any DRAG, with or without lines, moves the chart normally.
                    fingerDown = true
                    dragging = true
                    panGestureStarted = false
                    manualPanCandidate = true
                    crosshairCreatedThisGesture = false
                    crosshairDragMode = CrosshairDragMode.NONE
                    crosshairDownX = event.x
                    crosshairDownY = event.y
                    lastX = event.x
                    lastY = event.y
                    followLive = false

                    // No hold timer: levels are requested by tap, not by holding.
                    crosshairHoldPending = false
                    crosshairHoldRunnable?.let { removeCallbacks(it) }
                    crosshairHoldRunnable = null
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (scaleDetector.isInProgress || scaleInProgress) {
                    invalidate()
                    return true
                }

                // CROSSHAIR MODE: regardless of whether the two level lines are
                // visible or hidden, every one-finger drag pans the chart.
                if (activeTool == DrawingTool.CROSSHAIR && fingerDown && dragging) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    val totalDx = event.x - crosshairDownX
                    val totalDy = event.y - crosshairDownY

                    if (kotlin.math.hypot(totalDx.toDouble(), totalDy.toDouble()) > 1.0) {
                        panGestureStarted = true
                        manualPanCandidate = false
                        followLive = false

                        offsetX += dx
                        pricePan += screenDyToPricePan(dy)
                        clampFreePan()

                        // IMPORTANT: drawings are anchored to the chart transform.
                        // Do NOT translate their stored screen coordinates here.
                        // syncDrawingsToChartTransform() converts them from the old
                        // candle/price mapping to the new mapping on the next draw.
                        // Translating here as well would move drawings twice and make
                        // them drift away from their candle/price.

                        lastX = event.x
                        lastY = event.y
                    }

                    invalidate()
                    return true
                }

                if (priceScaleDragging) {
                    val dy = event.y - lastPanY
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

                if (activeTool == DrawingTool.CROSSHAIR) {
                    val moved = kotlin.math.abs(event.x - crosshairDownX) > 6f ||
                        kotlin.math.abs(event.y - crosshairDownY) > 6f

                    // TAP toggles the two level lines. A drag ALWAYS remains
                    // a normal one-finger chart pan, whether the levels are
                    // visible or hidden.
                    if (!moved) {
                        if (crosshairVisible) {
                            crosshairVisible = false
                            crossX = -1f
                            crossY = -1f
                        } else {
                            crossX = event.x.coerceIn(chartLeft(), chartRight())
                            crossY = event.y.coerceIn(chartTop(), chartBottom())
                            crosshairVisible = true
                        }
                    }

                    dragging = false
                    panGestureStarted = false
                    manualPanCandidate = false
                    crosshairDragMode = CrosshairDragMode.NONE
                    crosshairCreatedThisGesture = false
                    invalidate()
                    post {
                        if (isAttachedToWindow) {
                            saveDrawings()
                        }
                    }
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
                    saveDrawings()
                    invalidate()
                    return true
                }

                if (activeTool != DrawingTool.CROSSHAIR &&
                    startX >= 0f && startY >= 0f
                ) {
                    val endX = event.x
                    val endY = event.y

                    when (activeTool) {
                        DrawingTool.TREND_LINE -> {
                            val a = screenPointToTimedAnchor(startX, startY)
                            val b = screenPointToTimedAnchor(endX, endY)
                            trendLines.add(LineData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.RAY -> {
                            val a = screenPointToTimedAnchor(startX, startY)
                            val b = screenPointToTimedAnchor(endX, endY)
                            rayLines.add(LineData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.HORIZONTAL_LINE ->
                            horizontalLines.add(screenToPrice(endY))

                        DrawingTool.VERTICAL_LINE ->
                            verticalLines.add(candlePosToTime(screenToCandlePos(endX)))

                        DrawingTool.RECTANGLE -> {
                            val a = screenPointToTimedAnchor(min(startX, endX), min(startY, endY))
                            val b = screenPointToTimedAnchor(max(startX, endX), max(startY, endY))
                            rectangles.add(RectData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.CIRCLE -> {
                            val a = screenPointToTimedAnchor(min(startX, endX), min(startY, endY))
                            val b = screenPointToTimedAnchor(max(startX, endX), max(startY, endY))
                            circles.add(RectData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.ARROW -> {
                            val a = screenPointToTimedAnchor(startX, startY)
                            val b = screenPointToTimedAnchor(endX, endY)
                            arrows.add(LineData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.PARALLEL_CHANNEL -> {
                            val a = screenPointToTimedAnchor(startX, startY)
                            val b = screenPointToTimedAnchor(endX, endY)
                            parallelChannels.add(LineData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.FIBONACCI -> {
                            val a = screenPointToTimedAnchor(startX, startY)
                            val b = screenPointToTimedAnchor(endX, endY)
                            fibonacciLines.add(LineData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.MEASURE -> {
                            val a = screenPointToTimedAnchor(startX, startY)
                            val b = screenPointToTimedAnchor(endX, endY)
                            measurements.add(MeasureData(timeToCandlePos(a.time), a.price, timeToCandlePos(b.time), b.price, a.time, b.time))
                        }

                        DrawingTool.BRUSH -> {
                            if (activeBrush.size > 1) {
                                brushStrokes.add(activeBrush.map { screenPointToTimedAnchor(it.first, it.second) })
                            }
                            activeBrush.clear()
                        }

                        DrawingTool.TEXT -> {
                            textMarkers.add(screenPointToTimedAnchor(endX, endY))
                        }

                        DrawingTool.CROSSHAIR -> Unit
                    }

                    startX = -1f
                    startY = -1f
                    currentX = -1f
                    currentY = -1f
                    activeTool = DrawingTool.CROSSHAIR
                    saveDrawings()
                } else {
                    dragging = false
                    manualPanCandidate = false
                }

                dragging = false
                panGestureStarted = false
                manualPanCandidate = false
                saveDrawings()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
                crosshairHoldPending = false
                crosshairHoldRunnable?.let { removeCallbacks(it) }
                crosshairHoldRunnable = null
                priceScaleDragging = false
                timeScaleDragging = false
                dragging = false
                panGestureStarted = false
                manualPanCandidate = false
                crosshairDragMode = CrosshairDragMode.NONE
                saveDrawings()
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

    // Drawings are now stored as candle/time + price anchors. Chart pan/zoom
    // changes only the projection, so there is intentionally no per-frame
    // mutation/translation of stored drawings. This prevents drift and the
    // double-movement bug that occurred with screen-space drawings.
    private fun syncDrawingsToChartTransform() { }

    private fun translateDrawings(dx: Float, dy: Float) {
        // Intentionally unused. Chart panning changes the chart transform;
        // anchored drawings follow automatically during rendering.
    }

    private fun scaleDrawings(sx: Float, sy: Float, fx: Float, fy: Float) {
        // Intentionally unused. Chart zoom changes the chart transform;
        // anchored drawings follow automatically during rendering.
    }

    private fun saveDrawings() {
        try {
            val root = JSONObject()
            root.put("version", 3)
            root.put("width", width)
            root.put("height", height)

            fun lineArray(list: List<LineData>): JSONArray {
                val a = JSONArray()
                list.forEach { l ->
                    a.put(JSONArray().put(l.x1).put(l.y1).put(l.x2).put(l.y2).put(l.t1).put(l.t2))
                }
                return a
            }
            fun rectArray(list: List<RectData>): JSONArray {
                val a = JSONArray()
                list.forEach { r ->
                    a.put(JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom).put(r.tLeft).put(r.tRight))
                }
                return a
            }
            fun measureArray(list: List<MeasureData>): JSONArray {
                val a = JSONArray()
                list.forEach { m ->
                    a.put(JSONArray().put(m.x1).put(m.y1).put(m.x2).put(m.y2).put(m.t1).put(m.t2))
                }
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
            root.put("text", JSONArray().apply {
                textMarkers.forEach { put(JSONArray().put(it.time).put(it.price)) }
            })
            val brushes = JSONArray()
            brushStrokes.forEach { stroke ->
                brushes.put(JSONArray().apply {
                    stroke.forEach { put(JSONArray().put(it.time).put(it.price)) }
                })
            }
            root.put("brush", brushes)
            drawingPrefs.edit().putString("state", root.toString()).apply()
        } catch (_: Exception) { }
    }

    /**
     * Converts the previous v2 drawing format (candle-position + price) into
     * immutable timestamp + price anchors. This is the key stability fix:
     * adding/removing/prepending candles can no longer move an existing drawing.
     */
    private fun convertV2DrawingsToTimeAnchors() {
        if (candles.isEmpty()) return

        fun line(l: LineData): LineData {
            val t1 = if (l.t1 != 0L) l.t1 else candlePosToTime(l.x1)
            val t2 = if (l.t2 != 0L) l.t2 else candlePosToTime(l.x2)
            return LineData(timeToCandlePos(t1), l.y1, timeToCandlePos(t2), l.y2, t1, t2)
        }
        fun rect(r: RectData): RectData {
            val t1 = if (r.tLeft != 0L) r.tLeft else candlePosToTime(r.left)
            val t2 = if (r.tRight != 0L) r.tRight else candlePosToTime(r.right)
            return RectData(timeToCandlePos(t1), r.top, timeToCandlePos(t2), r.bottom, t1, t2)
        }
        fun measure(m: MeasureData): MeasureData {
            val t1 = if (m.t1 != 0L) m.t1 else candlePosToTime(m.x1)
            val t2 = if (m.t2 != 0L) m.t2 else candlePosToTime(m.x2)
            return MeasureData(timeToCandlePos(t1), m.y1, timeToCandlePos(t2), m.y2, t1, t2)
        }

        for (i in trendLines.indices) trendLines[i] = line(trendLines[i])
        for (i in rayLines.indices) rayLines[i] = line(rayLines[i])
        for (i in arrows.indices) arrows[i] = line(arrows[i])
        for (i in parallelChannels.indices) parallelChannels[i] = line(parallelChannels[i])
        for (i in fibonacciLines.indices) fibonacciLines[i] = line(fibonacciLines[i])
        for (i in measurements.indices) measurements[i] = measure(measurements[i])
        for (i in rectangles.indices) rectangles[i] = rect(rectangles[i])
        for (i in circles.indices) circles[i] = rect(circles[i])

        // Vertical lines, text markers and brush points are already converted
        // while loading v2 data above.
        saveDrawings()
    }

    private fun loadDrawings() {
        if (drawingsLoaded || width <= 0 || height <= 0) return
        drawingsLoaded = true
        lastLoadedWidth = width
        lastLoadedHeight = height
        try {
            val raw = drawingPrefs.getString("state", null) ?: return
            val root = JSONObject(raw)
            val version = root.optInt("version", 1)

            fun readLines(key: String, target: MutableList<LineData>) {
                val a = root.optJSONArray(key) ?: return
                for (i in 0 until a.length()) {
                    val v = a.optJSONArray(i) ?: continue
                    if (v.length() >= 4) {
                        val x1 = v.optDouble(0).toFloat()
                        val y1 = v.optDouble(1).toFloat()
                        val x2 = v.optDouble(2).toFloat()
                        val y2 = v.optDouble(3).toFloat()
                        val t1 = if (version >= 3 && v.length() >= 6) v.optLong(4) else 0L
                        val t2 = if (version >= 3 && v.length() >= 6) v.optLong(5) else 0L
                        target.add(LineData(x1, y1, x2, y2, t1, t2))
                    }
                }
            }
            fun readRects(key: String, target: MutableList<RectData>) {
                val a = root.optJSONArray(key) ?: return
                for (i in 0 until a.length()) {
                    val v = a.optJSONArray(i) ?: continue
                    if (v.length() >= 4) {
                        val left = v.optDouble(0).toFloat()
                        val top = v.optDouble(1).toFloat()
                        val right = v.optDouble(2).toFloat()
                        val bottom = v.optDouble(3).toFloat()
                        val t1 = if (version >= 3 && v.length() >= 6) v.optLong(4) else 0L
                        val t2 = if (version >= 3 && v.length() >= 6) v.optLong(5) else 0L
                        target.add(RectData(left, top, right, bottom, t1, t2))
                    }
                }
            }
            fun readMeasures(key: String, target: MutableList<MeasureData>) {
                val a = root.optJSONArray(key) ?: return
                for (i in 0 until a.length()) {
                    val v = a.optJSONArray(i) ?: continue
                    if (v.length() >= 4) {
                        val x1 = v.optDouble(0).toFloat()
                        val y1 = v.optDouble(1).toFloat()
                        val x2 = v.optDouble(2).toFloat()
                        val y2 = v.optDouble(3).toFloat()
                        val t1 = if (version >= 3 && v.length() >= 6) v.optLong(4) else 0L
                        val t2 = if (version >= 3 && v.length() >= 6) v.optLong(5) else 0L
                        target.add(MeasureData(x1, y1, x2, y2, t1, t2))
                    }
                }
            }

            readLines("trend", trendLines)
            readLines("ray", rayLines)
            readLines("arrow", arrows)
            readLines("channel", parallelChannels)
            readLines("fib", fibonacciLines)
            readMeasures("measure", measurements)
            readRects("rect", rectangles)
            readRects("circle", circles)

            root.optJSONArray("h")?.let { a ->
                for (i in 0 until a.length()) horizontalLines.add(a.optDouble(i).toFloat())
            }

            root.optJSONArray("v")?.let { a ->
                for (i in 0 until a.length()) {
                    verticalLines.add(if (version >= 3) a.optLong(i) else a.optDouble(i).toFloat().let { candlePosToTime(it) })
                }
            }

            root.optJSONArray("text")?.let { a ->
                for (i in 0 until a.length()) {
                    val v = a.optJSONArray(i) ?: continue
                    if (v.length() >= 2) {
                        if (version >= 3) {
                            textMarkers.add(AnchorPoint(v.optLong(0), v.optDouble(1).toFloat()))
                        } else {
                            textMarkers.add(AnchorPoint(candlePosToTime(v.optDouble(0).toFloat()), v.optDouble(1).toFloat()))
                        }
                    }
                }
            }

            root.optJSONArray("brush")?.let { a ->
                for (i in 0 until a.length()) {
                    val st = a.optJSONArray(i) ?: continue
                    val out = mutableListOf<AnchorPoint>()
                    for (j in 0 until st.length()) {
                        val v = st.optJSONArray(j) ?: continue
                        if (v.length() >= 2) {
                            if (version >= 3) {
                                out.add(AnchorPoint(v.optLong(0), v.optDouble(1).toFloat()))
                            } else {
                                out.add(AnchorPoint(candlePosToTime(v.optDouble(0).toFloat()), v.optDouble(1).toFloat()))
                            }
                        }
                    }
                    if (out.size > 1) brushStrokes.add(out)
                }
            }

            if (version < 3) {
                convertV2DrawingsToTimeAnchors()
            }
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
        lastLoadedWidth = w
        lastLoadedHeight = h
        // Drawings are loaded after the first candle list arrives so legacy
        // candle-position anchors can be converted to real timestamps.
        invalidate()
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

        // Blank-screen guard: a transient empty provider response must never
        // erase the currently rendered chart. Explicit chart clearing is still
        // performed only through clearCandles(), so existing behavior remains
        // unchanged for intentional clears.
        if (newCandles.isEmpty()) {
            invalidate()
            return
        }

        candles.clear()
        candles.addAll(newCandles)
        ictMtfSourceCache.clear()

        if (!drawingsLoaded && width > 0 && height > 0 && candles.isNotEmpty()) {
            loadDrawings()
        }

        if (candles.isEmpty()) {
            offsetX = 0f
        } else if (marketOpen && !isWeekendClosedNow() && followLive && !manualPanCandidate) {
            // The exact live-follow position is calculated every frame from
            // liveNowMillis, so incoming ticks do not reset the timeline.
            updateLiveFollowOffset()
        } else {
            // HISTORY-SAFE GUARD: when the candle set changes (especially
            // during a timeframe switch/reconnect) while the user is browsing
            // history, the old pixel offset may no longer intersect the new
            // candle count. Keep history mode and the existing zoom/pan state,
            // but clamp the horizontal offset so at least a few real candles
            // remain reachable. This prevents a valid candle set from looking
            // like a blank chart. No live/data/drawing behaviour is changed.
            clampHorizontalPanOnly()
        }

        invalidate()
    }

    /**
     * Compatibility API used by MainActivity. The timeframe is supplied as
     * the already-converted interval in milliseconds. Keeping this method
     * separate avoids changing the locked live price/countdown rendering.
     */
    /** Enables/disables the additive ICT MTF source/projection layer only. */
    fun setIctMtfEnabled(enabled: Boolean) {
        ictMtfEnabled = enabled
        invalidate()
    }

    /** Controls whether the required immediate HTF context is projected. */
    fun setIctMtfProjectionEnabled(enabled: Boolean) {
        ictMtfProjectionEnabled = enabled
        invalidate()
    }

    fun setMarketOpen(open: Boolean) {
        if (marketOpen == open) return
        marketOpen = open
        if (!open) {
            // Do NOT zero the countdown. MainActivity restores the exact
            // persisted frozen value after the market-closed state is set.
            frozenCountdownMode = true
        } else {
            frozenCountdownMode = false
            liveNowMillis = System.currentTimeMillis()
        }
        invalidate()
    }

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

    /** Restores the exact frozen countdown captured before market close. */
    fun restoreFrozenCountdown(
        remainingMillis: Long,
        anchorTimeMillis: Long,
        intervalMillis: Long
    ) {
        lastCandleIntervalMillis = intervalMillis.coerceAtLeast(1L)
        liveNowMillis = anchorTimeMillis
        livePriceCountdownMillis = remainingMillis.coerceAtLeast(0L)
        frozenCountdownMode = true
        invalidate()
    }

    /** Receives the real wall-clock time and selected timeframe interval. */
    fun setLiveTime(
        nowMillis: Long,
        intervalMillis: Long
    ) {
        if (!marketOpen || isWeekendClosedNow()) return
        liveNowMillis = nowMillis
        lastCandleIntervalMillis = intervalMillis.coerceAtLeast(1L)
        invalidate()
    }

    /** Moves the live price line only on a REAL provider tick. */
    fun setLiveTickPrice(
        price: Float,
        remainingMillis: Long
    ) {
        if (!marketOpen || isWeekendClosedNow()) {
            livePriceCountdownMillis = 0L
            return
        }
        if (!price.isFinite()) return
        livePriceOverride = price
        livePriceCountdownMillis = remainingMillis.coerceAtLeast(0L)
        invalidate()
    }

    /** Updates only the live price marker. Countdown state is untouched. */
    fun setLiveTickPriceOnly(price: Float) {
        if (!marketOpen || isWeekendClosedNow()) return
        if (!price.isFinite()) return
        livePriceOverride = price
        invalidate()
    }

    /** Countdown-only refresh. This NEVER changes the live price position. */
    fun setLiveCountdownRemaining(remainingMillis: Long) {
        if (!marketOpen || isWeekendClosedNow()) {
            livePriceCountdownMillis = 0L
            return
        }
        livePriceCountdownMillis = remainingMillis.coerceAtLeast(0L)
        invalidate()
    }

    /** Compatibility API for existing callers. */
    fun setLivePriceCountdown(
        price: Float,
        remainingMillis: Long
    ) = setLiveTickPrice(price, remainingMillis)

    fun resumeLiveFollow() {
        manualPanCandidate = false
        followLive = true
        if (marketOpen && !isWeekendClosedNow()) {
            updateLiveFollowOffset()
        }
        invalidate()
    }

    fun clearCandles() {

        candles.clear()

        invalidate()
    }
}

// Compile-fix: drawing helper methods drawRay/drawArrow/drawFibonacci/drawMeasureLabel are defined in this class.
