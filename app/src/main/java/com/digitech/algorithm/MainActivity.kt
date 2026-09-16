package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val backgroundColor = Color.rgb(13, 17, 23)
    private val panelColor = Color.rgb(20, 26, 34)
    private val whiteColor = Color.WHITE
    private val mutedColor = Color.rgb(150, 160, 175)
    private val buyColor = Color.rgb(38, 166, 91)
    private val sellColor = Color.rgb(220, 70, 70)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        // TOP BAR
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(panelColor)
        }

        val title = TextView(this).apply {
            text = "DA  |  EURUSD"
            textSize = 18f
            setTextColor(whiteColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        topBar.addView(
            title,
            LinearLayout.LayoutParams(0, 55, 1f)
        )

        val timeframe = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("1m", "3m", "5m", "15m", "30m", "1H", "4H", "1D")
            )
        }

        topBar.addView(
            timeframe,
            LinearLayout.LayoutParams(120, 55)
        )

        root.addView(topBar)

        // MAIN AREA
        val mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // LEFT TOOLBAR
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(panelColor)
            setPadding(6, 8, 6, 8)
        }

        val toolNames = arrayOf(
            "＋",
            "↗",
            "—",
            "⌁",
            "□",
            "◇",
            "T",
            "◎"
        )

        for (toolName in toolNames) {

            val tool = TextView(this).apply {
                text = toolName
                textSize = 20f
                setTextColor(whiteColor)
                gravity = Gravity.CENTER
                setPadding(4, 10, 4, 10)
            }

            toolbar.addView(
                tool,
                LinearLayout.LayoutParams(52, 58)
            )
        }

        mainArea.addView(
            toolbar,
            LinearLayout.LayoutParams(68, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        // CHART
        val chartArea = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(10, 14, 19))
        }

        val chartText = TextView(this).apply {
            text = """
                CANDLESTICK CHART

                Live market data will appear here

                ICT ENGINE
                Structure • Liquidity • FVG • OB
            """.trimIndent()

            textSize = 16f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }

        chartArea.addView(
            chartText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val branding = TextView(this).apply {
            text = "DA"
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(10, 6, 10, 6)
        }

        val brandingParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }

        chartArea.addView(branding, brandingParams)

        mainArea.addView(
            chartArea,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        // WATCHLIST
        val watchlist = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelColor)
            setPadding(10, 12, 10, 12)
        }

        val watchTitle = TextView(this).apply {
            text = "WATCHLIST"
            textSize = 13f
            setTextColor(whiteColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        watchlist.addView(watchTitle)

        val symbols = arrayOf(
            "EURUSD",
            "GBPUSD",
            "USDJPY",
            "XAUUSD",
            "BTCUSD",
            "NIFTY",
            "RELIANCE",
            "AAPL"
        )

        for (symbolName in symbols) {

            val item = TextView(this).apply {
                text = symbolName
                textSize = 14f
                setTextColor(mutedColor)
                setPadding(4, 12, 4, 12)
            }

            watchlist.addView(item)
        }

        mainArea.addView(
            watchlist,
            LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        root.addView(
            mainArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // BOTTOM PANEL
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(panelColor)
        }

        val signal = TextView(this).apply {
            text = "ICT SIGNAL: WAITING"
            textSize = 14f
            setTextColor(mutedColor)
        }

        bottomPanel.addView(
            signal,
            LinearLayout.LayoutParams(0, 55, 1f)
        )

        val buyButton = Button(this).apply {
            text = "BUY"
            setTextColor(Color.WHITE)
            setBackgroundColor(buyColor)
        }

        bottomPanel.addView(
            buyButton,
            LinearLayout.LayoutParams(120, 55)
        )

        val sellButton = Button(this).apply {
            text = "SELL"
            setTextColor(Color.WHITE)
            setBackgroundColor(sellColor)
        }

        bottomPanel.addView(
            sellButton,
            LinearLayout.LayoutParams(120, 55)
        )

        root.addView(bottomPanel)

        setContentView(root)
    }
}
