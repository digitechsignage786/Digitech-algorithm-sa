package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

import android.content.pm.ActivityInfo
class MainActivity : AppCompatActivity() {

    private lateinit var chart: CandlestickChartView
    private lateinit var symbolText: TextView
    private lateinit var timeframeText: TextView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var statusText: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var watchlist: LinearLayout
    private lateinit var streamStatusText: TextView

    private var selectedSymbol = "EUR/USD"
    private var selectedTimeframe = "5m"

    private val favourites = mutableListOf<String>()

    private var refreshThread: Thread? = null
    @Volatile private var stopRefresh = false
    @Volatile private var requestSerial = 0

    private val httpClient = OkHttpClient.Builder().build()
    private var marketWebSocket: WebSocket? = null
    private var heartbeatThread: Thread? = null
    private var reconnectThread: Thread? = null
    @Volatile private var websocketStop = false
    @Volatile private var reconnectInProgress = false

    private val liveCandles = mutableListOf<Candle>()
    private val liveLock = Any()
    private var liveTickCount = 0

    // Bar Replay state. It uses the real historical OHLC already loaded into
    // the chart; no synthetic/demo candles are generated.
    private val replayHandler = Handler(Looper.getMainLooper())
    private var replayMode = false
    private var replayIndex = -1
    private var replaySpeed = 1.0f
    private var replayRunnable: Runnable? = null
    private val historicalCandles = mutableListOf<Candle>()

    private val symbols = arrayOf(
        "EUR/USD",
        "GBP/USD",
        "USD/JPY",
        "AUD/USD",
        "USD/CAD",
        "USD/CHF",
        "XAU/USD",
        "BTC/USD",
        "ETH/USD",
        "AAPL",
        "MSFT",
        "TSLA",
        "NVDA",
        "AMZN"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(10, 13, 18)
        window.navigationBarColor = Color.rgb(10, 13, 18)

        loadFavourites()
        buildUI()
        refreshToolbar()
        loadMarketData()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun buildUI() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(7, 10, 14))

