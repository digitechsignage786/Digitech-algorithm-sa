package com.digitech.algorithm

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
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

        window.setStatusBarColor(Color.rgb(10, 13, 18))
        window.setNavigationBarColor(Color.rgb(10, 13, 18))

        loadFavourites()
        buildUI()
        refreshToolbar()

        // No demo candles are loaded.
        chart.clearCandles()

        loadMarketData()
    }

    // ---------------------------------------------------------
    // MAIN UI
    // ---------------------------------------------------------

    private fun buildUI() {

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(10, 13, 18))

        // TOP BAR
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(12, 8, 12, 8)
        topBar.setBackgroundColor(Color.rgb(18, 22, 29))

        val logo = TextView(this)
        logo.text = "DA"
        logo.textSize = 20f
        logo.setTextColor(Color.WHITE)
        logo.setTypeface(null, android.graphics.Typeface.BOLD)
        logo.gravity = Gravity.CENTER

        val logoParams = LinearLayout.LayoutParams(48, 48)
        logoParams.setMargins(0, 0, 10, 0)
        topBar.addView(logo, logoParams)

        symbolText = TextView(this)
        symbolText.text = selectedSymbol
        symbolText.textSize = 16f
        symbolText.setTextColor(Color.WHITE)
        symbolText.setTypeface(null, android.graphics.Typeface.BOLD)
        symbolText.gravity = Gravity.CENTER_VERTICAL
        symbolText.setPadding(8, 0, 8, 0)

        symbolText.setOnClickListener {
            showSymbolDialog()
        }

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
        search.textSize = 26f
        search.setTextColor(Color.LTGRAY)
        search.gravity = Gravity.CENTER
        search.setPadding(12, 0, 12, 0)

        search.setOnClickListener {
            showSymbolDialog()
        }

        topBar.addView(
            search,
            LinearLayout.LayoutParams(
                48,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        val tools = TextView(this)
        tools.text = "⋮"
        tools.textSize = 28f
        tools.setTextColor(Color.WHITE)
        tools.gravity = Gravity.CENTER

        tools.setOnClickListener {
            showTools()
        }

        topBar.addView(
            tools,
            LinearLayout.LayoutParams(
                42,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                58
            )
        )

        // TIMEFRAME BAR
        val timeframeBar = LinearLayout(this)
        timeframeBar.orientation = LinearLayout.HORIZONTAL
        timeframeBar.gravity = Gravity.CENTER_VERTICAL
        timeframeBar.setPadding(8, 4, 8, 4)
        timeframeBar.setBackgroundColor(Color.rgb(14, 18, 24))

        val timeframes = arrayOf(
            "1m",
            "5m",
            "15m",
            "30m",
            "1H",
            "4H",
            "1D"
        )

        for (tf in timeframes) {
            val button = TextView(this)
            button.text = tf
            button.textSize = 12f
            button.gravity = Gravity.CENTER
            button.setTextColor(Color.LTGRAY)
            button.setPadding(12, 8, 12, 8)

            button.setOnClickListener {
                selectedTimeframe = tf
                timeframeText.text = tf
                loadMarketData()
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
                42
            )
        )

        // OHLC / STATUS BAR
        val infoBar = LinearLayout(this)
        infoBar.orientation = LinearLayout.HORIZONTAL
        infoBar.gravity = Gravity.CENTER_VERTICAL
        infoBar.setPadding(10, 4, 10, 4)
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
        changeText.textSize = 13f
        changeText.setTextColor(Color.LTGRAY)
        changeText.gravity = Gravity.CENTER

        infoBar.addView(
            changeText,
            LinearLayout.LayoutParams(
                100,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(this)
        statusText.text = "LIVE"
        statusText.textSize = 11f
        statusText.setTextColor(Color.GREEN)
        statusText.gravity = Gravity.CENTER

        infoBar.addView(
            statusText,
            LinearLayout.LayoutParams(
                60,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            infoBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                34
            )
        )

        // MAIN AREA
        val mainArea = LinearLayout(this)
        mainArea.orientation = LinearLayout.HORIZONTAL
        mainArea.setBackgroundColor(Color.rgb(7, 10, 14))

        // LEFT TOOLBAR
        toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.VERTICAL
        toolbar.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        toolbar.setPadding(3, 6, 3, 6)
        toolbar.setBackgroundColor(Color.rgb(13, 17, 23))

        addToolbarButton("＋") {
            chart.setDrawingTool(CandlestickChartView.DrawingTool.CROSSHAIR)
        }

        addToolbarButton("╱") {
            chart.setDrawingTool(CandlestickChartView.DrawingTool.TREND_LINE)
        }

        addToolbarButton("—") {
            chart.setDrawingTool(CandlestickChartView.DrawingTool.HORIZONTAL_LINE)
        }

        addToolbarButton("□") {
            chart.setDrawingTool(CandlestickChartView.DrawingTool.RECTANGLE)
        }

        addToolbarButton("↔") {
            chart.setDrawingTool(CandlestickChartView.DrawingTool.MEASURE)
        }

        addToolbarButton("×") {
            chart.clearDrawings()
        }

        mainArea.addView(
            toolbar,
            LinearLayout.LayoutParams(
                42,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        // CHART
        chart = CandlestickChartView(this)
        chart.setBackgroundColor(Color.rgb(7, 10, 14))

        mainArea.addView(
            chart,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        // RIGHT WATCHLIST
        watchlist = LinearLayout(this)
        watchlist.orientation = LinearLayout.VERTICAL
        watchlist.setPadding(5, 6, 5, 6)
        watchlist.setBackgroundColor(Color.rgb(13, 17, 23))

        val watchTitle = TextView(this)
        watchTitle.text = "WATCH"
        watchTitle.textSize = 10f
        watchTitle.setTextColor(Color.GRAY)
        watchTitle.gravity = Gravity.CENTER
        watchlist.addView(
            watchTitle,
            LinearLayout.LayoutParams(
                72,
                30
            )
        )

        addWatchItem("EUR/USD")
        addWatchItem("GBP/USD")
        addWatchItem("USD/JPY")
        addWatchItem("XAU/USD")
        addWatchItem("BTC/USD")

        mainArea.addView(
            watchlist,
            LinearLayout.LayoutParams(
                78,
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

        // ICT SIGNAL BAR
        val signalBox = LinearLayout(this)
        signalBox.orientation = LinearLayout.VERTICAL
        signalBox.setPadding(12, 6, 12, 6)
        signalBox.setBackgroundColor(Color.rgb(17, 22, 29))

        val signalTitle = TextView(this)
        signalTitle.text = "ICT AUTO ANALYSIS"
        signalTitle.textSize = 11f
        signalTitle.setTextColor(Color.LTGRAY)

        val signal = TextView(this)
        signal.text = "Waiting for live market data..."
        signal.textSize = 13f
        signal.setTextColor(Color.WHITE)

        signalBox.addView(signalTitle)
        signalBox.addView(signal)

        root.addView(
            signalBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                52
            )
        )

        // BOTTOM BAR
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
                48
            )
        )

        setContentView(root)
    }

    // ---------------------------------------------------------
    // TOOLBAR
    // ---------------------------------------------------------

    private fun addToolbarButton(
        label: String,
        action: () -> Unit
    ) {
        val button = TextView(this)
        button.text = label
        button.textSize = 19f
        button.setTextColor(Color.LTGRAY)
        button.gravity = Gravity.CENTER
        button.setPadding(0, 8, 0, 8)

        button.setOnClickListener {
            action()
        }

        toolbar.addView(
            button,
            LinearLayout.LayoutParams(
                38,
                44
            )
        )
    }

    private fun addBottomButton(
        parent: LinearLayout,
        label: String
    ) {
        val button = TextView(this)
        button.text = label
        button.textSize = 10f
        button.setTextColor(Color.LTGRAY)
        button.gravity = Gravity.CENTER

        button.setOnClickListener {
            if (label == "TOOLS") {
                showTools()
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

    // ---------------------------------------------------------
    // WATCHLIST
    // ---------------------------------------------------------

    private fun addWatchItem(symbol: String) {

        val item = TextView(this)
        item.text = symbol
        item.textSize = 10f
        item.setTextColor(Color.LTGRAY)
        item.gravity = Gravity.CENTER
        item.setPadding(2, 10, 2, 10)

        item.setOnClickListener {
            selectedSymbol = symbol
            refreshToolbar()
            loadMarketData()
        }

        watchlist.addView(
            item,
            LinearLayout.LayoutParams(
                72,
                42
            )
        )
    }

    // ---------------------------------------------------------
    // MARKET DATA
    // ---------------------------------------------------------

    private fun loadMarketData() {

        statusText.text = "LIVE"
        statusText.setTextColor(Color.YELLOW)

        chart.clearCandles()

        val apiKey = BuildConfig.TWELVE_DATA_API_KEY

        if (apiKey.isBlank()) {
            statusText.text = "NO KEY"
            statusText.setTextColor(Color.RED)
            return
        }

        val symbol = convertSymbolForApi(selectedSymbol)
        val interval = intervalFor(selectedTimeframe)

        thread {

            try {

                val urlString =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=${java.net.URLEncoder.encode(symbol, "UTF-8")}" +
                            "&interval=$interval" +
                            "&outputsize=120" +
                            "&apikey=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"

                val connection =
                    URL(urlString).openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val responseCode = connection.responseCode

                if (responseCode != 200) {
                    throw Exception("HTTP $responseCode")
                }

                val response =
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                connection.disconnect()

                val json = JSONObject(response)

                if (json.has("status") &&
                    json.optString("status") == "error"
                ) {
                    throw Exception(
                        json.optString(
                            "message",
                            "API error"
                        )
                    )
                }

                val values = json.optJSONArray("values")
                    ?: throw Exception("No market data")

                val candles =
                    mutableListOf<CandlestickChartView.Candle>()

                for (i in values.length() - 1 downTo 0) {

                    val obj = values.getJSONObject(i)

                    val time =
                        parseTime(
                            obj.optString("datetime")
                        )

                    val open =
                        obj.optDouble("open", Double.NaN)

                    val high =
                        obj.optDouble("high", Double.NaN)

                    val low =
                        obj.optDouble("low", Double.NaN)

                    val close =
                        obj.optDouble("close", Double.NaN)

                    if (
                        !open.isNaN() &&
                        !high.isNaN() &&
                        !low.isNaN() &&
                        !close.isNaN()
                    ) {

                        candles.add(
                            CandlestickChartView.Candle(
                                time = time,
                                open = open.toFloat(),
                                high = high.toFloat(),
                                low = low.toFloat(),
                                close = close.toFloat()
                            )
                        )
                    }
                }

                runOnUiThread {

                    if (candles.isNotEmpty()) {

                        chart.setCandles(candles)

                        val last =
                            candles.last().close

                        priceText.text =
                            String.format(
                                Locale.US,
                                "%.5f",
                                last
                            )

                        statusText.text = "LIVE"
                        statusText.setTextColor(Color.GREEN)

                    } else {

                        statusText.text = "NO DATA"
                        statusText.setTextColor(Color.RED)
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    statusText.text = "OFFLINE"
                    statusText.setTextColor(Color.RED)

                    priceText.text = "--"
                    changeText.text = "--"

                    chart.clearCandles()
                }
            }
        }
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

            "AAPL" -> "AAPL"
            "MSFT" -> "MSFT"
            "TSLA" -> "TSLA"
            "NVDA" -> "NVDA"
            "AMZN" -> "AMZN"

            else -> symbol
        }
    }

    // ---------------------------------------------------------
    // INTERVAL
    // ---------------------------------------------------------

    private fun intervalFor(
        timeframe: String
    ): String {

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

    // ---------------------------------------------------------
    // TIME PARSER
    // ---------------------------------------------------------

    private fun parseTime(
        value: String
    ): Long {

        val formats = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )

        for (format in formats) {

            try {

                val sdf =
                    SimpleDateFormat(
                        format,
                        Locale.US
                    )

                sdf.timeZone =
                    TimeZone.getDefault()

                val date =
                    sdf.parse(value)

                if (date != null) {
            
