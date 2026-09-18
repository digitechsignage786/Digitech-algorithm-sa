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

        chart.clearCandles()
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

        topBar.addView(
            logo,
            LinearLayout.LayoutParams(44, 44)
        )

        symbolText = TextView(this)
        symbolText.text = selectedSymbol
        symbolText.textSize = 16f
        symbolText.setTextColor(Color.WHITE)
        symbolText.setTypeface(null, android.graphics.Typeface.BOLD)
        symbolText.gravity = Gravity.CENTER_VERTICAL
        symbolText.setPadding(12, 0, 8, 0)

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
        search.textSize = 25f
        search.setTextColor(Color.LTGRAY)
        search.gravity = Gravity.CENTER

        search.setOnClickListener {
            showSymbolDialog()
        }

        topBar.addView(
            search,
            LinearLayout.LayoutParams(44, 44)
        )

        val more = TextView(this)
        more.text = "⋮"
        more.textSize = 27f
        more.setTextColor(Color.WHITE)
        more.gravity = Gravity.CENTER

        more.setOnClickListener {
            showTools()
        }

        topBar.addView(
            more,
            LinearLayout.LayoutParams(40, 44)
        )

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
            button.setPadding(11, 7, 11, 7)

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
            LinearLayout.LayoutParams(
                90,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(this)
        statusText.text = "LIVE"
        statusText.textSize = 10f
        statusText.setTextColor(Color.GREEN)
        statusText.gravity = Gravity.CENTER

        infoBar.addView(
            statusText,
            LinearLayout.LayoutParams(
                62,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
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

        addToolbarButton("＋") {
            chart.setDrawingTool(DrawingTool.CROSSHAIR)
        }

        addToolbarButton("╱") {
            chart.setDrawingTool(DrawingTool.TREND_LINE)
        }

        addToolbarButton("—") {
            chart.setDrawingTool(DrawingTool.HORIZONTAL_LINE)
        }

        addToolbarButton("□") {
            chart.setDrawingTool(DrawingTool.RECTANGLE)
        }

        addToolbarButton("↔") {
            chart.setDrawingTool(DrawingTool.MEASURE)
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

        watchlist.addView(
            watchTitle,
            LinearLayout.LayoutParams(76, 28)
        )

        addWatchItem("EUR/USD")
        addWatchItem("GBP/USD")
        addWatchItem("USD/JPY")
        addWatchItem("XAU/USD")
        addWatchItem("BTC/USD")

        mainArea.addView(
            watchlist,
            LinearLayout.LayoutParams(
                80,
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

    private fun addToolbarButton(
        label: String,
        action: () -> Unit
    ) {

        val button = TextView(this)

        button.text = label
        button.textSize = 18f
        button.setTextColor(Color.LTGRAY)
        button.gravity = Gravity.CENTER

        button.setOnClickListener {
            action()
        }

        toolbar.addView(
            button,
            LinearLayout.LayoutParams(38, 43)
        )
    }

    private fun addBottomButton(
        parent: LinearLayout,
        label: String
    ) {

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

                "CHART" -> {
                    chart.setDrawingTool(
                        DrawingTool.CROSSHAIR
                    )
                }
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

    private fun addWatchItem(
        symbol: String
    ) {

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

                selectedSymbol =
                    symbols[which]

                refreshToolbar()
                loadMarketData()
            }
            .setNegativeButton(
                "CLOSE",
                null
            )
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

                    0 -> chart.setDrawingTool(
                        DrawingTool.CROSSHAIR
                    )

                    1 -> chart.setDrawingTool(
                        DrawingTool.TREND_LINE
                    )

                    2 -> chart.setDrawingTool(
                        DrawingTool.HORIZONTAL_LINE
                    )

                    3 -> chart.setDrawingTool(
                        DrawingTool.RECTANGLE
                    )

                    4 -> chart.setDrawingTool(
                        DrawingTool.MEASURE
                    )

                    5 -> chart.clearDrawings()
                }
            }
            .setNegativeButton(
                "CLOSE",
                null
            )
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

        val prefs =
            getSharedPreferences(
                "digitech_preferences",
                MODE_PRIVATE
            )

        val saved =
            prefs.getString(
                "favourites",
                ""
            )

        if (!saved.isNullOrBlank()) {

            favourites.addAll(
                saved.split(",")
                    .filter {
                        it.isNotBlank()
                    }
            )
        }
    }

    private fun saveFavourites() {

        getSharedPreferences(
            "digitech_preferences",
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "favourites",
                favourites.joinToString(",")
            )
            .apply()
    }

    private fun loadMarketData() {

        if (!::chart.isInitialized) {
            return
        }

        statusText.text = "LIVE"
        statusText.setTextColor(Color.YELLOW)

        priceText.text = "--"
        changeText.text = "--"

        chart.clearCandles()

        val apiKey =
            BuildConfig.TWELVE_DATA_API_KEY

        if (apiKey.isBlank()) {

            statusText.text = "NO KEY"
            statusText.setTextColor(Color.RED)

            return
        }

        val symbol =
            convertSymbolForApi(
                selectedSymbol
            )

        val interval =
            intervalFor(
                selectedTimeframe
            )

        thread {

            var connection:
                    HttpURLConnection? = null

            try {

                val encodedSymbol =
                    URLEncoder.encode(
                        symbol,
                        "UTF-8"
                    )

                val encodedKey =
                    URLEncoder.encode(
                        apiKey,
                        "UTF-8"
                    )

                val urlString =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=$encodedSymbol" +
                            "&interval=$interval" +
                            "&outputsize=120" +
                            "&apikey=$encodedKey"

                connection =
                    URL(urlString)
                        .openConnection()
                            as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                if (connection.responseCode != 200) {
                    throw Exception(
                        "HTTP ${connection.responseCode}"
                    )
                }

                val response =
                    connection.inputStream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                val json =
                    JSONObject(response)

                if (
                    json.optString("status")
                        .equals(
                            "error",
                            ignoreCase = true
                        )
                ) {

                    throw Exception(
                        json.optString(
                            "message",
                            "API error"
                        )
                    )
                }

                val values =
                    json.optJSONArray("values")
                        ?: throw Exception(
                            "No market data"
                        )

                val candleList =
                    mutableListOf<Candle>()

                for (
                    i in values.length() - 1 downTo 0
                ) {

                    val item =
                        values.getJSONObject(i)

                    val time =
                        parseTime(
                            item.optString(
                                "datetime"
                            )
                        )

                    val open =
                        item.optDouble(
                            "open",
                            Double.NaN
                        )

                    val high =
                        item.optDouble(
                            "high",
                            Double.NaN
                        )
                        val low =
                        item.optDouble(
                            "low",
                            Double.NaN
                        )

                    val close =
                        item.optDouble(
                            "close",
                            Double.NaN
                        )

                    if (
                        !open.isNaN() &&
                        !high.isNaN() &&
                        !low.isNaN() &&
                        !close.isNaN()
                    ) {

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
                }

                runOnUiThread {

                    if (candleList.isNotEmpty()) {

                        chart.setCandles(
                            candleList
                        )

                        val latest =
                            candleList.last().close

                        priceText.text =
                            formatPrice(latest)

                        if (
                            candleList.size >= 2
                        ) {

                            val previous =
                                candleList[
                                    candleList.size - 2
                                ].close

                            val difference =
                                latest - previous

                            val percent =
                                if (previous != 0f) {
                                    difference /
                                            previous *
                                            100f
                                } else {
                                    0f
                                }

                            changeText.text =
                                String.format(
                                    Locale.US,
                                    "%+.2f%%",
                                    percent
                                )

                            changeText.setTextColor(
                                if (difference >= 0f) {
                                    Color.rgb(
                                        22,
                                        190,
                                        145
                                    )
                                } else {
                                    Color.rgb(
                                        235,
                                        65,
                                        85
                                    )
                                }
                            )
                        }

                        statusText.text = "LIVE"
                        statusText.setTextColor(Color.GREEN)

                    } else {

                        statusText.text = "NO DATA"
                        statusText.setTextColor(Color.RED)
                    }
                }

            } catch (
                e: Exception
            ) {

                runOnUiThread {

                    statusText.text = "OFFLINE"
                    statusText.setTextColor(Color.RED)

                    priceText.text = "--"
                    changeText.text = "--"

                    chart.clearCandles()
                }

            } finally {

                connection?.disconnect()
            }
        }
    }

    private fun convertSymbolForApi(
        symbol: String
    ): String {

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

    private fun parseTime(
        value: String
    ): Long {

        val formats =
            arrayOf(
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
                    return date.time
                }

            } catch (
                ignored: Exception
            ) {
            }
        }

        return System.currentTimeMillis()
    }

    private fun formatPrice(
        price: Float
    ): String {

        return when {

            price >= 1000f -> {
                String.format(
                    Locale.US,
                    "%.2f",
                    price
                )
            }

            price >= 10f -> {
                String.format(
                    Locale.US,
                    "%.3f",
                    price
                )
            }

            else -> {
                String.format(
                    Locale.US,
                    "%.5f",
                    price
                )
            }
        }
    }
}