        // Compact professional trading layout:
        // top controls -> timeframe -> chart -> chart tools -> navigation.
        // The chart itself receives the largest possible flexible area.

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(4), 0, dp(4), 0)
        topBar.setBackgroundColor(Color.rgb(13, 17, 23))

        fun topButton(label: String, width: Int = 30, size: Float = 12f, action: (() -> Unit)? = null) {
            val b = TextView(this)
            b.text = label
            b.textSize = size
            b.setTextColor(Color.LTGRAY)
            b.gravity = Gravity.CENTER
            b.setSingleLine(true)
            b.includeFontPadding = false
            if (action != null) b.setOnClickListener { action() }
            topBar.addView(
                b,
                LinearLayout.LayoutParams(dp(width), dp(34))
            )
        }

        // Compact top controls: everything fits in portrait without clipping.
        topButton("☰", 30, 18f) { showTools() }
        topButton("DA", 34, 14f)

        symbolText = TextView(this)
        symbolText.text = selectedSymbol
        symbolText.textSize = 14f
        symbolText.setTextColor(Color.WHITE)
        symbolText.setTypeface(null, android.graphics.Typeface.BOLD)
        symbolText.gravity = Gravity.CENTER_VERTICAL
        symbolText.setSingleLine(true)
        symbolText.ellipsize = null
        symbolText.setPadding(dp(2), 0, dp(2), 0)
        symbolText.setOnClickListener { showSymbolDialog() }

        topBar.addView(
            symbolText,
            LinearLayout.LayoutParams(
                dp(82),
                dp(34)
            )
        )

        topButton("+", 28, 19f) { showSymbolDialog() }
        topButton("⌕", 28, 20f) { showSymbolDialog() }
        topButton("IND", 36, 8f) { showTools() }
        topButton("⛶", 30, 17f)
        topButton("⚙", 30, 15f) { showTools() }

        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )

        // TIMEFRAME ROW — compact, like a professional charting terminal.
        val timeframeScroll = android.widget.HorizontalScrollView(this)
        timeframeScroll.isHorizontalScrollBarEnabled = false
        timeframeScroll.setBackgroundColor(Color.rgb(10, 14, 19))

        val timeframeBar = LinearLayout(this)
        timeframeBar.orientation = LinearLayout.HORIZONTAL
        timeframeBar.gravity = Gravity.CENTER_VERTICAL
        timeframeBar.setPadding(dp(5), 0, dp(5), 0)

        val timeframes = arrayOf("1m", "5m", "15m", "30m", "1H", "4H", "1D")

        val timeframeButtons = mutableListOf<TextView>()

        fun refreshTimeframeButtons() {
            timeframeButtons.forEach { b ->
                val active = b.text.toString() == selectedTimeframe
                b.setTextColor(
                    if (active) Color.rgb(35, 170, 255) else Color.LTGRAY
                )
                b.setTypeface(
                    null,
                    if (active) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL
                )
            }
        }

        for (tf in timeframes) {
            val button = TextView(this)
            button.text = tf
            button.textSize = 10f
            button.gravity = Gravity.CENTER
            button.setTextColor(Color.LTGRAY)
            button.setSingleLine(true)
            button.setPadding(dp(7), 0, dp(7), 0)
            button.isClickable = true
            button.isFocusable = true
            button.contentDescription = "Timeframe $tf"

            button.setOnClickListener {
                selectedTimeframe = tf
                timeframeText.text = selectedTimeframe
                refreshTimeframeButtons()
                refreshToolbar()
                loadMarketData()
            }

            timeframeButtons.add(button)
            timeframeBar.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(28)
                )
            )
        }

        refreshTimeframeButtons()

        val indicatorButton = TextView(this)
        indicatorButton.text = "Indicators"
        indicatorButton.textSize = 9f
        indicatorButton.gravity = Gravity.CENTER
        indicatorButton.setTextColor(Color.LTGRAY)
        indicatorButton.setPadding(dp(8), 0, dp(8), 0)
        indicatorButton.setOnClickListener { showTools() }
        timeframeBar.addView(
            indicatorButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28)
            )
        )

        timeframeScroll.addView(
            timeframeBar,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(28)
            )
        )

        root.addView(
            timeframeScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(28)
            )
        )

        timeframeText = TextView(this)
        timeframeText.text = selectedTimeframe
        timeframeText.visibility = View.GONE

        // COMPACT PRICE/STATUS ROW.
        val infoBar = LinearLayout(this)
        infoBar.orientation = LinearLayout.HORIZONTAL
        infoBar.gravity = Gravity.CENTER_VERTICAL
        infoBar.setPadding(dp(7), 0, dp(7), 0)
        infoBar.setBackgroundColor(Color.rgb(8, 12, 17))

        priceText = TextView(this)
        priceText.text = "--"
        priceText.textSize = 12f
        priceText.setTextColor(Color.WHITE)
        priceText.gravity = Gravity.CENTER_VERTICAL
        priceText.setSingleLine(true)

        infoBar.addView(
            priceText,
            LinearLayout.LayoutParams(0, dp(22), 1f)
        )

        changeText = TextView(this)
        changeText.text = "--"
        changeText.textSize = 9f
        changeText.setTextColor(Color.rgb(40, 205, 155))
        changeText.gravity = Gravity.CENTER
        changeText.setSingleLine(true)
        infoBar.addView(
            changeText,
            LinearLayout.LayoutParams(dp(72), dp(22))
        )

        statusText = TextView(this)
        statusText.text = "LIVE"
        statusText.textSize = 9f
        statusText.setTextColor(Color.rgb(40, 205, 155))
        statusText.gravity = Gravity.CENTER
        statusText.setSingleLine(true)
        infoBar.addView(
            statusText,
            LinearLayout.LayoutParams(dp(52), dp(22))
        )

        root.addView(
            infoBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(22)
            )
        )

        // MAIN AREA: chart gets all remaining height.
        val mainArea = LinearLayout(this)
        mainArea.orientation = LinearLayout.HORIZONTAL
        mainArea.setBackgroundColor(Color.rgb(5, 9, 13))

        toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.VERTICAL
        toolbar.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        toolbar.setPadding(0, 0, 0, 0)
        toolbar.setBackgroundColor(Color.rgb(10, 14, 19))

        // ONLY the drawing tools are expanded here.
        // The surrounding UI, chart size, watchlist and bottom layout are unchanged.
        addToolbarButton("⌖") { chart.setDrawingTool(DrawingTool.CROSSHAIR) }
        addToolbarButton("╱") { chart.setDrawingTool(DrawingTool.TREND_LINE) }
        addToolbarButton("↗") { chart.setDrawingTool(DrawingTool.TREND_LINE) }
        addToolbarButton("—") { chart.setDrawingTool(DrawingTool.HORIZONTAL_LINE) }
        addToolbarButton("│") { chart.setDrawingTool(DrawingTool.HORIZONTAL_LINE) }
        addToolbarButton("□") { chart.setDrawingTool(DrawingTool.RECTANGLE) }
        addToolbarButton("○") { chart.setDrawingTool(DrawingTool.RECTANGLE) }
        addToolbarButton("➤") { chart.setDrawingTool(DrawingTool.TREND_LINE) }
        addToolbarButton("▱") { chart.setDrawingTool(DrawingTool.TREND_LINE) }
        addToolbarButton("F") { chart.setDrawingTool(DrawingTool.MEASURE) }
        addToolbarButton("✎") { chart.setDrawingTool(DrawingTool.TREND_LINE) }
        addToolbarButton("T") { chart.setDrawingTool(DrawingTool.MEASURE) }
        addToolbarButton("↔") { chart.setDrawingTool(DrawingTool.MEASURE) }
        addToolbarButton("×") { chart.clearDrawings() }

        mainArea.addView(
            toolbar,
            LinearLayout.LayoutParams(
                dp(34),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        chart = CandlestickChartView(this)
        chart.setBackgroundColor(Color.rgb(5, 9, 13))

        mainArea.addView(
            chart,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        // Narrow watchlist — enough for symbols, without stealing chart width.
        watchlist = LinearLayout(this)
        watchlist.orientation = LinearLayout.VERTICAL
        watchlist.setPadding(dp(1), 0, dp(1), 0)
        watchlist.setBackgroundColor(Color.rgb(10, 14, 19))

        val watchTitle = TextView(this)
        watchTitle.text = "WATCH"
        watchTitle.textSize = 8f
        watchTitle.setTextColor(Color.GRAY)
        watchTitle.gravity = Gravity.CENTER
        watchlist.addView(
            watchTitle,
            LinearLayout.LayoutParams(dp(68), dp(24))
        )

        addWatchItem("EUR/USD")
        addWatchItem("GBP/USD")
        addWatchItem("USD/JPY")
        addWatchItem("XAU/USD")
        addWatchItem("BTC/USD")

        mainArea.addView(
            watchlist,
            LinearLayout.LayoutParams(
                dp(72),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            mainArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // Small ICT status strip — chart remains the dominant element.
        val signalBox = LinearLayout(this)
        signalBox.orientation = LinearLayout.VERTICAL
        signalBox.setPadding(dp(6), 0, dp(6), 0)
        signalBox.setBackgroundColor(Color.rgb(13, 17, 23))

        val signalTitle = TextView(this)
        signalTitle.text = "ICT AUTO ANALYSIS"
        signalTitle.textSize = 8f
        signalTitle.setTextColor(Color.LTGRAY)
        signalTitle.setSingleLine(true)

        streamStatusText = TextView(this)
        streamStatusText.text = "WEBSOCKET: CONNECTING"
        streamStatusText.textSize = 8f
        streamStatusText.setTextColor(Color.YELLOW)
        streamStatusText.setSingleLine(true)
        streamStatusText.ellipsize = android.text.TextUtils.TruncateAt.END

        val signal = TextView(this)
        signal.text = "Waiting for live market data..."
        signal.textSize = 8f
        signal.setTextColor(Color.WHITE)
        signal.setSingleLine(true)
        signal.ellipsize = android.text.TextUtils.TruncateAt.END

        signalBox.addView(signalTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(11)
        ))
        signalBox.addView(streamStatusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(11)
        ))
        signalBox.addView(signal, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(11)
        ))

        root.addView(
            signalBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(31)
            )
        )

        // CHART TOOL ROW — similar spacing to the supplied trading UI.
        val chartToolRow = LinearLayout(this)
        chartToolRow.orientation = LinearLayout.HORIZONTAL
        chartToolRow.gravity = Gravity.CENTER_VERTICAL
        chartToolRow.setBackgroundColor(Color.rgb(10, 14, 19))

        fun chartTool(label: String, width: Int = 42, action: (() -> Unit)? = null) {
            val b = TextView(this)
            b.text = label
            b.textSize = 10f
            b.setTextColor(Color.LTGRAY)
            b.gravity = Gravity.CENTER
            b.setSingleLine(true)
            if (action != null) b.setOnClickListener { action() }
            chartToolRow.addView(b, LinearLayout.LayoutParams(dp(width), dp(32)))
        }

        chartTool(selectedSymbol, 70) { showSymbolDialog() }
        chartTool(selectedTimeframe, 46)
        chartTool("✎", 42) { chart.setDrawingTool(DrawingTool.TREND_LINE) }
        chartTool("▥", 42) { showTools() }
        chartTool("▦", 42) { showIndicators() }
        chartTool("＋", 42) { chart.setDrawingTool(DrawingTool.CROSSHAIR) }
        chartTool("↻", 42) { showReplay() }
        chartTool("◫", 42) { showBrokers() }
        chartTool("▱", 42) { showTools() }
        chartTool("•••", 48) { showTools() }

        val spacer = View(this)
        chartToolRow.addView(
            spacer,
            LinearLayout.LayoutParams(0, dp(34), 1f)
        )

        chartTool("↶", 38)
        chartTool("↷", 38)
        chartTool("⛶", 38)

        // Put the complete tool row inside a horizontal scroller.
        // Nothing is clipped on narrow portrait screens; the user can swipe
        // the tool row when there are more controls than available width.
        val chartToolScroll = android.widget.HorizontalScrollView(this)
        chartToolScroll.isHorizontalScrollBarEnabled = false
        chartToolScroll.setFillViewport(true)
        chartToolScroll.setBackgroundColor(Color.rgb(10, 14, 19))
        chartToolScroll.addView(
            chartToolRow,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
        )

        root.addView(
            chartToolScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )

        // Bottom navigation.
        val bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER_VERTICAL
        bottomBar.setBackgroundColor(Color.rgb(13, 17, 23))

        addBottomButton(bottomBar, "WATCHLIST")
        addBottomButton(bottomBar, "CHART")
        addBottomButton(bottomBar, "EXPLORE")
        addBottomButton(bottomBar, "COMMUNITY")
        addBottomButton(bottomBar, "MENU")

        root.addView(
            bottomBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )

        setContentView(root)

        // Landscape gets even tighter chrome so the chart dominates.
        if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            topBar.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)
            )
            timeframeScroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(25)
            )
            infoBar.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(20)
            )
            signalBox.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(25)
            )
            chartToolScroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(31)
            )
            chartToolRow.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, dp(31)
            )
            bottomBar.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)
            )
        }
    }

    private fun addToolbarButton(label: String, action: () -> Unit) {
        val button = TextView(this)
        button.text = label
        button.textSize = 15f
        button.setTextColor(Color.LTGRAY)
        button.gravity = Gravity.CENTER
        button.setOnClickListener { action() }

        toolbar.addView(
            button,
            LinearLayout.LayoutParams(dp(30), dp(34))
        )
    }

    private fun addBottomButton(parent: LinearLayout, label: String) {
        val button = TextView(this)
        button.text = label
        button.textSize = 8f
        button.setTextColor(Color.LTGRAY)
        button.gravity = Gravity.CENTER

        button.setOnClickListener {
            when (label) {
                "WATCHLIST" -> showWatchlist()
                "CHART" -> chart.setDrawingTool(DrawingTool.CROSSHAIR)
                "EXPLORE" -> showExplore()
                "COMMUNITY" -> showCommunity()
                "MENU" -> showMenu()
            }
        }

        parent.addView(
            button,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )
    }

    private fun addWatchItem(symbol: String) {
        val item = TextView(this)
        item.text = symbol
        item.textSize = 8f
        item.setTextColor(Color.LTGRAY)
        item.gravity = Gravity.CENTER
        item.setPadding(1, 3, 1, 3)

        item.setOnClickListener {
            selectedSymbol = symbol
            refreshToolbar()
            loadMarketData()
        }

        watchlist.addView(
            item,
            LinearLayout.LayoutParams(dp(54), dp(30))
        )
    }

    private fun showSymbolDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Select Market")
            .setItems(symbols) { _, which ->
                selectedSymbol = symbols[which]
                refreshToolbar()
                loadMarketData()
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showTools() {
        val items = arrayOf(
            "Crosshair",
            "Trend Line",
            "Horizontal Line",
            "Rectangle",
            "Measure",
            "Clear Drawings"
        )

        android.app.AlertDialog.Builder(this)
            .setTitle("Chart Tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chart.setDrawingTool(DrawingTool.CROSSHAIR)
                    1 -> chart.setDrawingTool(DrawingTool.TREND_LINE)
                    2 -> chart.setDrawingTool(DrawingTool.HORIZONTAL_LINE)
                    3 -> chart.setDrawingTool(DrawingTool.RECTANGLE)
                    4 -> chart.setDrawingTool(DrawingTool.MEASURE)
                    5 -> chart.clearDrawings()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showSignals() {
        android.app.AlertDialog.Builder(this)
            .setTitle("ICT Signals")
            .setMessage(
                "ICT Auto Analysis\n\n" +
                        "Market Structure\n" +
                        "Liquidity Sweep\n" +
                        "BOS / MSS / CHoCH\n" +
                        "FVG\n" +
                        "Order Block\n" +
                        "Premium / Discount\n" +
                        "Entry / SL / TP\n\n" +
                        "Waiting for sufficient live OHLC data."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBacktest() {
        android.app.AlertDialog.Builder(this)
            .setTitle("ICT Backtest")
            .setMessage(
                "Symbol: $selectedSymbol\n" +
                        "Timeframe: $selectedTimeframe\n\n" +
                        "Backtest engine will use real OHLC data."
            )
            .setPositiveButton("CLOSE", null)
            .show()
    }


    private fun showIndicators() {
        val items = arrayOf(
            "Moving Average",
            "EMA",
            "VWAP",
            "RSI",
            "MACD",
            "ICT Auto Analysis"
        )

        android.app.AlertDialog.Builder(this)
            .setTitle("Indicators")
            .setItems(items) { _, which ->
                if (which == items.lastIndex) {
                    showSignals()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showBrokers() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Brokers")
            .setItems(
                arrayOf(
                    "Paper Trading",
                    "MT5 — connect later",
                    "Broker connection settings"
                )
            ) { _, which ->
                if (which == 1) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("MT5")
                        .setMessage(
                            "MT5 execution can be connected to this chart later. " +
                                "No real-money order is sent by this screen."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showReplay() {
        synchronized(historicalCandles) {
            if (historicalCandles.size < 20) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Bar Replay")
                    .setMessage("Load more real historical candles first.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
        }

        stopReplay()
        stopMarketStreams()
        replayMode = true

        synchronized(historicalCandles) {
            replayIndex = (historicalCandles.size * 0.55f).toInt()
                .coerceIn(10, historicalCandles.lastIndex)
            chart.setCandles(historicalCandles.subList(0, replayIndex + 1))
        }

        statusText.text = "REPLAY"
        statusText.setTextColor(Color.rgb(255, 190, 50))
        streamStatusText.text = "BAR REPLAY — real historical candles"
        streamStatusText.setTextColor(Color.rgb(255, 190, 50))

        val speeds = arrayOf("0.5x", "1x", "2x", "4x")
        android.app.AlertDialog.Builder(this)
            .setTitle("Bar Replay")
            .setSingleChoiceItems(
                speeds,
                when (replaySpeed) {
                    0.5f -> 0
                    2f -> 2
                    4f -> 3
                    else -> 1
                }
            ) { dialog, which ->
                replaySpeed = when (which) {
                    0 -> 0.5f
                    2 -> 2f
                    3 -> 4f
                    else -> 1f
                }
                dialog.dismiss()
                startReplayLoop()
            }
            .setNeutralButton("STEP", null)
            .setPositiveButton("PLAY", null)
            .setNegativeButton("EXIT", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener {
                            stepReplay()
                        }
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {
                            startReplayLoop()
                            dialog.dismiss()
                        }
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                        .setOnClickListener {
                            stopReplay()
                            dialog.dismiss()
                            loadMarketData()
                        }
                }
            }
            .show()
    }

    private fun stepReplay() {
        if (!replayMode) return

        synchronized(historicalCandles) {
            if (replayIndex >= historicalCandles.lastIndex) {
                stopReplay()
                return
            }

            replayIndex += 1
            chart.setCandles(
                historicalCandles.subList(0, replayIndex + 1)
            )
        }
    }

    private fun startReplayLoop() {
        if (!replayMode) return

        stopReplayLoopOnly()

        val delay = (900L / replaySpeed).toLong().coerceAtLeast(120L)

        val runnable = object : Runnable {
            override fun run() {
                if (!replayMode) return

                synchronized(historicalCandles) {
                    if (replayIndex >= historicalCandles.lastIndex) {
                        stopReplay()
                        return
                    }

                    replayIndex += 1
                    chart.setCandles(
                        historicalCandles.subList(0, replayIndex + 1)
                    )
                }

                replayHandler.postDelayed(this, delay)
            }
        }

        replayRunnable = runnable
        replayHandler.post(runnable)
    }

    private fun stopReplayLoopOnly() {
        replayRunnable?.let { replayHandler.removeCallbacks(it) }
        replayRunnable = null
    }

    private fun stopReplay() {
        stopReplayLoopOnly()
        replayMode = false
    }

    private fun showWatchlist() {
        showSymbolDialog()
    }

    private fun showExplore() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Explore")
            .setItems(
                arrayOf(
                    "Forex",
                    "Commodities",
                    "Crypto",
                    "US Stocks",
                    "Indian Stocks"
                ),
                null
            )
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showCommunity() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Community")
            .setMessage(
                "Digitech Algorithm community area.\n\n" +
                    "Signals, ideas and shared chart layouts can be added here."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Digitech Algorithm")
            .setItems(
                arrayOf(
                    "Brokers",
                    "Bar Replay",
                    "Indicators",
                    "ICT Signals",
                    "Backtest",
                    "Chart Tools"
                )
            ) { _, which ->
                when (which) {
                    0 -> showBrokers()
                    1 -> showReplay()
                    2 -> showIndicators()
                    3 -> showSignals()
                    4 -> showBacktest()
                    5 -> showTools()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun refreshToolbar() {
        if (::symbolText.isInitialized) {
            symbolText.text = selectedSymbol
        }

        if (::timeframeText.isInitialized) {
            timeframeText.text = selectedTimeframe
        }
    }

    private fun loadFavourites() {
        favourites.clear()

        val prefs = getSharedPreferences(
            "digitech_preferences",
            MODE_PRIVATE
        )

        val saved = prefs.getString("favourites", "")

        if (!saved.isNullOrBlank()) {
            favourites.addAll(
                saved.split(",").filter { it.isNotBlank() }
            )
        }
    }

    private fun saveFavourites() {
        getSharedPreferences(
            "digitech_preferences",
            MODE_PRIVATE
        )
            .edit()
            .putString("favourites", favourites.joinToString(","))
            .apply()
    }

    private fun loadMarketData() {
        if (!::chart.isInitialized) return

        requestSerial++
        val serial = requestSerial

        stopReplay()
        replayMode = false
        replayIndex = -1

        stopMarketStreams()
        chart.clearCandles()

        synchronized(liveLock) {
            liveCandles.clear()
        }

        priceText.text = "--"
        changeText.text = "--"
        liveTickCount = 0
        statusText.text = "HISTORY"
        statusText.setTextColor(Color.YELLOW)
        streamStatusText.text = "WEBSOCKET: CONNECTING"
        streamStatusText.setTextColor(Color.YELLOW)

        val apiKey = BuildConfig.TWELVE_DATA_API_KEY

        if (apiKey.isBlank()) {
            statusText.text = "NO KEY"
            statusText.setTextColor(Color.RED)
            streamStatusText.text = "WEBSOCKET: NO API KEY"
            streamStatusText.setTextColor(Color.RED)
            return
        }

        thread(name = "HistoricalLoader") {
            try {
                // Load the largest practical single historical block first.
                // More history can later be loaded in date-ranged batches.
                val candles = fetchHistoricalCandles(
                    apiKey = apiKey,
                    outputSize = 5000
                )

                if (candles.isEmpty()) {
                    throw Exception("No historical OHLC candles")
                }

                synchronized(liveLock) {
                    liveCandles.clear()
                    liveCandles.addAll(candles)
                }

                synchronized(historicalCandles) {
                    historicalCandles.clear()
                    historicalCandles.addAll(candles)
                }

                val latest = candles.last()
                val previous = if (candles.size >= 2) {
                    candles[candles.size - 2]
                } else {
                    null
                }

                runOnUiThread {
                    if (serial != requestSerial || isFinishing || isDestroyed) return@runOnUiThread

                    chart.setCandles(candles)
                    updatePriceHeader(latest, previous)

                    statusText.text = "HISTORY"
                    statusText.setTextColor(Color.rgb(120, 170, 255))
                }

                // Start the actual tick stream only after history is ready.
                connectLivePriceStream(apiKey, serial)

            } catch (e: Exception) {
                runOnUiThread {
                    if (serial != requestSerial || isFinishing || isDestroyed) return@runOnUiThread

                    statusText.text = "NO DATA"
                    statusText.setTextColor(Color.RED)
                    priceText.text = "--"
                    changeText.text = "--"
                    chart.clearCandles()
                }
            }
        }
    }

    private fun fetchHistoricalCandles(
        apiKey: String,
        outputSize: Int
    ): List<Candle> {
        var connection: HttpURLConnection? = null

        try {
            val symbol = convertSymbolForApi(selectedSymbol)
            val interval = intervalFor(selectedTimeframe)

            val encodedSymbol = URLEncoder.encode(symbol, "UTF-8")
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")

            val urlString =
                "https://api.twelvedata.com/time_series" +
                        "?symbol=$encodedSymbol" +
                        "&interval=$interval" +
                        "&outputsize=$outputSize" +
                        "&apikey=$encodedKey"

            connection = URL(urlString)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode

            val stream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                        ?: throw Exception("HTTP $responseCode")
                }

            val response = stream.bufferedReader().use { it.readText() }

            if (response.isBlank()) {
                throw Exception("Empty API response")
            }

            val json = JSONObject(response)

            val apiStatus = json.optString("status", "")
            val apiCode = json.optInt("code", 0)

            if (apiStatus.equals("error", ignoreCase = true) || apiCode != 0) {
                val message = json.optString(
                    "message",
                    "Twelve Data error"
                )
                throw Exception(
                    if (apiCode != 0) "$message (code $apiCode)" else message
                )
            }

            val values = json.optJSONArray("values")
                ?: throw Exception(
                    json.optString(
                        "message",
                        "No historical values"
                    )
                )

            val result = mutableListOf<Candle>()

            // Twelve Data returns newest first. Reverse it for chart order.
            for (i in values.length() - 1 downTo 0) {
                val item = values.optJSONObject(i) ?: continue

                val timeString = item.optString("datetime", "")
                if (timeString.isBlank()) continue

                val open = item.optDouble("open", Double.NaN)
                val high = item.optDouble("high", Double.NaN)
                val low = item.optDouble("low", Double.NaN)
                val close = item.optDouble("close", Double.NaN)

                if (
                    !open.isFinite() ||
                    !high.isFinite() ||
                    !low.isFinite() ||
                    !close.isFinite()
                ) {
                    continue
                }

                result.add(
                    Candle(
                        time = parseTime(timeString),
                        open = open.toFloat(),
                        high = high.toFloat(),
                        low = low.toFloat(),
                        close = close.toFloat()
                    )
                )
            }

            return result
        } finally {
            connection?.disconnect()
        }
    }

    private fun connectLivePriceStream(
        apiKey: String,
        serial: Int
    ) {
        if (serial != requestSerial) return

        websocketStop = false

        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val request = Request.Builder()
            .url("wss://ws.twelvedata.com/v1/quotes/price?apikey=$encodedKey")
            .build()

        marketWebSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    if (serial != requestSerial || websocketStop) {
                        webSocket.close(1000, "stopped")
                        return
                    }

                    val payload = JSONObject()
                        .put("action", "subscribe")
                        .put(
                            "params",
                            JSONObject().put(
                                "symbols",
                                convertSymbolForApi(selectedSymbol)
                            )
                        )

                    webSocket.send(payload.toString())

                    startHeartbeat(webSocket, serial)

                    runOnUiThread {
                        if (serial == requestSerial) {
                            statusText.text = "LIVE"
                            statusText.setTextColor(Color.rgb(30, 200, 140))
                            streamStatusText.text = "WEBSOCKET: CONNECTED — waiting for tick"
                            streamStatusText.setTextColor(Color.rgb(30, 200, 140))
                        }
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    if (serial != requestSerial || websocketStop) return

                    val event = try {
                        JSONObject(text).optString("event", "")
                    } catch (_: Exception) {
                        ""
                    }

                    if (event.equals("subscribe-status", ignoreCase = true)) {
                        handleSubscribeStatus(text, serial)
                    } else if (event.equals("price", ignoreCase = true)) {
                        handleLivePrice(text, serial)
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    if (serial != requestSerial || websocketStop) return

                    runOnUiThread {
                        if (serial == requestSerial) {
                            statusText.text = "LIVE OFF"
                            statusText.setTextColor(Color.YELLOW)
                            streamStatusText.text = "WEBSOCKET: RECONNECTING..."
                            streamStatusText.setTextColor(Color.YELLOW)
                        }
                    }

                    scheduleLiveReconnect(apiKey, serial)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    if (serial != requestSerial || websocketStop) return

                    runOnUiThread {
                        if (serial == requestSerial) {
                            statusText.text = "LIVE OFF"
                            statusText.setTextColor(Color.YELLOW)
                            streamStatusText.text = "WEBSOCKET: RECONNECTING..."
                            streamStatusText.setTextColor(Color.YELLOW)
                        }
                    }

                    scheduleLiveReconnect(apiKey, serial)
                }
            }
        )
    }

    private fun scheduleLiveReconnect(
        apiKey: String,
        serial: Int
    ) {
        if (serial != requestSerial || websocketStop || reconnectInProgress) return

        reconnectInProgress = true
        reconnectThread?.interrupt()

        reconnectThread = thread(name = "TwelveDataReconnect") {
            try {
                Thread.sleep(2500L)

                if (!websocketStop && serial == requestSerial) {
                    runOnUiThread {
                        if (serial == requestSerial && !isFinishing && !isDestroyed) {
                            streamStatusText.text = "WEBSOCKET: CONNECTING..."
                            streamStatusText.setTextColor(Color.YELLOW)
                        }
                    }
                    connectLivePriceStream(apiKey, serial)
                }
            } catch (_: InterruptedException) {
                // Stream was intentionally stopped or replaced.
            } finally {
                reconnectInProgress = false
            }
        }
    }

    private fun startHeartbeat(
        webSocket: WebSocket,
        serial: Int
    ) {
        heartbeatThread?.interrupt()

        heartbeatThread = thread(name = "TwelveDataHeartbeat") {
            while (
                !websocketStop &&
                serial == requestSerial &&
                !Thread.currentThread().isInterrupted
            ) {
                try {
                    Thread.sleep(10_000L)
                } catch (_: InterruptedException) {
                    break
                }

                if (websocketStop || serial != requestSerial) break

                val heartbeat = JSONObject()
                    .put("action", "heartbeat")

                webSocket.send(heartbeat.toString())
            }
        }
    }

    private fun handleSubscribeStatus(
        message: String,
        serial: Int
    ) {
        try {
            val json = JSONObject(message)

            val status = json.optString("status", "")
            val symbol = json.optString("symbol", selectedSymbol)
            val messageText = json.optString(
                "message",
                json.optString("reason", "")
            )

            runOnUiThread {
                if (serial != requestSerial || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                when {
                    status.equals("ok", ignoreCase = true) ||
                            status.equals("success", ignoreCase = true) -> {
                        streamStatusText.text =
                            "WEBSOCKET: SUBSCRIBED — " + symbol + " | waiting for tick"
                        streamStatusText.setTextColor(Color.rgb(30, 200, 140))
                    }

                    status.equals("warning", ignoreCase = true) -> {
                        streamStatusText.text =
                            if (messageText.isBlank()) "WEBSOCKET: WARNING"
                            else "WEBSOCKET: WARNING — $messageText"
                        streamStatusText.setTextColor(Color.YELLOW)
                    }

                    else -> {
                        streamStatusText.text =
                            if (messageText.isBlank()) {
                                "WEBSOCKET: REJECTED"
                            } else {
                                "WEBSOCKET: ERROR"
                            }
                        streamStatusText.setTextColor(Color.RED)

                        statusText.contentDescription =
                            "$symbol: $messageText"
                    }
                }
            }
        } catch (_: Exception) {
            runOnUiThread {
                if (serial == requestSerial) {
                    streamStatusText.text = "WEBSOCKET: BAD MESSAGE"
                    streamStatusText.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun handleLivePrice(
        message: String,
        serial: Int
    ) {
        try {
            val json = JSONObject(message)

            if (!json.optString("event")
                    .equals("price", ignoreCase = true)
            ) {
                return
            }

            val price = when (val raw = json.opt("price")) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }
            if (!price.isFinite()) return

            val timestampSeconds = json.optLong(
                "timestamp",
                System.currentTimeMillis() / 1000L
            )

            val tickTime = timestampSeconds * 1000L
            runOnUiThread {
                if (serial == requestSerial && !isFinishing && !isDestroyed) {
                    liveTickCount += 1
                    streamStatusText.text = String.format(
                        Locale.US,
                        "LIVE TICK #%d | PRICE: %.5f",
                        liveTickCount,
                        price
                    )
                    streamStatusText.setTextColor(Color.rgb(30, 200, 140))
                }
            }

            // Show every real tick immediately in the price header.
            runOnUiThread {
                if (serial == requestSerial && !isFinishing && !isDestroyed) {
                    priceText.text = String.format(Locale.US, "%.5f", price)
                }
            }

            updateCurrentCandle(
                price = price.toFloat(),
                tickTime = tickTime,
                serial = serial
            )
        } catch (_: Exception) {
            // Ignore malformed/non-price stream events.
        }
    }

    private fun updateCurrentCandle(
        price: Float,
        tickTime: Long,
        serial: Int
    ) {
        var latest: Candle
        var previous: Candle? = null
        var snapshot: List<Candle>

        synchronized(liveLock) {
            if (liveCandles.isEmpty()) return

            val current = liveCandles[liveCandles.lastIndex]
            val interval = intervalMillis(selectedTimeframe)

            // Align every live tick to the exact opening time of its
            // selected timeframe. This is important for TradingView-style
            // behaviour: a 5m candle keeps changing tick-by-tick for the
            // whole 5-minute window, while a new candle starts exactly at
            // :00, :05, :10, :15, etc.
            val bucketTime = (tickTime / interval) * interval

            if (current.time == bucketTime) {
                // SAME OPEN CANDLE: update it immediately on every real tick.
                val updated = Candle(
                    time = current.time,
                    open = current.open,
                    high = maxOf(current.high, price),
                    low = minOf(current.low, price),
                    close = price
                )

                liveCandles[liveCandles.lastIndex] = updated
                latest = updated
            } else if (bucketTime > current.time) {
                // NEW TIMEFRAME CANDLE: start exactly on the timeframe boundary.
                latest = Candle(
                    time = bucketTime,
                    open = price,
                    high = price,
                    low = price,
                    close = price
                )
                liveCandles.add(latest)

                if (liveCandles.size > 5500) {
                    liveCandles.removeAt(0)
                }
            } else {
                // Ignore an out-of-order tick.
                return
            }

            if (liveCandles.size >= 2) {
                previous = liveCandles[liveCandles.lastIndex - 1]
            }

            snapshot = liveCandles.toList()
        }

        runOnUiThread {
            if (serial != requestSerial || isFinishing || isDestroyed) return@runOnUiThread

            chart.setCandles(snapshot)
            updatePriceHeader(latest, previous)

            statusText.text = "LIVE"
            statusText.setTextColor(Color.rgb(30, 200, 140))
        }
    }

    private fun updatePriceHeader(
        latest: Candle,
        previous: Candle?
    ) {
        priceText.text = formatPrice(latest.close)

        if (previous == null) {
            changeText.text = "--"
            changeText.setTextColor(Color.LTGRAY)
            return
        }

        val change = latest.close - previous.close
        val percent =
            if (previous.close != 0f) {
                change / previous.close * 100f
            } else {
                0f
            }

        changeText.text = String.format(
            Locale.US,
            "%+.5f  %+.2f%%",
            change,
            percent
        )

        changeText.setTextColor(
            if (change >= 0f) {
                Color.rgb(30, 200, 140)
            } else {
                Color.rgb(235, 65, 85)
            }
        )
    }

    private fun intervalMillis(timeframe: String): Long {
        return when (timeframe) {
            "1m" -> 60_000L
            "5m" -> 5 * 60_000L
            "15m" -> 15 * 60_000L
            "30m" -> 30 * 60_000L
            "1H" -> 60 * 60_000L
            "4H" -> 4 * 60 * 60_000L
            "1D" -> 24 * 60 * 60_000L
            else -> 5 * 60_000L
        }
    }

    private fun stopMarketStreams() {
        websocketStop = true

        heartbeatThread?.interrupt()
        heartbeatThread = null

        reconnectThread?.interrupt()
        reconnectThread = null
        reconnectInProgress = false

        marketWebSocket?.close(1000, "switching market")
        marketWebSocket = null

        refreshThread?.interrupt()
        refreshThread = null
    }

    private fun convertSymbolForApi(symbol: String): String {
        return when (symbol) {
            "EUR/USD" -> "EUR/USD"
            "GBP/USD" -> "GBP/USD"
            "USD/JPY" -> "USD/JPY"
            "AUD/USD" -> "AUD/USD"
            "USD/CAD" -> "USD/CAD"
            "USD/CHF" -> "USD/CHF"
            "XAU/USD" -> "XAU/USD"
            "BTC/USD" -> "BTC/USD"
            "ETH/USD" -> "ETH/USD"
            else -> symbol
        }
    }

    private fun intervalFor(timeframe: String): String {
        return when (timeframe) {
            "1m" -> "1min"
            "5m" -> "5min"
            "15m" -> "15min"
            "30m" -> "30min"
            "1H" -> "1h"
            "4H" -> "4h"
            "1D" -> "1day"
            else -> "5min"
        }
    }

    private fun parseTime(value: String): Long {
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )

        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getDefault()
                val date = sdf.parse(value)

                if (date != null) {
                    return date.time
                }
            } catch (_: Exception) {
            }
        }

        return System.currentTimeMillis()
    }

    private fun formatPrice(price: Float): String {
        return if (price >= 100f) {
            String.format(Locale.US, "%.2f", price)
        } else {
            String.format(Locale.US, "%.5f", price)
        }
    }

    override fun onDestroy() {
        stopRefresh = true
        requestSerial++
        stopMarketStreams()
        httpClient.dispatcher.executorService.shutdown()

        saveFavourites()
        super.onDestroy()
    }
}
