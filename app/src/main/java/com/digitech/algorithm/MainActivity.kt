package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(7, 13, 21)
    private val panel = Color.rgb(13, 21, 32)
    private val panel2 = Color.rgb(18, 28, 41)
    private val blue = Color.rgb(30, 120, 230)
    private val green = Color.rgb(20, 190, 145)
    private val red = Color.rgb(235, 65, 85)
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

        // =========================================================
        // TOP HEADER
        // =========================================================

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 6, 10, 6)
            setBackgroundColor(panel)
        }

        val logo = TextView(this).apply {
            text = "DA"
            textSize = 22f
            setTextColor(white)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(48, 50)
        )

        val appTitle = TextView(this).apply {
            text = "Digitech Algorithm\nTrade Smart | Trade With Logic"
            textSize = 11f
            setTextColor(white)
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            appTitle,
            LinearLayout.LayoutParams(190, 50)
        )

        val symbol = TextView(this).apply {
            text = "●  EURUSD  ▾"
            textSize = 15f
            setTextColor(white)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 0)
        }

        header.addView(
            symbol,
            LinearLayout.LayoutParams(145, 50)
        )

        val timeframes = arrayOf(
            "1m", "5m", "15m", "30m",
            "1H", "4H", "D", "W", "M"
        )

        for (tf in timeframes) {

            val button = TextView(this).apply {
                text = tf
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(
                    if (tf == "15m") white else muted
                )
                setBackgroundColor(
                    if (tf == "15m") blue else Color.TRANSPARENT
                )
            }

            header.addView(
                button,
                LinearLayout.LayoutParams(48, 44)
            )
        }

        val indicators = TextView(this).apply {
            text = "◫  Indicators"
            textSize = 13f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(10, 0, 10, 0)
        }

        header.addView(
            indicators,
            LinearLayout.LayoutParams(115, 50)
        )

        val alert = TextView(this).apply {
            text = "◉  Alert"
            textSize = 13f
            setTextColor(white)
            gravity = Gravity.CENTER
        }

        header.addView(
            alert,
            LinearLayout.LayoutParams(80, 50)
        )

        val replay = TextView(this).apply {
            text = "◁  Replay"
            textSize = 13f
            setTextColor(white)
            gravity = Gravity.CENTER
        }

        header.addView(
            replay,
            LinearLayout.LayoutParams(85, 50)
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                60
            )
        )

        // =========================================================
        // SECONDARY INFO BAR
        // =========================================================

        val infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 0)
            setBackgroundColor(bg)
        }

        val info = TextView(this).apply {
            text = "EURUSD · 15 · LIVE     O 1.08452   H 1.08518   L 1.08437   C 1.08496"
            textSize = 12f
            setTextColor(muted)
        }

        infoBar.addView(
            info,
            LinearLayout.LayoutParams(0, 42, 1f)
        )

        val ictStatus = TextView(this).apply {
            text = "● ICT AUTO DRAWING: ON"
            textSize = 12f
            setTextColor(green)
            gravity = Gravity.CENTER
        }

        infoBar.addView(
            ictStatus,
            LinearLayout.LayoutParams(190, 42)
        )

        root.addView(
            infoBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                42
            )
        )

        // =========================================================
        // MAIN CONTENT
        // =========================================================

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
        }

        // ---------------------------------------------------------
        // LEFT DRAWING TOOLBAR
        // ---------------------------------------------------------

        val leftTools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(panel)
            setPadding(3, 4, 3, 4)
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
            "⌂",
            "⌗",
            "◉",
            "⌘",
            "↶",
            "▣"
        )

        for (toolName in tools) {

            val tool = TextView(this).apply {
                text = toolName
                textSize = 20f
                setTextColor(white)
                gravity = Gravity.CENTER
                setPadding(2, 5, 2, 5)
            }

            leftTools.addView(
                tool,
                LinearLayout.LayoutParams(54, 45)
            )
        }

        content.addView(
            leftTools,
            LinearLayout.LayoutParams(
                62,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        // ---------------------------------------------------------
        // CENTER CHART
        // ---------------------------------------------------------

        val chartContainer = FrameLayout(this).apply {
            setBackgroundColor(
                Color.rgb(7, 14, 22)
            )
        }

        val chart = CandlestickChartView(this)

        chartContainer.addView(
            chart,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Chart overlay: ICT labels
        val chartOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 0, 0)
        }

        val ictLabel = TextView(this).apply {
            text = "ICT STRUCTURE"
            textSize = 11f
            setTextColor(green)
        }

        chartOverlay.addView(ictLabel)

        val concepts = TextView(this).apply {
            text = "BOS   MSS   CHoCH   FVG   OB   LIQUIDITY"
            textSize = 10f
            setTextColor(muted)
        }

        chartOverlay.addView(concepts)

        chartContainer.addView(
            chartOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Bottom chart tabs
        val chartTabs = TextView(this).apply {
            text = "1D   5D   1M   3M   6M   YTD   1Y   5Y   All"
            textSize = 10f
            setTextColor(muted)
            setPadding(12, 4, 12, 4)
            setBackgroundColor(panel)
        }

        val tabParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            30
        ).apply {
            gravity = Gravity.BOTTOM
        }

        chartContainer.addView(
            chartTabs,
            tabParams
        )

        content.addView(
            chartContainer,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        // ---------------------------------------------------------
        // RIGHT PANEL
        // ---------------------------------------------------------

        val rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(8, 8, 8, 6)
        }

        val watchHeader = TextView(this).apply {
            text = "WATCHLIST                         +"
            textSize = 14f
            setTextColor(white)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 4, 4, 10)
        }

        rightPanel.addView(watchHeader)

        val marketTabs = TextView(this).apply {
            text = "FOREX       COMMODITIES       CRYPTO"
            textSize = 10f
            setTextColor(muted)
            setPadding(4, 4, 4, 10)
        }

        rightPanel.addView(marketTabs)

        val watchItems = arrayOf(
            "🇪🇺 EURUSD       1.08496   +0.37%",
            "🇬🇧 GBPUSD       1.27644   +0.28%",
            "🥇 XAUUSD       3414.76   +0.62%",
            "₿ BTCUSD       67480.32   +1.26%",
            "♦ ETHUSD        3248.10   +1.18%",
            "🇯🇵 USDJPY       148.76    +0.09%",
            "NIFTY           —         —",
            "RELIANCE        —         —"
        )

        for (itemText in watchItems) {

            val item = TextView(this).apply {
                text = itemText
                textSize = 11f
                setTextColor(muted)
                setPadding(4, 9, 4, 9)
            }

            rightPanel.addView(item)
        }

        val divider = TextView(this).apply {
            text = "────────────────────"
            textSize = 10f
            setTextColor(Color.rgb(45, 60, 75))
        }

        rightPanel.addView(divider)

        val signalTitle = TextView(this).apply {
            text = "ICT SIGNALS"
            textSize = 13f
            setTextColor(white)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 8, 4, 8)
        }

        rightPanel.addView(signalTitle)

        val buySignal = TextView(this).apply {
            text = "🟢 BUY (A+)       5R\n\nEURUSD · 15m\nEntry: —   SL: —\nTP1: —   TP2: —   TP3: —\n\nWaiting for real market data"
            textSize = 11f
            setTextColor(green)
            setPadding(8, 8, 8, 12)
            setBackgroundColor(
                Color.rgb(12, 40, 38)
            )
        }

        rightPanel.addView(
            buySignal,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            )
        )

        val sellSignal = TextView(this).apply {
            text = "🔴 SELL (B+)\n\nSignal engine waiting..."
            textSize = 11f
            setTextColor(red)
            setPadding(8, 10, 8, 10)
        }

        rightPanel.addView(sellSignal)

        content.addView(
            rightPanel,
            LinearLayout.LayoutParams(
                285,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // =========================================================
        // BOTTOM NAVIGATION
        // =========================================================

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)
        }

        val navItems = arrayOf(
            "⌂\nHome",
            "▣\nChart",
            "◎\nSignals",
            "◈\nAnalysis",
            "↻\nBacktest",
            "⇄\nTrade",
            "•••\nMore"
        )

        for (navText in navItems) {

            val nav = TextView(this).apply {
                text = navText
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(
                    if (navText.contains("Chart"))
                        blue
                    else
                        muted
                )
            }

            bottom.addView(
                nav,
                LinearLayout.LayoutParams(
                    0,
                    58,
                    1f
                )
            )
        }

        root.addView(
            bottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                58
            )
        )

        setContentView(root)
    }
}
