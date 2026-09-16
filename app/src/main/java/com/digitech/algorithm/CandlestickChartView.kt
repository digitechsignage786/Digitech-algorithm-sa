package com.digitech.algorithm

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
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
    private val candleUpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val candleDownPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val candles = mutableListOf<Candle>()

    private var scale = 1f
    private var offsetX = 0f

    private var lastX = 0f
    private var lastY = 0f

    private var crossX = -1f
    private var crossY = -1f

    init {

        setBackgroundColor(
            Color.rgb(8, 14, 22)
        )

        gridPaint.color =
            Color.rgb(24, 36, 50)

        gridPaint.strokeWidth = 1f

        textPaint.color =
            Color.rgb(145, 158, 175)

        textPaint.textSize = 18f

        candleUpPaint.color =
            Color.rgb(20, 190, 150)

        candleDownPaint.color =
            Color.rgb(235, 65, 85)

        crosshairPaint.color =
            Color.rgb(100, 120, 140)

        crosshairPaint.strokeWidth = 1f

        crosshairPaint.pathEffect =
            DashPathEffect(
                floatArrayOf(8f, 8f),
                0f
            )
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        drawGrid(canvas)

        drawAxes(canvas)

        if (candles.isEmpty()) {

            textPaint.textAlign =
                Paint.Align.CENTER

            textPaint.textSize = 30f

            canvas.drawText(
                "LIVE MARKET CHART",
                width / 2f,
                height / 2f - 30f,
                textPaint
            )

            textPaint.textSize = 20f

            canvas.drawText(
                "Waiting for real OHLC market data...",
                width / 2f,
                height / 2f + 15f,
                textPaint
            )

            textPaint.textSize = 16f

            canvas.drawText(
                "No demo candles",
                width / 2f,
                height / 2f + 50f,
                textPaint
            )

        } else {

            drawCandles(canvas)
        }

        if (crossX >= 0f && crossY >= 0f) {

            drawCrosshair(canvas)
        }

        drawBranding(canvas)
    }

    private fun drawGrid(canvas: Canvas) {

        val verticalSpacing = 120f
        val horizontalSpacing = 90f

        var x =
            offsetX % verticalSpacing

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

    private fun drawAxes(canvas: Canvas) {

        textPaint.textAlign =
            Paint.Align.RIGHT

        textPaint.textSize = 16f

        val priceLabels = arrayOf(
            "1.0900",
            "1.0875",
            "1.0850",
            "1.0825",
            "1.0800"
        )

        var y = 55f

        for (label in priceLabels) {

            canvas.drawText(
                label,
                width - 12f,
                y,
                textPaint
            )

            y += 75f
        }

        textPaint.textAlign =
            Paint.Align.LEFT

        canvas.drawText(
            "09:00",
            80f,
            height - 20f,
            textPaint
        )

        canvas.drawText(
            "12:00",
            300f,
            height - 20f,
            textPaint
        )

        canvas.drawText(
            "15:00",
            520f,
            height - 20f,
            textPaint
        )

        canvas.drawText(
            "18:00",
            740f,
            height - 20f,
            textPaint
        )
    }

    private fun drawCandles(canvas: Canvas) {

        if (candles.isEmpty()) return

        var highest =
            Float.MIN_VALUE

        var lowest =
            Float.MAX_VALUE

        for (candle in candles) {

            highest =
                max(highest, candle.high)

            lowest =
                min(lowest, candle.low)
        }

        val range =
            max(
                highest - lowest,
                0.00001f
            )

        val candleWidth =
            18f * scale

        val gap =
            8f * scale

        val step =
            candleWidth + gap

        for (i in candles.indices) {

            val candle =
                candles[i]

            val x =
                80f +
                i * step +
                offsetX

            if (
                x < -50f ||
                x > width + 50f
            ) {
                continue
            }

            val highY =
                40f +
                (highest - candle.high) /
                range *
                (height - 100f)

            val lowY =
                40f +
                (highest - candle.low) /
                range *
                (height - 100f)

            val openY =
                40f +
                (highest - candle.open) /
                range *
                (height - 100f)

            val closeY =
                40f +
                (highest - candle.close) /
                range *
                (height - 100f)

            val paint =
                if (
                    candle.close >=
                    candle.open
                ) {
                    candleUpPaint
                } else {
                    candleDownPaint
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
                max(
                    bottom,
                    top + 2f
                ),
                paint
            )
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

    private fun drawBranding(
        canvas: Canvas
    ) {

        textPaint.textAlign =
            Paint.Align.LEFT

        textPaint.textSize = 16f

        textPaint.color =
            Color.rgb(105, 120, 135)

        canvas.drawText(
            "DA",
            16f,
            height - 45f,
            textPaint
        )
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                lastX = event.x
                lastY = event.y

                crossX = event.x
                crossY = event.y

                invalidate()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                val dx =
                    event.x - lastX

                if (
                    event.pointerCount == 1
                ) {
                    offsetX += dx
                }

                crossX = event.x
                crossY = event.y

                lastX = event.x
                lastY = event.y

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP -> {

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

        candles.addAll(
            newCandles
        )

        invalidate()
    }

    fun clearCandles() {

        candles.clear()

        invalidate()
    }
}
