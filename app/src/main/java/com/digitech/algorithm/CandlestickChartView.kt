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

class CandlestickChartView(context: Context) : View(context) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val candles = mutableListOf<Candle>()

    private var zoom = 1f
    private var offsetX = 0f

    private var crossX = -1f
    private var crossY = -1f

    private var lastX = 0f
    private var dragging = false

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

        setBackgroundColor(Color.rgb(7, 12, 18))

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

        labelPaint.color = Color.WHITE
        labelPaint.textSize = 12f

        boxPaint.style = Paint.Style.FILL
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

        if (crossX >= 0f && crossY >= 0f) {

            drawCrosshair(canvas)
            drawCrosshairLabels(canvas)
        }

        drawBranding(canvas)
    }

    // =========================
    // GRID
    // =========================

    private fun drawGrid(canvas: Canvas) {

        val verticalSpacing =
            100f * zoom

        var x =
            offsetX % verticalSpacing

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

            x += verticalSpacing
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

    // =========================
    // EMPTY STATE
    // =========================

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

    // =========================
    // CANDLES
    // =========================

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
            max(highest - lowest, 0.000001f)

        val candleWidth =
            (15f * zoom)
                .coerceIn(3f, 42f)

        val gap =
            (5f * zoom)
                .coerceIn(1f, 16f)

        val step =
            candleWidth + gap

        val chartTop = 25f
        val chartBottom =
            height - 55f

        val chartHeight =
            max(
                chartBottom - chartTop,
                1f
            )

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
                chartTop +
                (highest - candle.high) /
                range *
                chartHeight

            val lowY =
                chartTop +
                (highest - candle.low) /
                range *
                chartHeight

            val openY =
                chartTop +
                (highest - candle.open) /
                range *
                chartHeight

            val closeY =
                chartTop +
                (highest - candle.close) /
                range *
                chartHeight

            val paint =
                if (
                    candle.close >= candle.open
                ) {
                    upPaint
                } else {
                    downPaint
                }

            val centerX =
                x + candleWidth / 2f

            // Wick

            canvas.drawLine(
                centerX,
                highY,
                centerX,
                lowY,
                paint
            )

            // Body

            val top =
                min(openY, closeY)

            val bottom =
                max(openY, closeY)

            canvas.drawRect(
                x,
                top,
                x + candleWidth,
                max(bottom, top + 2f),
                paint
            )
        }
    }

    // =========================
    // PRICE SCALE
    // =========================

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
            max(highest - lowest, 0.000001f)

        textPaint.textAlign =
            Paint.Align.RIGHT

        textPaint.textSize = 13f

        val levels = 6

        for (i in 0..levels) {

            val fraction =
                i.toFloat() / levels

            val price =
                highest -
                range * fraction

            val y =
                25f +
                fraction *
                (height - 80f)

            val label =
                String.format(
                    java.util.Locale.US,
                    "%.5f",
                    price
                )

            canvas.drawText(
                label,
                width - 7f,
                y,
                textPaint
            )
        }
    }

    // =========================
    // TIME SCALE
    // =========================

    private fun drawTimeScale(canvas: Canvas) {

        if (candles.isEmpty()) return

        textPaint.textAlign =
            Paint.Align.CENTER

        textPaint.textSize = 12f

        val candleWidth =
            (15f * zoom)
                .coerceIn(3f, 42f)

        val gap =
            (5f * zoom)
                .coerceIn(1f, 16f)

        val step =
            candleWidth + gap

        val visibleStep =
            max(
                1,
                (90f / step).toInt()
            )

        for (
            i in candles.indices
            step visibleStep
        ) {

            val x =
                65f +
                i * step +
                offsetX +
                candleWidth / 2f

            if (
                x < 0f ||
                x > width
            ) continue

            canvas.drawText(
                formatTime(
                    candles[i].time
                ),
                x,
                height - 14f,
                textPaint
            )
        }
    }

    private fun formatTime(time: Long): String {

        val sdf =
            java.text.SimpleDateFormat(
                "HH:mm",
                java.util.Locale.US
            )

        return sdf.format(
            java.util.Date(time)
        )
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

        // Price label

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

        // Time label

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

    // =========================
    // BRANDING
    // =========================

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
    // TOUCH
    // =========================

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                lastX = event.x
                dragging = true

                crossX = event.x
                crossY = event.y

                invalidate()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (
                    event.pointerCount == 1 &&
                    dragging &&
                    !scaleDetector.isInProgress
                ) {

                    val dx =
                        event.x - lastX

                    offsetX += dx

                    lastX = event.x
                }

                crossX = event.x
                crossY = event.y

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP -> {

                dragging = false

                crossX = event.x
                crossY = event.y

                invalidate()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                dragging = false

                return true
            }
        }

        return true
    }

    // =========================
    // MARKET DATA API
    // =========================

    fun setCandles(
        newCandles: List<Candle>
    ) {

        candles.clear()

        candles.addAll(newCandles)

        invalidate()
    }

    fun clearCandles() {

        candles.clear()

        invalidate()
    }
}
