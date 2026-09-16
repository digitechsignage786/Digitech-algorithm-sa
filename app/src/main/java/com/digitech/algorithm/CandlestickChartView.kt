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

    private val candles = mutableListOf<Candle>()

    private var zoom = 1f
    private var offsetX = 0f

    private var lastX = 0f
    private var dragging = false

    private var crossX = -1f
    private var crossY = -1f

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoom *= detector.scaleFactor
                    zoom = zoom.coerceIn(0.5f, 4f)
                    invalidate()
                    return true
                }
            }
        )

    init {
        setBackgroundColor(Color.rgb(7, 13, 21))

        gridPaint.color = Color.rgb(27, 39, 53)
        gridPaint.strokeWidth = 1f

        textPaint.color = Color.rgb(145, 158, 175)
        textPaint.textSize = 16f

        upPaint.color = Color.rgb(20, 190, 145)
        downPaint.color = Color.rgb(235, 65, 85)

        crosshairPaint.color = Color.rgb(110, 125, 145)
        crosshairPaint.strokeWidth = 1f
        crosshairPaint.pathEffect =
            DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackgroundGrid(canvas)
        drawPriceScale(canvas)
        drawTimeScale(canvas)

        if (candles.isEmpty()) {
            drawEmptyChart(canvas)
        } else {
            drawCandles(canvas)
        }

        if (crossX >= 0f && crossY >= 0f) {
            drawCrosshair(canvas)
        }

        drawBranding(canvas)
    }

    private fun drawBackgroundGrid(canvas: Canvas) {

        val verticalSpacing = 100f
        val horizontalSpacing = 75f

        var x = (offsetX % verticalSpacing) - verticalSpacing

        while (x < width) {
            canvas.drawLine(
                x,
                0f,
                x,
                height.toFloat(),
                gridPaint
            )
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
            y += horizontalSpacing
        }
    }

    private fun drawPriceScale(canvas: Canvas) {

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 15f

        val labels = arrayOf(
            "1.0900",
            "1.0875",
            "1.0850",
            "1.0825",
            "1.0800"
        )

        var y = 45f

        for (label in labels) {
            canvas.drawText(
                label,
                width - 12f,
                y,
                textPaint
            )

            y += 75f
        }
    }

    private fun drawTimeScale(canvas: Canvas) {

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 14f

        canvas.drawText(
            "09:00",
            70f + offsetX,
            height - 18f,
            textPaint
        )

        canvas.drawText(
            "12:00",
            280f + offsetX,
            height - 18f,
            textPaint
        )

        canvas.drawText(
            "15:00",
            490f + offsetX,
            height - 18f,
            textPaint
        )

        canvas.drawText(
            "18:00",
            700f + offsetX,
            height - 18f,
            textPaint
        )
    }

    private fun drawEmptyChart(canvas: Canvas) {

        textPaint.textAlign = Paint.Align.CENTER

        textPaint.textSize = 27f
        canvas.drawText(
            "LIVE MARKET CHART",
            width / 2f,
            height / 2f - 25f,
            textPaint
        )

        textPaint.textSize = 17f
        canvas.drawText(
            "Waiting for real OHLC market data...",
            width / 2f,
            height / 2f + 15f,
            textPaint
        )

        textPaint.textSize = 14f
        canvas.drawText(
            "No demo candles",
            width / 2f,
            height / 2f + 45f,
            textPaint
        )
    }

    private fun drawCandles(canvas: Canvas) {

        var highest = Float.MIN_VALUE
        var lowest = Float.MAX_VALUE

        for (candle in candles) {
            highest = max(highest, candle.high)
            lowest = min(lowest, candle.low)
        }

        val range = max(
            highest - lowest,
            0.00001f
        )

        val candleWidth = 16f * zoom
        val gap = 7f * zoom
        val step = candleWidth + gap

        for (i in candles.indices) {

            val candle = candles[i]

            val x =
                70f +
                i * step +
                offsetX

            if (x < -40f || x > width + 40f) {
                continue
            }

            val chartHeight =
                height - 100f

            val highY =
                35f +
                (highest - candle.high) /
                range *
                chartHeight

            val lowY =
                35f +
                (highest - candle.low) /
                range *
                chartHeight

            val openY =
                35f +
                (highest - candle.open) /
                range *
                chartHeight

            val closeY =
                35f +
                (highest - candle.close) /
                range *
                chartHeight

            val paint =
                if (candle.close >= candle.open)
                    upPaint
                else
                    downPaint

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

    private fun drawBranding(canvas: Canvas) {

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 15f
        textPaint.color = Color.rgb(
            100,
            115,
            130
        )

        canvas.drawText(
            "DA",
            15f,
            height - 45f,
            textPaint
        )
    }

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

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                dragging = false

                invalidate()

                return true
            }
        }

        return true
    }

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
