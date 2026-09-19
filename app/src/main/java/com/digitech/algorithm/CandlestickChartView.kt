package com.digitech.algorithm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

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
    HORIZONTAL_LINE,
    RECTANGLE,
    MEASURE
}

class CandlestickChartView(context: Context) : View(context) {

    // V8: fluid pinch zoom with correct zoom-space anchoring.

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
    private var targetZoom = 1f
    private var offsetX = 0f
    private var targetOffsetX = 0f
    private var smoothZoomRunning = false

    private var crossX = -1f
    private var crossY = -1f
    private var lastX = 0f
    private var dragging = false
    private var followLatest = true

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
    private val horizontalLines = mutableListOf<Float>()
    private val rectangles = mutableListOf<RectData>()
    private val measurements = mutableListOf<MeasureData>()

    private var scaleInProgress = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(
                detector: ScaleGestureDetector
            ): Boolean {

                scaleInProgress = true
                dragging = false
                return true
            }

            override fun onScale(
                detector: ScaleGestureDetector
            ): Boolean {

                val rawFactor = detector.scaleFactor

                if (!rawFactor.isFinite()) {
                    return true
                }

                val delta = rawFactor - 1f

                if (kotlin.math.abs(delta) < 0.001f) {
                    return true
                }

                // Apply a small portion of each detector update. This reduces
                // visible jitter while keeping the pinch responsive.
                val smoothFactor =
                    1f + delta.coerceIn(-0.10f, 0.10f) * 0.55f

                val requestedZoom =
                    (targetZoom * smoothFactor)
                        .coerceIn(0.55f, 5f)

                targetZoom = requestedZoom

                val left = chartLeft()
                val focusX =
                    detector.focusX.coerceIn(
                        left,
                        chartRight()
                    )

                // Keep the exact logical candle under the pinch center.
                // This value is recalculated from the current rendered geometry
                // so the anchor follows the fingers without a horizontal jump.
                val currentStep = candleStep()
                val logicalPosition =
                    if (currentStep > 0f) {
                        (focusX - left - offsetX) / currentStep
                    } else {
                        0f
                    }

                val desiredStep = candleStepForZoom(targetZoom)

                targetOffsetX =
                    focusX -
                    left -
                    logicalPosition * desiredStep

                targetOffsetX = clampOffsetValue(
                    targetOffsetX,
                    targetZoom
                )

                startSmoothZoom()
                return true
            }

