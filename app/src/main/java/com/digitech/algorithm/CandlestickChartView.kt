package com.digitech.algorithm

import android.content.Context
import android.graphics.*
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

    private var crossX = -1f
    private var crossY = -1f

    private var lastX = 0f
    private var dragging = false

    private var activeTool = DrawingTool.CROSSHAIR

    private var drawStartX = -1f
    private var drawStartY = -1f

    private var currentX = -1f
    private var currentY = -1f

    private val trendLines =
        mutableListOf<LineData>()

    private val horizontalLines =
        mutableListOf<Float>()

    private val rectangles =
        mutableListOf<RectData>()

    private val measurements =
        mutableListOf<MeasureData>()

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

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {

                    val oldZoom = zoom

                    zoom *= detector.scaleFactor
                    zoom = zoom.coerceIn(0.45f, 5f)

                    val focusX = detector.focusX

                    offsetX =
                        focusX -
                        (focusX - offsetX) *
                        (zoom / oldZoom)

                    invalidate()

                    return true
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

        labelPaint.color = Color.WHITE
        labelPaint.textSize = 12f

        boxPaint.style =
            Paint.Style.FILL

        boxPaint.color =
            Color.rgb(25, 34, 46)
    }

    override fun onDraw(canvas: Canvas) {

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

    private fun drawGrid(canvas: Canvas) {

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

    private fun drawEmptyChart(canvas: Canvas) {

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

    private fun drawCandles(canvas: Canvas) {

        if (candles.isEmpty()) return

        var highest =
            Float.NEGATIVE_INFINITY

        var lowest =
            Float.POSITIVE_INFINITY

        for (candle in candles) {

            highest =
                max(highest, candle.high)

            lowest =
                min(lowest, candle.low)
        }

        val range =
            max(
                highest - lowest,
                0.000001f
            )

        val candleWidth =
            (15f * zoom).coerceIn(3f, 42f)

        val gap =
            (5f * zoom).coerceIn(1f, 16f)

        val step =
            candleWidth + gap

        val top = 25f
        val bottom = height - 55f

        val chartHeight =
            max(bottom - top, 1f)

        for (i in candles.indices) {

            val candle =
                candles[i]

            val x =
                65f +
                i * step +
                offsetX

            if (
                x + candleWidth < 0f ||
                x > width
            ) continue

            val highY =
                top +
                (highest - candle.high) /
                range *
                chartHeight

            val lowY =
                top +
                (highest - candle.low) /
                range *
                chartHeight

            val openY =
                top +
                (highest - candle.open) /
                range *
                chartHeight

            val closeY =
                top +
                (highest - candle.close) /
                range *
                chartHeight

            val paint =
                if (candle.close >= candle.open)
                    upPaint
                else
                    downPaint

            val center =
                x + candleWidth / 2f

            canvas.drawLine(
                center,
                highY,
                center,
                lowY,
                paint
            )

            canvas.drawRect(
                x,
                min(openY, closeY),
                x + candleWidth,
                max(
                    closeY,
                    openY + 2f
                ),
                paint
            )
        }
    }

    private fun drawPriceScale(canvas: Canvas) {

        if (candles.isEmpty()) return

        var highest =
            Float.NEGATIVE_INFINITY

        var lowest =
            Float.POSITIVE_INFINITY

        for (candle in candles) {

            highest =
                max(highest, candle.high)

            lowest =
                min(lowest, candle.low)
        }

        val range =
            max(
                highest - lowest,
                0.000001f
            )

        textPaint.textAlign =
            Paint.Align.RIGHT

        textPaint.textSize = 13f

        for (i in 0..6) {

            val fraction =
                i.toFloat() / 6f

            val price =
                highest -
                range * fraction

            val y =
                25f +
                fraction *
                (height - 80f)

            canvas.drawText(
                String.format(
                    java.util.Locale.US,
                    "%.5f",
                    price
                ),
                width - 7f,
                y,
                textPaint
            )
        }
    }

    private fun drawTimeScale(canvas: Canvas) {

        if (candles.isEmpty()) return

        textPaint.textAlign =
            Paint.Align.CENTER

        textPaint.textSize = 12f

        val candleWidth =
            (15f * zoom).coerceIn(3f, 42f)

        val gap =
            (5f * zoom).coerceIn(1f, 16f)

        val step =
            candleWidth + gap

        val every =
            max(
                1,
                (90f / step).toInt()
            )

        for (i in candles.indices step every) {

            val x =
                65f +
                i * step +
                offsetX +
                candleWidth / 2f

            if (
                x < 0f ||
                x > width
            ) continue

            val sdf =
                java.text.SimpleDateFormat(
                    "HH:mm",
                    java.util.Locale.US
                )

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

    // =========================
    // STORED DRAWINGS
    // =========================

    private fun drawStoredDrawings(canvas: Canvas) {

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

    // =========================
    // ACTIVE DRAWING
    // =========================

    private fun drawActiveDrawing(canvas: Canvas) {

        if (
            drawStartX < 0f ||
            currentX < 0f
        ) return

        when (activeTool) {

            DrawingTool.TREND_LINE -> {

                drawingPaint.color =
                    Color.rgb(55, 130, 235)

                canvas.drawLine(
                    drawStartX,
                    drawStartY,
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
                    drawStartX,
                    drawStartY,
                    currentX,
                    currentY,
                    drawingPaint
                )
            }

            DrawingTool.MEASURE -> {

                drawingPaint.color =
                    Color.rgb(190, 120, 235)

                canvas.drawLine(
                    drawStartX,
                    drawStartY,
                    currentX,
                    currentY,
                    drawingPaint
                )
            }

            else -> {}
        }
    }

    // =========================
    // CROSSHAIR
    // =========================

    private fun drawCrosshair(canvas: Canvas) {

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

    private fun drawCrosshairLabels(canvas: Canvas) {

        canvas.drawRect(
            width - 78f,
            crossY - 14f,
            width.toFloat(),
            crossY + 14f,
            boxPaint
        )

        labelPaint.textAlign =
            Paint.Align.CENTER

        canvas.drawText(
            "Price",
            width - 39f,
            crossY + 4f,
            labelPaint
        )

        canvas.drawRect(
            crossX - 32f,
            height - 31f,
            crossX + 32f,
            height.toFloat(),
            boxPaint
        )

        canvas.drawText(
            "Time",
            crossX,
            height - 11f,
            labelPaint
        )
    }

    private fun drawBranding(canvas: Canvas) {

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

    // =========================
    // TOUCH ENGINE
    // =========================

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                if (
                    activeTool != DrawingTool.CROSSHAIR
                ) {

                    drawStartX = event.x
                    drawStartY = event.y

                    currentX = event.x
                    currentY = event.y

                } else {

                    crossX = event.x
                    crossY = event.y

                    lastX = event.x
                    dragging = true
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (
                    activeTool != DrawingTool.CROSSHAIR
                ) {

                    currentX = event.x
                    currentY = event.y

                } else {

                    if (
                        event.pointerCount == 1 &&
                        dragging &&
                        !scaleDetector.isInProgress
                    ) {

                        offsetX +=
                            event.x - lastX

                        lastX = event.x
                    }

                    crossX = event.x
                    crossY = event.y
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP -> {

                if (
                    activeTool != DrawingTool.CROSSHAIR &&
                    drawStartX >= 0f
                ) {

                    when (activeTool) {

                        DrawingTool.TREND_LINE -> {

                            trendLines.add(
                                LineData(
                                    drawStartX,
                                    drawStartY,
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
                                        drawStartX,
                                        event.x
                                    ),
                                    min(
                                        drawStartY,
                                        event.y
                                    ),
                                    max(
                                        drawStartX,
                                        event.x
                                    ),
                                    max(
                                        drawStartY,
                                        event.y
                                    )
                                )
                            )
                        }

                        DrawingTool.MEASURE -> {

                            measurements.add(
                                MeasureData(
                                    drawStartX,
                                    drawStartY,
                                    event.x,
                                    event.y
                                )
                            )
                        }

                        else -> {}
                    }

                    drawStartX = -1f
                    drawStartY = -1f
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

                drawStartX = -1f
                drawStartY = -1f
                currentX = -1f
                currentY = -1f

                invalidate()

                return true
            }
        }

        return true
    }

    // =========================
    // TOOL SELECTION
    // =========================

    fun setDrawingTool(tool: DrawingTool) {

        activeTool = tool

        drawStartX = -1f
        drawStartY = -1f
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

    // =========================
    // MARKET DATA
    // =========================

    fun setCandles(
        newCandles: List<Candle>
    ) {

        candles.clear()
        candles.addAll(newCandles)

        invalidate()
    }

    fun clearCandles() {
        
