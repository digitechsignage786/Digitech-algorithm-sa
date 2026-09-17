package com.digitech.algorithm

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(8, 12, 18)
    private val panel = Color.rgb(13, 18, 26)
    private val panel2 = Color.rgb(20, 27, 37)
    private val border = Color.rgb(38, 48, 62)
    private val white = Color.WHITE
    private val muted = Color.rgb(145, 157, 173)
    private val green = Color.rgb(30, 190, 145)
    private val blue = Color.rgb(55, 130, 235)

    private lateinit var favouritePanel: LinearLayout

    private val allTools = listOf(
        "Crosshair",
        "Trend Line",
        "Horizontal Line",
        "Vertical Line",
        "Ray",
        "Arrow",
        "Parallel Channel",
        "Rectangle",
        "Circle",
        "Triangle",
        "Text",
        "Price Label",
        "Measure",
        "Long Position",
        "Short Position",
        "Fib Retracement",
        "Fib Extension",
        "Fib Projection",
        "Pitchfork",
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

    private val defaultFavourites = mutableListOf(
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
        getSharedPreferences("DA_TOOLS", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildScreen()
    }

    private fun getFavourites(): MutableList<String> {
        val saved = prefs.getStringSet("favourites", null)

        return if (saved == null) {
            defaultFavourites.toMutableList()
        } else {
            saved.toMutableList()
        }
    }

    private fun saveFavourites(list: List<String>) {
        prefs.edit()
            .putStringSet("favourites", list.toSet())
            .apply()
    }

    private fun buildScreen() {

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
            setPadding(6, 3, 6, 3)
            setBackgroundColor(panel)
        }

        val logo = TextView(this).apply {
            text = "DA"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
        }

        top.addView(
            logo,
            LinearLayout.LayoutParams(45, 48)
        )

        val symbol = TextView(this).apply {
            text = "EURUSD ▾"
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(white)
            setPadding(8, 0, 8, 0)
        }

        top.addView(
            symbol,
            LinearLayout.LayoutParams(105, 48)
        )

        val timeframes = arrayOf(
            "1m", "5m", "15m", "30m",
            "1H", "4H", "D", "W"
        )

        for (tf in timeframes) {

            val button = TextView(this).apply {
                text = tf
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(
                    if (tf == "15m") white else muted
                )

                if (tf == "15m") {
                    setBackgroundColor(blue)
                }
            }

            top.addView(
                button,
                LinearLayout.LayoutParams(39, 40)
            )
        }

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
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(white)
        }

        top.addView(
            search,
            LinearLayout.LayoutParams(42, 48)
        )

        root.addView(
            top,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                54
            )
        )

        // =========================
        // SECOND BAR
        // =========================

        val second = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(panel2)
            setPadding(10, 0, 10, 0)
        }

        val priceInfo = TextView(this).apply {
            text =
                "EURUSD · 15m     O 1.08452   H 1.08518   L 1.08437   C 1.08496"
            textSize = 10f
            setTextColor(muted)
        }

        second.addView(
            priceInfo,
            LinearLayout.LayoutParams(0, 38, 1f)
        )

        val live = TextView(this).apply {
            text = "● LIVE"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(green)
        }

        second.addView(
            live,
            LinearLayout.LayoutParams(65, 38)
        )

        root.addView(
            second,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                38
            )
        )

        // =========================
        // MAIN AREA
        // =========================

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
        }

        // =========================
        // FAVOURITE TOOLBAR
        // =========================

        favouritePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(panel)
            setPadding(2, 4, 2, 4)
        }

        main.addView(
            favouritePanel,
            LinearLayout.LayoutParams(
                52,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        refreshFavouriteToolbar()

        // =========================
        // CHART
        // =========================

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

        val chartInfo = TextView(this).apply {
            text =
                "ICT AUTO DRAWING  •  ON\n" +
                "BOS   MSS   CHoCH   FVG   OB   LIQUIDITY"

            textSize = 9f
            setTextColor(green)
            setPadding(10, 9, 0, 0)
        }

        chartFrame.addView(
            chartInfo,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val status = TextView(this).apply {
            text = "DA  •  REAL OHLC DATA REQUIRED"
            textSize = 8f
            setTextColor(muted)
            setPadding(8, 0, 8, 7)
        }

        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )

        statusParams.gravity = Gravity.BOTTOM or Gravity.LEFT

        chartFrame.addView(
            status,
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

        // =========================
        // WATCHLIST
        // =========================

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(7, 7, 7, 4)
        }

        val watchTitle = TextView(this).apply {
            text = "WATCHLIST        ＋"
            textSize = 12f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(3, 3, 3, 10)
        }

        right.addView(watchTitle)

        val markets = TextView(this).apply {
            text = "FOREX   STOCKS   CRYPTO"
            textSize = 8f
            setTextColor(muted)
            setPadding(3, 0, 3, 8)
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

        for (item in symbols) {

            val row = TextView(this).apply {
                text = item
                textSize = 10f
                setTextColor(muted)
                setPadding(4, 8, 4, 8)
            }

            right.addView(row)
        }

        val separator = TextView(this).apply {
            text = "────────────────"
            textSize = 8f
            setTextColor(border)
        }

        right.addView(separator)

        val signalTitle = TextView(this).apply {
            text = "ICT SIGNAL"
            textSize = 11f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(3, 9, 3, 7)
        }

        right.addView(signalTitle)

        val signal = TextView(this).apply {
            text =
                "WAITING FOR REAL DATA\n\n" +
                "A+  •  5R\n" +
                "Entry: —\n" +
                "SL: —\n" +
                "TP1: —\n" +
                "TP2: —\n" +
                "TP3: —"

            textSize = 10f
            setTextColor(green)
            setPadding(8, 9, 8, 9)
            setBackgroundColor(Color.rgb(12, 38, 36))
        }

        right.addView(
            signal,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                145
            )
        )

        main.addView(
            right,
            LinearLayout.LayoutParams(
                225,
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

        // =========================
        // BOTTOM BAR
        // =========================

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
    // FAVOURITE TOOLBAR
    // =========================

    private fun refreshFavouriteToolbar() {

        favouritePanel.removeAllViews()

        val favourites = getFavourites()

        for (toolName in favourites) {

            val icon = getToolIcon(toolName)

            val tool = TextView(this).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(muted)

                setOnClickListener {
                    Toast.makeText(
                        this@MainActivity,
                        toolName,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                setOnLongClickListener {
                    removeFavourite(toolName)
                    true
                }
            }

            favouritePanel.addView(
                tool,
                LinearLayout.LayoutParams(
                    48,
                    42
                )
            )
        }

        // ALL TOOLS BUTTON

        val all = TextView(this).apply {
            text = "☰"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(white)

            setOnClickListener {
                showAllTools()
            }
        }

        favouritePanel.addView(
            all,
            LinearLayout.LayoutParams(
                48,
                48
            )
        )
    }

    private fun getToolIcon(name: String): String {

        return when {

            name.contains("Crosshair") -> "⌖"
            name.contains("Trend") -> "↗"
            name.contains("Horizontal") -> "—"
            name.contains("Vertical") -> "↕"
            name.contains("Ray") -> "➜"
            name.contains("Arrow") -> "➤"
            name.contains("Channel") -> "▥"
            name.contains("Rectangle") -> "□"
            name.contains("Circle") -> "○"
            name.contains("Triangle") -> "△"
            name.contains("Text") -> "T"
            name.contains("Price") -> "⌁"
            name.contains("Measure") -> "↔"
            name.contains("Long") -> "↗"
            name.contains("Short") -> "↘"
            name.contains("Fib") -> "F"
            name.contains("Pitchfork") -> "Y"
            name.contains("Brush") -> "✎"
            name.contains("Eraser") -> "⌫"
            name.contains("BOS") -> "B"
            name.contains("MSS") -> "M"
            name.contains("CHoCH") -> "C"
            name.contains("FVG") -> "F"
            name.contains("Order Block") -> "OB"
            name.contains("Liquidity") -> "LQ"
            name.contains("Premium") -> "PD"
            name.contains("OTE") -> "OT"
            name.contains("Previous Day High") -> "PDH"
            name.contains("Previous Day Low") -> "PDL"

            else -> "•"
        }
    }

    // =========================
    // ALL TOOLS
    // =========================

    private fun showAllTools() {

        val favourites = getFavourites()

        val checked = BooleanArray(allTools.size)

        for (i in allTools.indices) {
            checked[i] = favourites.contains(allTools[i])
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Favourite Tools")

        dialog.setMultiChoiceItems(
            allTools.toTypedArray(),
            checked
        ) { _, which, isChecked ->

            val current = getFavourites()

            if (isChecked) {

                if (!current.contains(allTools[which])) {
                    current.add(allTools[which])
                }

            } else {

                current.remove(allTools[which])
            }

            saveFavourites(current)
        }

        dialog.setPositiveButton("DONE") { _, _ ->
            refreshFavouriteToolbar()
        }

        dialog.setNeutralButton("RESET") { _, _ ->

            saveFavourites(defaultFavourites)

            refreshFavouriteToolbar()
        }

        dialog.show()
    }

    private fun removeFavourite(toolName: String) {

        val current = getFavourites()

        current.remove(toolName)

        saveFavourites(current)

        refreshFavouriteToolbar()

        Toast.makeText(
            this,
            "$toolName removed",
            Toast.LENGTH_SHORT
        ).show()
    }
}