            override fun onScaleEnd(
                detector: ScaleGestureDetector
            ) {

                scaleInProgress = false
                clampHorizontalOffset()
                invalidate()
            }
        }
    )

    init {

        setBackgroundColor(
            Color.rgb(7, 12, 18)
        )

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

        drawGrid(canvas)

        if (candles.isEmpty()) {

            drawEmptyChart(canvas)

        } else {

            drawCandles(canvas)
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

    private fun chartTop(): Float {
        return 20f
    }

    private fun chartBottom(): Float {
        return height - 55f
    }

    private fun candleWidth(): Float {
        return (14f * zoom).coerceIn(4f, 34f)
    }

    private fun candleGap(): Float {
        return (4f * zoom).coerceIn(1f, 10f)
    }

    private fun candleWidthForZoom(zoomValue: Float): Float {
        return (14f * zoomValue).coerceIn(4f, 34f)
    }

    private fun candleGapForZoom(zoomValue: Float): Float {
        return (4f * zoomValue).coerceIn(1f, 10f)
    }

    private fun candleStepForZoom(zoomValue: Float): Float {
        return candleWidthForZoom(zoomValue) + candleGapForZoom(zoomValue)
    }

    private fun candleStep(): Float {
        return candleStepForZoom(zoom)
    }

    private fun candleX(index: Int): Float {
        return chartLeft() +
            index * candleStep() +
            offsetX
    }

    private fun clampHorizontalOffset() {

        if (candles.isEmpty()) {
            offsetX = 0f
            return
        }

        val left = chartLeft()
        val right = chartRight()
        val step = candleStep()
        val candle = candleWidth()

        val firstLimit = 0f
        val lastLimit =
            right - (left + (candles.size - 1) * step + candle)

        if (lastLimit <= firstLimit) {
            offsetX = offsetX.coerceIn(lastLimit, firstLimit)
        } else {
            // When the whole series is narrower than the viewport, keep it
            // centered instead of allowing it to slide endlessly.
            offsetX = (lastLimit + firstLimit) / 2f
        }
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

        val rawRange =
            max(
                highest - lowest,
                0.000001f
            )

        // Give the price scale more breathing room so portrait mode
        // does not make candles/wicks look vertically exaggerated.
        // The OHLC values themselves are never changed or clipped.
        val padding =
            rawRange * 0.30f

        return Pair(
            highest + padding,
            lowest - padding
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

            canvas.drawText(
                label,
                width - 7f,
                y + 4f,
                textPaint
            )
        }
    }

    private fun drawTimeScale(
        canvas: Canvas
    ) {

        if (candles.isEmpty()) {
            return
        }

        textPaint.textAlign =
            Paint.Align.CENTER

        textPaint.textSize = 11f

        val widthOfCandle =
            candleWidth()

        val step =
            candleStep()

        val every =
            max(
                1,
                (90f / step).toInt()
            )

        val sdf =
            java.text.SimpleDateFormat(
                "HH:mm",
                java.util.Locale.US
            )

        for (
            i in candles.indices step every
        ) {

            val x =
                candleX(i) +
                widthOfCandle / 2f

            if (
                x < chartLeft() ||
                x > chartRight()
            ) {
                continue
            }

            canvas.drawText(
                sdf.format(
                    java.util.Date(
                        candles[i].time
                    )
                ),
                x,
                height - 14f,
                textPaint
            )
        }
    }

    private fun drawStoredDrawings(
        canvas: Canvas
    ) {

        drawingPaint.strokeWidth =
            2.5f

        drawingPaint.color =
            Color.rgb(55, 130, 235)

        for (line in trendLines) {

            canvas.drawLine(
                line.x1,
                line.y1,
                line.x2,
                line.y2,
                drawingPaint
            )
        }

        drawingPaint.color =
            Color.rgb(235, 190, 55)

        for (y in horizontalLines) {

            canvas.drawLine(
                0f,
                y,
                width.toFloat(),
                y,
                drawingPaint
            )
        }

        drawingPaint.color =
            Color.rgb(55, 180, 235)

        for (rect in rectangles) {

            canvas.drawRect(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                drawingPaint
            )
        }

        drawingPaint.color =
            Color.rgb(190, 120, 235)

        for (measure in measurements) {

            canvas.drawLine(
                measure.x1,
                measure.y1,
                measure.x2,
                measure.y2,
                drawingPaint
            )
        }
    }

    private fun drawActiveDrawing(
        canvas: Canvas
    ) {

        if (
            startX < 0f ||
            startY < 0f ||
            currentX < 0f ||
            currentY < 0f
        ) {
            return
        }

        when (activeTool) {

            DrawingTool.TREND_LINE -> {

                drawingPaint.color =
                    Color.rgb(55, 130, 235)

                canvas.drawLine(
                    startX,
                    startY,
                    currentX,
                    currentY,
                    drawingPaint
                )
            }

            DrawingTool.HORIZONTAL_LINE -> {

                drawingPaint.color =
                    Color.rgb(235, 190, 55)

                canvas.drawLine(
                    0f,
                    currentY,
                    width.toFloat(),
                    currentY,
                    drawingPaint
                )
            }

            DrawingTool.RECTANGLE -> {

                drawingPaint.color =
                    Color.rgb(55, 180, 235)

                canvas.drawRect(
                    min(startX, currentX),
                    min(startY, currentY),
                    max(startX, currentX),
                    max(startY, currentY),
                    drawingPaint
                )
            }

            DrawingTool.MEASURE -> {

                drawingPaint.color =
                    Color.rgb(190, 120, 235)

                canvas.drawLine(
                    startX,
                    startY,
                    currentX,
                    currentY,
                    drawingPaint
                )
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

    private fun clampOffsetValue(
        value: Float,
        zoomValue: Float
    ): Float {

        val previousZoom = zoom
        val previousOffset = offsetX

        zoom = zoomValue

        val left = chartLeft()
        val right = chartRight()
        val step = candleStep()
        val candle = candleWidth()

        val lastLimit =
            right - (left + (candles.size - 1) * step + candle)

        val result =
            if (candles.isEmpty()) {
                0f
            } else if (lastLimit <= 0f) {
                value.coerceIn(lastLimit, 0f)
            } else {
                // Keep the complete series centered when it is narrower
                // than the available chart width.
                (lastLimit / 2f)
            }

        zoom = previousZoom
        offsetX = previousOffset

        return result
    }

    private fun startSmoothZoom() {

        if (smoothZoomRunning) {
            return
        }

        smoothZoomRunning = true

        postOnAnimation(object : Runnable {

            override fun run() {

                val zoomDelta =
                    targetZoom - zoom

                val offsetDelta =
                    targetOffsetX - offsetX

                // Higher interpolation keeps the pinch responsive while
                // still removing the small jumps caused by touch sampling.
                zoom += zoomDelta * 0.42f
                offsetX += offsetDelta * 0.42f

                clampHorizontalOffset()

                invalidate()

                if (
                    kotlin.math.abs(zoomDelta) > 0.0005f ||
                    kotlin.math.abs(offsetDelta) > 0.2f ||
                    scaleInProgress
                ) {
                    postOnAnimation(this)
                } else {
                    zoom = targetZoom
                    offsetX = targetOffsetX
                    clampHorizontalOffset()
                    smoothZoomRunning = false
                    invalidate()
                }
            }
        })
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                if (
                    activeTool !=
                    DrawingTool.CROSSHAIR
                ) {

                    startX =
                        event.x

                    startY =
                        event.y

                    currentX =
                        event.x

                    currentY =
                        event.y

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

                if (
                    activeTool !=
                    DrawingTool.CROSSHAIR
                ) {

                    currentX =
                        event.x

                    currentY =
                        event.y

                } else {

                    if (
                        event.pointerCount == 1 &&
                        dragging &&
                        !scaleDetector.isInProgress
                    ) {

                        val dx = event.x - lastX

                        if (kotlin.math.abs(dx) > 1f) {
                            followLatest = false
                        }

                        offsetX += dx

                        targetOffsetX = offsetX
                        clampHorizontalOffset()

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

                if (scaleInProgress) {
                    scaleInProgress = false
                    dragging = false
                    startX = -1f
                    startY = -1f
                    currentX = -1f
                    currentY = -1f
                    invalidate()
                    return true
                }

                if (
                    activeTool !=
                    DrawingTool.CROSSHAIR &&
                    startX >= 0f &&
                    startY >= 0f
                ) {

                    when (activeTool) {

                        DrawingTool.TREND_LINE -> {

                            trendLines.add(
                                LineData(
                                    startX,
                                    startY,
                                    event.x,
                                    event.y
                                )
                            )
                        }

                        DrawingTool.HORIZONTAL_LINE -> {

                            horizontalLines.add(
                                event.y
                            )
                        }

                        DrawingTool.RECTANGLE -> {

                            rectangles.add(
                                RectData(
                                    min(
                                        startX,
                                        event.x
                                    ),
                                    min(
                                        startY,
                                        event.y
                                    ),
                                    max(
                                        startX,
                                        event.x
                                    ),
                                    max(
                                        startY,
                                        event.y
                                    )
                                )
                            )
                        }

                        DrawingTool.MEASURE -> {

                            measurements.add(
                                MeasureData(
                                    startX,
                                    startY,
                                    event.x,
                                    event.y
                                )
                            )
                        }

                        DrawingTool.CROSSHAIR -> {
                            // Nothing to save.
                        }
                    }

                    startX = -1f
                    startY = -1f
                    currentX = -1f
                    currentY = -1f

                } else {

                    dragging = false
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                dragging = false

                startX = -1f
                startY = -1f
                currentX = -1f
                currentY = -1f

                invalidate()

                return true
            }
        }

        return true
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

    fun clearDrawings() {

        trendLines.clear()
        horizontalLines.clear()
        rectangles.clear()
        measurements.clear()

        invalidate()
    }

    fun setCandles(
        newCandles: List<Candle>
    ) {

        candles.clear()
        candles.addAll(newCandles)

        if (followLatest && candles.isNotEmpty()) {
            val left = chartLeft()
            val right = chartRight()
            val step = candleStep()
            val candle = candleWidth()

            val lastCandleEnd =
                left +
                (candles.size - 1) * step +
                candle

            offsetX =
                right - lastCandleEnd - 14f

            targetOffsetX = offsetX
            clampHorizontalOffset()
            targetOffsetX = offsetX
        } else {
            clampHorizontalOffset()
            targetOffsetX = offsetX
        }

        invalidate()
    }

    fun clearCandles() {

        candles.clear()
        offsetX = 0f
        targetOffsetX = 0f
        followLatest = true

        invalidate()
    }
}
