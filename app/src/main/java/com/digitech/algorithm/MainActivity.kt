package com.digitech.algorithm

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(8, 12, 18)
    private val panel = Color.rgb(13, 18, 26)
    private val panel2 = Color.rgb(20, 27, 37)
    private val border = Color.rgb(35, 45, 58)
    private val white = Color.WHITE
    private val muted = Color.rgb(145, 157, 173)
    private val green = Color.rgb(30, 190, 145)
    private val red = Color.rgb(235, 70, 88)
    private val blue = Color.rgb(55, 130, 235)

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
            setPadding(10, 4, 10, 4)
            setBackgroundColor(panel)
        }

        val logo = TextView(this).apply {
            text = "DA"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        }

        top.addView(logo, LinearLayout.LayoutParams(48, 48))

        val symbol = TextView(this).apply {
            text = "EURUSD  ▾"
            textSize = 15f
            setTextColor(white)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 15, 0)
        }

        top.addView(symbol, LinearLayout.LayoutParams(125, 48))

        val tfList = arrayOf("1m", "5m", "15m", "30m", "1H", "4H", "D", "W")

        for (tf in tfList) {
            val v = TextView(this).apply {
                text = tf
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (tf == "15m") white else muted)

                if (tf == "15m") {
                    setBackgroundColor(blue)
                }
            }

            top.addView(v, LinearLayout.LayoutParams(43, 40))
        }

        val indicator = TextView(this).apply {
            text = "Indicators"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(white)
        }

        top.addView(
            indicator,
            LinearLayout.LayoutParams(90, 48)
        )

        val search = TextView(this).apply {
            text = "⌕"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(white)
        }

        top.addView(search, LinearLayout.LayoutParams(45, 48))

        root.addView(
            top,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                56
            )
        )

        // SECOND BAR
        val second = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(panel2)
            setPadding(12, 0, 12, 0)
        }

        val priceInfo = TextView(this).apply {
            text = "EURUSD · 15m     O 1.08452   H 1.08518   L 1.08437   C 1.08496"
            textSize = 11f
            setTextColor(muted)
        }

        second.addView(
            priceInfo,
            LinearLayout.LayoutParams(0, 40, 1f)
        )

        val live = TextView(this).apply {
            text = "● LIVE"
            textSize = 11f
            setTextColor(green)
            gravity = Gravity.CENTER
        }

        second.addView(live, LinearLayout.LayoutParams(70, 40))

        root.addView(
            second,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                40
            )
        )

        // MAIN AREA
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
        }

        // DRAWING TOOLBAR
        val toolsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(panel)
            setPadding(2, 4, 2, 4)
        }

        val tools = arrayOf(
            "⌖",
            "＋",
            "↗",
            "—",
            "↕",
            "□",
            "○",
            "⌁",
            "T",
            "⌗",
            "⌂",
            "↶",
            "↷"
        )

        for (name in tools) {
            val tool = TextView(this).apply {
                text = name
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(muted)
            }

            toolsPanel.addView(
                tool,
                LinearLayout.LayoutParams(48, 44)
            )
        }

        main.addView(
            toolsPanel,
            LinearLayout.LayoutParams(
                52,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        // CHART
        val chartFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 12, 18))
        }

        val chart = CandlestickChartView(this)

        chartFrame.addView(
            chart,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // CHART HEADER OVERLAY
        val chartInfo = TextView(this).apply {
            text = "ICT AUTO DRAWING  •  ON\nBOS   MSS   CHoCH   FVG   OB   LIQUIDITY"
            textSize = 10f
            setTextColor(green)
            setPadding(12, 10, 0, 0)
        }

        chartFrame.addView(
            chartInfo,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // RIGHT BOTTOM STATUS
        val chartStatus = TextView(this).apply {
            text = "DA  •  REAL OHLC DATA REQUIRED"
            textSize = 9f
            setTextColor(muted)
            setPadding(10, 0, 10, 8)
        }

        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )

        statusParams.gravity = Gravity.BOTTOM or Gravity.LEFT

        chartFrame.addView(
            chartStatus,
            statusParams
        )

        main.addView(
            chartFrame,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        // RIGHT WATCHLIST
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(8, 8, 8, 5)
        }

        val watchTitle = TextView(this).apply {
            text = "WATCHLIST                 ＋"
            textSize = 13f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(4, 4, 4, 12)
        }

        right.addView(watchTitle)

        val markets = TextView(this).apply {
            text = "FOREX   STOCKS   CRYPTO"
            textSize = 9f
            setTextColor(muted)
            setPadding(4, 0, 4, 10)
        }

        right.addView(markets)

        val symbols = arrayOf(
            "EURUSD     1.08496",
            "GBPUSD     1.27644",
            "USDJPY     148.76",
            "XAUUSD     3414.76",
            "BTCUSD     67480",
            "ETHUSD     3248",
            "NIFTY      —",
            "RELIANCE   —"
        )

        for (s in symbols) {
            val row = TextView(this).apply {
                text = s
                textSize = 11f
                setTextColor(muted)
                setPadding(5, 9, 5, 9)
            }

            right.addView(row)
        }

        val line = TextView(this).apply {
            text = "────────────────"
            textSize = 9f
            setTextColor(border)
        }

        right.addView(line)

        val signalTitle = TextView(this).apply {
            text = "ICT SIGNAL"
            textSize = 12f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(4, 10, 4, 8)
        }

        right.addView(signalTitle)

        val signal = TextView(this).apply {
            text = "WAITING FOR REAL DATA\n\nA+  •  5R\nEntry: —\nSL: —\nTP1: —\nTP2: —\nTP3: —"
            textSize = 11f
            setTextColor(green)
            setPadding(9, 10, 9, 10)
            setBackgroundColor(Color.rgb(12, 38, 36))
        }

        right.addView(
            signal,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            )
        )

        main.addView(
            right,
            LinearLayout.LayoutParams(
                260,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            main,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // BOTTOM BAR
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)
        }

        val nav = arrayOf(
            "⌂\nHome",
            "▣\nChart",
            "◎\nSignals",
            "◈\nAnalysis",
            "↻\nBacktest",
            "⇄\nTrade",
            "•••\nMore"
        )

        for (itemText in nav) {

            val item = TextView(this).apply {
                text = itemText
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(
                    if (itemText.contains("Chart"))
                        blue
                    else
                        muted
                )
            }

            bottom.addView(
                item,
                LinearLayout.LayoutParams(
                    0,
                    55,
                    1f
                )
            )
        }

        root.addView(
            bottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                55
            )
        )

        setContentView(root)
    }
}
