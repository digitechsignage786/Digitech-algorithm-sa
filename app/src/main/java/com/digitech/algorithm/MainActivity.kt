package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(13, 17, 23)
    private val panel = Color.rgb(20, 26, 34)
    private val text = Color.WHITE
    private val muted = Color.rgb(150, 160, 175)
    private val green = Color.rgb(38, 166, 91)
    private val red = Color.rgb(220, 70, 70)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // TOP BAR
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(panel)
        }

        val title = TextView(this).apply {
            text = "DA  |  EURUSD"
            textSize = 18f
            setTextColor(text)
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

        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // MAIN AREA
        val mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // LEFT TOOLBAR
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)
            setPadding(8, 8, 8, 8)
        }

        val tools = arrayOf(
            "＋",
            "↗",
            "—",
            "⌁",
            "□",
            "◇",
            "T",
            "◎"
        )

        for (tool in tools) {
            val button = TextView(this).apply {
                this.text = tool
                textSize = 20f
                setTextColor(text)
                gravity = Gravity.CENTER
                setPadding(4, 12, 4, 12)
            }

            toolbar.addView(
                button,
                LinearLayout.LayoutParams(52, 58)
            )
        }

        mainArea.addView(
            toolbar,
            LinearLayout.LayoutParams(68, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        // CHART AREA
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
            setTextColor(muted)
            gravity = Gravity.CENTER
        }

        chartArea.addView(
            chartText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // DA BRANDING
        val branding = TextView(this).apply {
            text = "DA"
            textSize = 12f
            setTextColor(muted)
            setPadding(10, 6, 10, 6)
        }

        val brandParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        )

        chartArea.addView(branding, brandParams)

        mainArea.addView(
            chartArea,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )

        // WATCHLIST
        val watchlist = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(10, 12, 10, 12)
        }

        val watchTitle = TextView(this).apply {
            text = "WATCHLIST"
            textSize = 13f
            setTextColor(text)
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

        for (symbol in symbols) {
            val item = TextView(this).apply {
                this.text = symbol
                textSize = 14f
                setTextColor(muted)
                setPadding(4, 14, 4, 14)
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

        // BOTTOM TRADING PANEL
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(panel)
        }

        val signal = TextView(this).apply {
            text = "ICT SIGNAL: WAITING"
            textSize = 14f
            setTextColor(muted)
        }

        bottomPanel.addView(
            signal,
            LinearLayout.LayoutParams(0, 55, 1f)
        )

        val buy = Button(this).apply {
            text = "BUY"
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
        }

        bottomPanel.addView(
            buy,
            LinearLayout.LayoutParams(120, 55)
        )

        val sell = Button(this).apply {
            text = "SELL"
            setTextColor(Color.WHITE)
            setBackgroundColor(red)
        }

        bottomPanel.addView(
            sell,
            LinearLayout.LayoutParams(120, 55)
        )

        root.addView(
            bottomPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }
}
