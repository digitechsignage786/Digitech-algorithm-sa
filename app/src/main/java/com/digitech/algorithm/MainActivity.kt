package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bgColor = Color.rgb(8, 14, 22)
    private val panelColor = Color.rgb(15, 23, 34)
    private val whiteColor = Color.WHITE
    private val mutedColor = Color.rgb(145, 158, 175)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        // TOP BAR
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(panelColor)
        }

        val title = TextView(this).apply {
            text = "DA  |  EURUSD"
            textSize = 18f
            setTextColor(whiteColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

        topBar.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                56,
                1f
            )
        )

        val timeframe = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "1m",
                    "3m",
                    "5m",
                    "15m",
                    "30m",
                    "1H",
                    "4H",
                    "1D",
                    "1W"
                )
            )
        }

        topBar.addView(
            timeframe,
            LinearLayout.LayoutParams(
                120,
                56
            )
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
            setBackgroundColor(bgColor)
        }

        // LEFT TOOLBAR
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(panelColor)
        }

        val toolNames = arrayOf(
            "＋",
            "↗",
            "—",
            "⌁",
            "□",
            "◇",
            "T",
            "◎",
            "⌖"
        )

        for (toolName in toolNames) {

            val tool = TextView(this).apply {
                text = toolName
                textSize = 20f
                setTextColor(whiteColor)
                gravity = Gravity.CENTER
                setPadding(4, 8, 4, 8)
            }

            toolbar.addView(
                tool,
                LinearLayout.LayoutParams(
                    62,
                    56
                )
            )
        }

        mainArea.addView(
            toolbar,
            LinearLayout.LayoutParams(
                68,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        // CHART
        val chartView = CandlestickChartView(this)

        mainArea.addView(
            chartView,
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
            setPadding(10, 12, 10, 8)
        }

        val watchTitle = TextView(this).apply {
            text = "WATCHLIST"
            textSize = 14f
            setTextColor(whiteColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 4, 12)
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
                setPadding(4, 10, 4, 10)
            }

            watchlist.addView(item)
        }

        mainArea.addView(
            watchlist,
            LinearLayout.LayoutParams(
                180,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        // IMPORTANT:
        // MAIN AREA MUST HAVE MATCH_PARENT WIDTH
        // AND WEIGHTED HEIGHT.
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
            setPadding(12, 6, 12, 6)
            setBackgroundColor(panelColor)
        }

        val signal = TextView(this).apply {
            text = "ICT SIGNAL: WAITING FOR MARKET DATA"
            textSize = 13f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER_VERTICAL
        }

        bottomPanel.addView(
            signal,
            LinearLayout.LayoutParams(
                0,
                52,
                1f
            )
        )

        val buyButton = Button(this).apply {
            text = "BUY"
        }

        bottomPanel.addView(
            buyButton,
            LinearLayout.LayoutParams(
                110,
                52
            )
        )

        val sellButton = Button(this).apply {
            text = "SELL"
        }

        bottomPanel.addView(
            sellButton,
            LinearLayout.LayoutParams(
                110,
                52
            )
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
