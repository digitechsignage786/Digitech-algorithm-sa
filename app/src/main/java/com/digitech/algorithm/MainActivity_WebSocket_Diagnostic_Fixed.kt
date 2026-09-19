package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
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
    @Volatile private var websocketStop = false

    private val liveCandles = mutableListOf<Candle>()
    private val liveLock = Any()

    @Volatile private var tickCount = 0
    @Volatile private var lastTickPrice = 0.0

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

    private fun buildUI() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(10, 13, 18))

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(10, 6, 10, 6)
        topBar.setBackgroundColor(Color.rgb(18, 22, 29))

        val logo = TextView(this)
        logo.text = "DA"
        logo.textSize = 18f
        logo.setTextColor(Color.WHITE)
        logo.setTypeface(null, android.graphics.Typeface.BOLD)
        logo.gravity = Gravity.CENTER

        topBar.addView(logo, LinearLayout.LayoutParams(44, 44))

        symbolText = TextView(this)
        symbolText.text = selectedSymbol
        symbolText.textSize = 16f
        symbolText.setTextColor(Color.WHITE)
        symbolText.setTypeface(null, android.graphics.Typeface.BOLD)
        symbolText.gravity = Gravity.CENTER_VERTICAL
        symbolText.setPadding(12, 0, 8, 0)
        symbolText.setOnClickListener { showSymbolDialog() }

        topBar.addView(
            symbolText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        val search = TextView(this)
        search.text = "⌕"
        search.textSize = 25f
        search.setTextColor(Color.LTGRAY)
        search.gravity = Gravity.CENTER
        search.setOnClickListener { showSymbolDialog() }
        topBar.addView(search, LinearLayout.LayoutParams(44, 44))

        val more = TextView(this)
        more.text = "⋮"
        more.textSize = 27f
        more.setTextColor(Color.WHITE)
        more.gravity = Gravity.CENTER
        more.setOnClickListener { showTools() }
        topBar.addView(more, LinearLayout.LayoutParams(40, 44))

        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                54
            )
        )

        val timeframeBar = LinearLayout(this)
        timeframeBar.orientation = LinearLayout.HORIZONTAL
        timeframeBar.gravity = Gravity.CENTER_VERTICAL
        timeframeBar.setPadding(5, 3, 5, 3)
        timeframeBar.setBackgroundColor(Color.rgb(14, 18, 24))

        val timeframes = arrayOf("1m", "5m", "15m", "30m", "1H", "4H", "1D")

        for (tf in timeframes) {
            val button = TextView(this)
            button.text = tf
            button.textSize = 12f
            button.gravity = Gravity.CENTER
            button.setTextColor(Color.LTGRAY)
            button.setPadding(11, 7, 11, 7)

            button.setOnClickListener {
                if (selectedTimeframe != tf) {
                    selectedTimeframe = tf
                    refreshToolbar()
                    loadMarketData()
                }
            }

            timeframeBar.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        timeframeText = TextView(this)
        timeframeText.text = selectedTimeframe
        timeframeText.visibility = View.GONE

        root.addView(
            timeframeBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                40
            )
        )

        val infoBar = LinearLayout(this)
        infoBar.orientation = LinearLayout.HORIZONTAL
        infoBar.gravity = Gravity.CENTER_VERTICAL
        infoBar.setPadding(10, 2, 10, 2)
        infoBar.setBackgroundColor(Color.rgb(11, 15, 20))

        priceText = TextView(this)
        priceText.text = "--"
        priceText.textSize = 14f
        priceText.setTextColor(Color.WHITE)

        infoBar.addView(
            priceText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        changeText = TextView(this)
        changeText.text = "--"
        changeText.textSize = 12f
        changeText.setTextColor(Color.LTGRAY)
        changeText.gravity = Gravity.CENTER

        infoBar.addView(
            changeText,
            LinearLayout.LayoutParams(90, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        statusText = TextView(this)
        statusText.text = "CONNECTING"
        statusText.textSize = 10f
        statusText.setTextColor(Color.YELLOW)
        statusText.gravity = Gravity.CENTER

        infoBar.addView(
            statusText,
            LinearLayout.LayoutParams(72, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        root.addView(
            infoBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                32
            )
        )

        val mainArea = LinearLayout(this)
        mainArea.orientation = LinearLayout.HORIZONTAL
        mainArea.setBackgroundColor(Color.rgb(7, 10, 14))

        toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.VERTICAL
        toolbar.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        toolbar.setPadding(2, 5, 2, 5)
        toolbar.setBackgroundColor(Color.rgb(13, 17, 23))

        addToolbarButton("＋") { chart.setDrawingTool(DrawingTool.CROSSHAIR) }
        addToolbarButton("╱") { chart.setDrawingTool(DrawingTool.TREND_LINE) }
        addToolbarButton("—") { chart.setDrawingTool(DrawingTool.HORIZONTAL_LINE) }
        addToolbarButton("□") { chart.setDrawingTool(DrawingTool.RECTANGLE) }
        addToolbarButton("↔") { chart.setDrawingTool(DrawingTool.MEASURE) }
        addToolbarButton("×") { chart.clearDrawings() }

        mainArea.addView(
            toolbar,
            LinearLayout.LayoutParams(42, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        chart = CandlestickChartView(this)

        mainArea.addView(
            chart,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        watchlist = LinearLayout(this)
        watchlist.orientation = LinearLayout.VERTICAL
        watchlist.setPadding(4, 5, 4, 5)
        watchlist.setBackgroundColor(Color.rgb(13, 17, 23))

        val watchTitle = TextView(this)
        watchTitle.text = "WATCH"
        watchTitle.textSize = 10f
        watchTitle.setTextColor(Color.GRAY)
        watchTitle.gravity = Gravity.CENTER
        watchlist.addView(watchTitle, LinearLayout.LayoutParams(76, 28))

        addWatchItem("EUR/USD")
        addWatchItem("GBP/USD")
        addWatchItem("USD/JPY")
        addWatchItem("XAU/USD")
        addWatchItem("BTC/USD")

        mainArea.addView(
            watchlist,
            LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        root.addView(
            mainArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val signalBox = LinearLayout(this)
        signalBox.orientation = LinearLayout.VERTICAL
        signalBox.setPadding(12, 5, 12, 5)
        signalBox.setBackgroundColor(Color.rgb(17, 22, 29))

        val signalTitle = TextView(this)
        signalTitle.text = "ICT AUTO ANALYSIS"
        signalTitle.textSize = 10f
        signalTitle.setTextColor(Color.LTGRAY)

        val signal = TextView(this)
        signal.text = "Waiting for live market data..."
        signal.textSize = 12f
        signal.setTextColor(Color.WHITE)

        streamStatusText = TextView(this)
        streamStatusText.text = "WEBSOCKET: CONNECTING"
        streamStatusText.textSize = 11f
        streamStatusText.setTextColor(Color.YELLOW)
        streamStatusText.setSingleLine(false)

        signalBox.addView(signalTitle)
        signalBox.addView(signal)
        signalBox.addView(streamStatusText)

        root.addView(
            signalBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                68
            )
        )

        val bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER
        bottomBar.setBackgroundColor(Color.rgb(18, 22, 29))

        addBottomButton(bottomBar, "CHART")
        addBottomButton(bottomBar, "SIGNALS")
        addBottomButton(bottomBar, "BACKTEST")
        addBottomButton(bottomBar, "TOOLS")

        root.addView(
            bottomBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                46
            )
        )

        setContentView(root)
    }

    private fun addToolbarButton(label: String, action: () -> Unit) {
        val button = TextView(this)
        button.text = label
        button.textSize = 18f
        button.setTextColor(Color.LTGRAY)
        button.gravity = Gravity.CENTER
        button.setOnClickListener { action() }

        toolbar.addView(
            button,
            LinearLayout.LayoutParams(38, 43)
        )
    }

    private fun addBottomButton(parent: LinearLayout, label: String) {
        val button = TextView(this)
        button.text = label
        button.textSize = 9f
        button.setTextColor(Color.LTGRAY)
        button.gravity = Gravity.CENTER

        button.setOnClickListener {
            when (label) {
                "TOOLS" -> showTools()
                "SIGNALS" -> showSignals()
                "BACKTEST" -> showBacktest()
                "CHART" -> chart.setDrawingTool(DrawingTool.CROSSHAIR)
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
        item.textSize = 9f
        item.setTextColor(Color.LTGRAY)
        item.gravity = Gravity.CENTER
        item.setPadding(2, 8, 2, 8)

        item.setOnClickListener {
            selectedSymbol = symbol
            refreshToolbar()
            loadMarketData()
        }

        watchlist.addView(
            item,
            LinearLayout.LayoutParams(76, 40)
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

        stopMarketStreams()
        chart.clearCandles()

        synchronized(liveLock) {
            liveCandles.clear()
        }

        tickCount = 0
        lastTickPrice = 0.0

        priceText.text = "--"
        changeText.text = "--"
        statusText.text = "HISTORY"
        statusText.setTextColor(Color.YELLOW)
        streamStatusText.text = "WS: CONNECTING"
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
                            streamStatusText.text = "WEBSOCKET: CONNECTED | WAITING FOR SUBSCRIPTION"
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
                            // Historical candles remain visible if the stream
                            // is temporarily unavailable.
                            statusText.text = "LIVE OFF"
                            statusText.setTextColor(Color.YELLOW)
                            val detail = t.message?.take(90) ?: "Unknown WebSocket error"
                            streamStatusText.text = "WEBSOCKET: ERROR | $detail"
                            streamStatusText.setTextColor(Color.RED)
                        }
                    }
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
                            streamStatusText.text = "WEBSOCKET: CLOSED | $reason (code $code)"
                            streamStatusText.setTextColor(Color.RED)
                        }
                    }
                }
            }
        )
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
                        streamStatusText.text = "WEBSOCKET: SUBSCRIBED | $symbol | WAITING FOR TICK"
                        streamStatusText.setTextColor(Color.rgb(30, 200, 140))
                    }

                    status.equals("warning", ignoreCase = true) -> {
                        streamStatusText.text = "WEBSOCKET: WARNING | $symbol"
                        streamStatusText.setTextColor(Color.YELLOW)
                    }

                    else -> {
                        streamStatusText.text =
                            if (messageText.isBlank()) {
                                "WEBSOCKET: REJECTED | $symbol"
                            } else {
                                "WEBSOCKET: ERROR | $symbol"
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
                    streamStatusText.text = "WEBSOCKET: BAD SUBSCRIPTION MESSAGE"
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

            val price = json.optDouble("price", Double.NaN)
            if (!price.isFinite()) return

            val timestampSeconds = json.optLong(
                "timestamp",
                System.currentTimeMillis() / 1000L
            )

            val tickTime = timestampSeconds * 1000L
            tickCount += 1
            lastTickPrice = price

            runOnUiThread {
                if (serial == requestSerial && !isFinishing && !isDestroyed) {
                    streamStatusText.text = String.format(
                        Locale.US,
                        "LIVE TICK #%d | PRICE: %s",
                        tickCount,
                        formatPrice(price.toFloat())
                    )
                    streamStatusText.setTextColor(Color.rgb(30, 200, 140))
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

            // Historical candle timestamps are the candle-open time.
            // If the tick has crossed the current timeframe boundary,
            // create a new candle. Otherwise update the current candle.
            val sameCandle =
                tickTime >= current.time &&
                        tickTime - current.time < interval

            if (sameCandle) {
                val updated = Candle(
                    time = current.time,
                    open = current.open,
                    high = maxOf(current.high, price),
                    low = minOf(current.low, price),
                    close = price
                )

                liveCandles[liveCandles.lastIndex] = updated
                latest = updated
            } else {
                latest = Candle(
                    time = tickTime,
                    open = price,
                    high = price,
                    low = price,
                    close = price
                )

                if (current.time < tickTime) {
                    liveCandles.add(latest)
                } else {
                    return
                }

                // Keep the in-memory chart reasonably bounded.
                if (liveCandles.size > 5500) {
                    liveCandles.removeAt(0)
                }
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
