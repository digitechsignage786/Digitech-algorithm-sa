package com.digitech.algorithm

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    private var selectedSymbol = "EUR/USD"
    private var selectedTimeframe = "5m"

    private val favourites = mutableListOf<String>()

    private var refreshThread: Thread? = null
    @Volatile private var stopRefresh = false
    @Volatile private var requestSerial = 0

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

        signalBox.addView(signalTitle)
        signalBox.addView(signal)

        root.addView(
            signalBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48
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
        stopRefresh = true
        refreshThread?.interrupt()

        chart.clearCandles()
        priceText.text = "--"
        changeText.text = "--"
        statusText.text = "CONNECTING"
        statusText.setTextColor(Color.YELLOW)

        val apiKey = BuildConfig.TWELVE_DATA_API_KEY

        if (apiKey.isBlank()) {
            statusText.text = "NO KEY"
            statusText.setTextColor(Color.RED)
            return
        }

        val serial = requestSerial

        stopRefresh = false

        refreshThread = thread(name = "MarketRefresh") {
            var firstSuccessfulLoad = false

            while (!stopRefresh && !Thread.currentThread().isInterrupted && serial == requestSerial) {
                fetchMarketData(apiKey, serial)

                if (!firstSuccessfulLoad) {
                    firstSuccessfulLoad = true
                }

                try {
                    Thread.sleep(refreshIntervalMillis())
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun refreshIntervalMillis(): Long {
        return when (selectedTimeframe) {
            "1m" -> 15_000L
            "5m" -> 20_000L
            "15m" -> 30_000L
            "30m" -> 30_000L
            "1H" -> 45_000L
            "4H" -> 60_000L
            "1D" -> 60_000L
            else -> 30_000L
        }
    }

    private fun fetchMarketData(apiKey: String, serial: Int) {
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
                        "&outputsize=200" +
                        "&apikey=$encodedKey"

            connection = URL(urlString)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
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

            if (!json.has("values")) {
                throw Exception(
                    json.optString(
                        "message",
                        "No values in API response"
                    )
                )
            }

            val values = json.optJSONArray("values")
                ?: throw Exception("No market values")

            val candleList = mutableListOf<Candle>()

            for (i in values.length() - 1 downTo 0) {
                val item = values.optJSONObject(i) ?: continue

                val timeString = item.optString("datetime", "")
                if (timeString.isBlank()) continue

                val time = parseTime(timeString)

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

                candleList.add(
                    Candle(
                        time = time,
                        open = open.toFloat(),
                        high = high.toFloat(),
                        low = low.toFloat(),
                        close = close.toFloat()
                    )
                )
            }

            if (candleList.isEmpty()) {
                throw Exception("No valid OHLC candles")
            }

            val latest = candleList.last()
            val previous = if (candleList.size >= 2) {
                candleList[candleList.size - 2]
            } else {
                null
            }

            runOnUiThread {
                if (serial != requestSerial || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                chart.setCandles(candleList)

                priceText.text = formatPrice(latest.close)

                if (previous != null) {
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
                } else {
                    changeText.text = "--"
                    changeText.setTextColor(Color.LTGRAY)
                }

                statusText.text = "LIVE"
                statusText.setTextColor(Color.rgb(30, 200, 140))
            }

        } catch (e: Exception) {
            val message = e.message ?: "No market data"

            runOnUiThread {
                if (serial != requestSerial || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                statusText.text = "NO DATA"
                statusText.setTextColor(Color.RED)

                // Keep existing candles during a temporary refresh failure.
                // Only the first failed request should leave the chart empty.
                if (!hasChartData()) {
                    priceText.text = "--"
                    changeText.text = "--"
                }

                statusText.contentDescription = message
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun hasChartData(): Boolean {
        // The chart remains responsible for rendering its current candle set.
        // Returning true after a successful request prevents temporary API
        // refresh failures from blanking an already populated chart.
        return priceText.text.toString() != "--"
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
        refreshThread?.interrupt()
        refreshThread = null

        saveFavourites()
        super.onDestroy()
    }
}
