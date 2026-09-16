package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(8, 14, 22)
    private val panel = Color.rgb(15, 23, 34)
    private val white = Color.WHITE
    private val muted = Color.rgb(145, 158, 175)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // TOP BAR
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(panel)
        }

        val symbol = TextView(this).apply {
            text = "DA  |  EURUSD"
            textSize = 18f
            setTextColor(white)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        top.addView(
            symbol,
            LinearLayout.LayoutParams(0, 56, 1f)
        )

        val timeframe = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("1m", "3m", "5m", "15m", "30m", "1H", "4H", "1D", "1W")
            )
        }

        top.addView(
            timeframe,
            LinearLayout.LayoutParams(120, 56)
        )

        root.addView(top)

        // MAIN AREA
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // LEFT TOOLS
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)
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

        for (name in toolNames) {
            val tool = TextView(this).apply {
                text = name
                textSize = 20f
                setTextColor(white)
                gravity = Gravity.CENTER
                setPadding(4, 8, 4, 8)
            }

            tools.addView(
                tool,
                LinearLayout.LayoutParams(62, 56)
            )
        }

        main.addView(
            tools,
            LinearLayout.LayoutParams(68, -1)
        )

        // ACTUAL CHART VIEW
        val chart = CandlestickChartView(this)

        main.addView(
            chart,
            LinearLayout.LayoutParams(0, -1, 1f)
        )

        // WATCHLIST
        val watchlist = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(10, 12, 10, 8)
        }

        val watchTitle = TextView(this).apply {
            text = "WATCHLIST"
            textSize = 14f
            setTextColor(white)
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

        for (itemName in symbols) {
            val item = TextView(this).apply {
                text = itemName
                textSize = 14f
                setTextColor(muted)
                setPadding(4, 11, 4, 11)
            }

            watchlist.addView(item)
        }

        main.addView(
            watchlist,
            LinearLayout.LayoutParams(180, -1)
        )

        root.addView(
            main,
            LinearLayout.LayoutParams(0, 0, 1f)
        )

        // BOTTOM
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 6, 12, 6)
            setBackgroundColor(panel)
        }

        val status = TextView(this).apply {
            text = "ICT SIGNAL: WAITING FOR MARKET DATA"
            textSize = 13f
            setTextColor(muted)
        }

        bottom.addView(
            status,
            LinearLayout.LayoutParams(0, 52, 1f)
        )

        val buy = Button(this).apply {
            text = "BUY"
        }

        bottom.addView(
            buy,
            LinearLayout.LayoutParams(110, 52)
        )

        val sell = Button(this).apply {
            text = "SELL"
        }

        bottom.addView(
            sell,
            LinearLayout.LayoutParams(110, 52)
        )

        root.addView(bottom)

        setContentView(root)
    }
}
