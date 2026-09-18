package com.digitech.algorithm

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(7, 11, 17)
    private val panel = Color.rgb(12, 17, 24)
    private val panel2 = Color.rgb(18, 25, 34)
    private val line = Color.rgb(35, 45, 58)
    private val white = Color.WHITE
    private val muted = Color.rgb(145, 157, 173)
    private val green = Color.rgb(28, 190, 145)
    private val red = Color.rgb(235, 70, 88)
    private val blue = Color.rgb(55, 130, 235)

    private lateinit var favouriteBar: LinearLayout
    private lateinit var chart: CandlestickChartView
    private lateinit var ohlcInfo: TextView
    private lateinit var liveStatus: TextView
    private lateinit var signalBox: TextView
    private lateinit var symbolText: TextView

    private var selectedSymbol = "EUR/USD"
    private var selectedInterval = "15min"

    private val tools = arrayOf(
        "Crosshair",
        "Trend Line",
        "Horizontal Line",
        "Vertical Line",
        "Ray",
        "Arrow",
        "Rectangle",
        "Circle",
        "Triangle",
        "Text",
        "Measure",
        "Long Position",
        "Short Position",
        "Fib Retracement",
        "Fib Extension",
        "Brush",
        "Eraser",
        "BOS",
        "MSS",
        "CHoCH",
        "FVG",
        "Order Block",
        "Liquidity",
        "Premium / Discount",
        "OTE",
        "Previous Day High",
        "Previous Day Low"
    )

    private val defaultFav = mutableListOf(
        "Crosshair",
        "Trend Line",
        "Horizontal Line",
        "Rectangle",
        "Text",
        "Measure",
        "Long Position",
        "Short Position"
    )

    private val prefs by lazy {
        getSharedPreferences("DIGITECH_TOOLS", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildUI()

        loadMarketData()
    }

    private fun favourites(): MutableList<String> {
        val saved = prefs.getStringSet("fav", null)

        return if (saved == null) {
            defaultFav.toMutableList()
        } else {
            saved.toMutableList()
        }
    }

    private fun saveFavourites(list: List<String>) {
        prefs.edit()
            .putStringSet("fav", list.toSet())
            .apply()
    }

    private fun buildUI() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // =========================
        // TOP BAR
        // =========================

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(5, 3, 5, 3)
            setBackgroundColor(panel)
        }

        val da = TextView(this).apply {
            text = "DA"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        }

        top.addView(
            da,
            LinearLayout.LayoutParams(42, 48)
        )

        symbolText = TextView(this).apply {
            text = "EUR/USD  ▾"
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(white)
            setPadding(8, 0, 8, 0)

            setOnClickListener {
                showSymbolDialog()
            }
        }

        top.addView(
            symbolText,
            LinearLayout.LayoutParams(105, 48)
        )

        val timeframes = arrayOf(
            "1m",
            "5m",
            "15m",
            "30m",
            "1H",
            "4H",
            "D",
            "W"
        )

        for (tf in timeframes) {

            val t = TextView(this).apply {
                text = tf
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(
                    if (tf == "15m") white else muted
                )

                if (tf == "15m") {
                    setBackgroundColor(blue)
                }

                setOnClickListener {
                    selectedInterval = intervalFor(tf)
                    loadMarketData()
                }
            }

            top.addView(
                t,
                LinearLayout.LayoutParams(38, 38)
            )
        }

        val spacer = Space(this)

        top.addView(
            spacer,
            LinearLayout.LayoutParams(
                0,
                1,
                1f
            )
        )

        val indicators = TextView(this).apply {
            text = "Indicators"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(white)
        }

        top.addView(
            indicators,
            LinearLayout.LayoutParams(78, 48)
        )

        val search = TextView(this).apply {
            text = "⌕"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(white)

            setOnClickListener {
                showSymbolDialog()
            }
        }

        top.addView(
            search,
            LinearLayout.LayoutParams(40, 48)
        )

        root.addView(
            top,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                54
            )
        )

        // =========================
        // OHLC BAR
        // =========================

        val ohlc = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 0, 8, 0)
            setBackgroundColor(panel2)
        }

        ohlcInfo = TextView(this).apply {
            text = "EUR/USD · 15m    Loading real OHLC..."
            textSize = 10f
            setTextColor(muted)
        }

        ohlc.addView(
            ohlcInfo,
            LinearLayout.LayoutParams(
                0,
                38,
                1f
            )
        )

        liveStatus = TextView(this).apply {
            text = "● CONNECTING"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(blue)
        }

        ohlc.addView(
            liveStatus,
            LinearLayout.LayoutParams(
                78,
                38
            )
        )

        root.addView(
            ohlc,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                38
            )
        )

        // =========================
        // MAIN WORKSPACE
        // =========================

        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
        }

        // =========================
        // LEFT TOOLBAR
        // =========================

        favouriteBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(panel)
            setPadding(2, 5, 2, 4)
        }

        workspace.addView(
            favouriteBar,
            LinearLayout.LayoutParams(
                50,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        refreshToolbar()

        // =========================
        // CHART
        // =========================

        val chartFrame = FrameLayout(this).apply {
            setBackgroundColor(bg)
        }

        chart = CandlestickChartView(this)

        chartFrame.addView(
            chart,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val ict = TextView(this).apply {
            text =
                "ICT AUTO DRAWING  •  ON\n" +
                "BOS   MSS   CHoCH   FVG   OB   LIQUIDITY"

            textSize = 9f
            setTextColor(green)
            setPadding(10, 8, 0, 0)
        }

        chartFrame.addView(
            ict,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val emptyStatus = TextView(this).apply {
            text = "DA  •  REAL OHLC DATA"
            textSize = 8f
            setTextColor(muted)
            setPadding(8, 0, 8, 7)
        }

        val emptyParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )

        emptyParams.gravity =
            Gravity.BOTTOM or Gravity.LEFT

        chartFrame.addView(
            emptyStatus,
            emptyParams
        )

        workspace.addView(
            chartFrame,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        // =========================
        // RIGHT PANEL
        // =========================

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(7, 6, 7, 5)
        }

        val watch = TextView(this).apply {
            text = "WATCHLIST                         ＋"
            textSize = 11f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(3, 4, 3, 9)
        }

        right.addView(watch)

        val category = TextView(this).apply {
            text = "FOREX     STOCKS     CRYPTO"
            textSize = 8f
            setTextColor(muted)
            setPadding(3, 0, 3, 8)
        }

        right.addView(category)

        val marketRows = arrayOf(
            "EURUSD      LIVE",
            "GBPUSD      —",
            "USDJPY      —",
            "XAUUSD      —",
            "BTCUSD      —",
            "ETHUSD      —",
            "NIFTY       —",
            "RELIANCE    —"
        )

        for (rowText in marketRows) {

            val row = TextView(this).apply {
                text = rowText
                textSize = 10f
                setTextColor(muted)
                setPadding(4, 7, 4, 7)
            }

            right.addView(row)
        }

        val divider = TextView(this).apply {
            text = "──────────────────"
            textSize = 8f
            setTextColor(line)
        }

        right.addView(divider)

        val signalTitle = TextView(this).apply {
            text = "ICT SIGNAL"
            textSize = 11f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(3, 8, 3, 6)
        }

        right.addView(signalTitle)

        signalBox = TextView(this).apply {
            text =
                "WAITING FOR REAL DATA\n\n" +
                "A+   •   5R\n" +
                "Entry     —\n" +
                "SL        —\n" +
                "TP1       —\n" +
                "TP2       —\n" +
                "TP3       —"

            textSize = 10f
            setTextColor(green)
            setPadding(8, 9, 8, 9)
            setBackgroundColor(
                Color.rgb(11, 37, 34)
            )
        }

        right.addView(
            signalBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                145
            )
        )

        workspace.addView(
            right,
            LinearLayout.LayoutParams(
                220,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            workspace,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // =========================
        // BOTTOM NAVIGATION
        // =========================

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)
        }

        val navigation = arrayOf(
            "⌂\nHome",
            "▣\nChart",
            "◎\nSignals",
            "◈\nAnalysis",
            "↻\nBacktest",
            "⇄\nTrade",
            "•••\nMore"
        )

        for (itemText in navigation) {

            val item = TextView(this).apply {
                text = itemText
                textSize = 8f
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
                    52,
                    1f
                )
            )
        }

        root.addView(
            bottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                52
            )
        )

        setContentView(root)
    }

    // =========================
    // LIVE MARKET DATA
    // =========================

    private fun loadMarketData() {

        val apiKey = BuildConfig.TWELVE_DATA_API_KEY

        if (apiKey.isBlank()) {

            runOnUiThread {

                liveStatus.text = "● NO API KEY"
                liveStatus.setTextColor(red)

                ohlcInfo.text =
                    "EUR/USD · $selectedInterval    API key not available"

                signalBox.text =
                    "DATA CONNECTION ERROR\n\n" +
                    "Twelve Data API key not available."
            }

            return
        }

        runOnUiThread {

            liveStatus.text = "● LOADING"
            liveStatus.setTextColor(blue)

            ohlcInfo.text =
                "EUR/USD · $selectedInterval    Loading real OHLC..."
        }

        thread {

            var connection: HttpURLConnection? = null

            try {

                val encodedSymbol =
                    selectedSymbol.replace("/", "%2F")

                val urlString =
                    "https://api.twelvedata.com/time_series" +
                    "?symbol=$encodedSymbol" +
                    "&interval=$selectedInterval" +
                    "&outputsize=200" +
                    "&apikey=$apiKey"

                val url = URL(urlString)

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val responseCode =
                    connection.responseCode

                if (responseCode != 200) {
                    throw Exception(
                        "HTTP $responseCode"
                    )
                }

                val response =
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                val json =
                    JSONObject(response)

                if (json.has("status") &&
                    json.getString("status")
                        .equals(
                            "error",
                            ignoreCase = true
                        )
                ) {

                    val message =
                        json.optString(
                            "message",
                            "Market data error"
                        )

                    throw Exception(message)
                }

                val values =
                    json.optJSONArray("values")
                        ?: throw Exception(
                            "No OHLC values returned"
                        )

                val result =
                    mutableListOf<Candle>()

                for (i in values.length() - 1 downTo 0) {

                    val item =
                        values.getJSONObject(i)

                    val datetime =
                        item.optString(
                            "datetime"
                        )

                    val open =
                        item
                            .optString("open")
                            .toFloatOrNull()

                    val high =
                        item
                            .optString("high")
                            .toFloatOrNull()

                    val low =
                        item
                            .optString("low")
                            .toFloatOrNull()

                    val close =
                        item
                            .optString("close")
                            .toFloatOrNull()

                    if (
                        open == null ||
                        high == null ||
                        low == null ||
                        close == null
                    ) {
                        continue
                    }

                    val time =
                        parseTime(datetime)

                    result.add(
                        Candle(
                            time = time,
                            open = open,
                            high = high,
                            low = low,
                            close = close
                        )
                    )
                }

                if (result.isEmpty()) {
                    throw Exception(
                        "No valid candles returned"
                    )
                }

                runOnUiThread {

                    chart.setCandles(result)

                    val latest =
                        result.last()

                    ohlcInfo.text =
                        String.format(
                            Locale.US,
                            "EUR/USD · %s    O %.5f   H %.5f   L %.5f   C %.5f",
                            selectedInterval,
                            latest.open,
                            latest.high,
                            latest.low,
                            latest.close
                        )

                    liveStatus.text =
                        "● LIVE"

                    liveStatus.setTextColor(
                        green
                    )

                    signalBox.text =
                        "REAL MARKET DATA\n\n" +
                        "EUR/USD  •  $selectedInterval\n" +
                        "Candles     ${result.size}\n" +
                        "ICT Engine  READY\n\n" +
                        "A+ signal waiting\n" +
                        "for ICT confirmation"
                }

            } catch (e: Exception) {

                runOnUiThread {

                    liveStatus.text =
                        "● ERROR"

                    liveStatus.setTextColor(
                        red
                    )

                    ohlcInfo.text =
                        "EUR/USD · $selectedInterval    Connection error"

                    signalBox.text =
                        "MARKET DATA ERROR\n\n" +
                        e.message.orEmpty()
                }

            } finally {

                connection?.disconnect()
       
