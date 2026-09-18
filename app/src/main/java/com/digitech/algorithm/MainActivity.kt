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

        val symbol = TextView(this).apply {
            text = "EURUSD  ▾"
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
            }

            top.addView(
                t,
                LinearLayout.LayoutParams(38, 38)
            )
        }

        val spacer = Space(this)

        top.addView(
            spacer,
            LinearLayout.LayoutParams(0, 1, 1f)
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

        val info = TextView(this).apply {
            text = "EURUSD · 15m    O 1.08452   H 1.08518   L 1.08437   C 1.08496"
            textSize = 10f
            setTextColor(muted)
        }

        ohlc.addView(
            info,
            LinearLayout.LayoutParams(0, 38, 1f)
        )

        val live = TextView(this).apply {
            text = "● LIVE"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(green)
        }

        ohlc.addView(
            live,
            LinearLayout.LayoutParams(60, 38)
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
        // LEFT FAVOURITE TOOLBAR
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

        val chart = CandlestickChartView(this)

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
            text = "DA  •  REAL OHLC DATA REQUIRED"
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
            "EURUSD      1.08496",
            "GBPUSD      1.27644",
            "USDJPY      148.76",
            "XAUUSD      3414.76",
            "BTCUSD      67480",
            "ETHUSD      3248",
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

        val signal = TextView(this).apply {
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
            setBackgroundColor(Color.rgb(11, 37, 34))
        }

        right.addView(
            signal,
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
    // TOOLBAR
    // =========================

    private fun refreshToolbar() {

        favouriteBar.removeAllViews()

        val fav = favourites()

        for (toolName in fav) {

            val button = TextView(this).apply {
                text = icon(toolName)
                textSize = 17f
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

            favouriteBar.addView(
                button,
                LinearLayout.LayoutParams(
                    46,
                    40
                )
            )
        }

        val allTools = TextView(this).apply {
            text = "☰"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(white)

            setOnClickListener {
                showTools()
            }
        }

        favouriteBar.addView(
            allTools,
            LinearLayout.LayoutParams(
                46,
                46
            )
        )
    }

    private fun icon(tool: String): String {

        return when {

            tool == "Crosshair" -> "⌖"
            tool == "Trend Line" -> "↗"
            tool == "Horizontal Line" -> "—"
            tool == "Vertical Line" -> "↕"
            tool == "Ray" -> "➜"
            tool == "Arrow" -> "➤"
            tool == "Rectangle" -> "□"
            tool == "Circle" -> "○"
            tool == "Triangle" -> "△"
            tool == "Text" -> "T"
            tool == "Measure" -> "↔"
            tool == "Long Position" -> "↗"
            tool == "Short Position" -> "↘"
            tool.contains("Fib") -> "F"
            tool == "Brush" -> "✎"
            tool == "Eraser" -> "⌫"
            tool == "BOS" -> "B"
            tool == "MSS" -> "M"
            tool == "CHoCH" -> "C"
            tool == "FVG" -> "F"
            tool == "Order Block" -> "OB"
            tool == "Liquidity" -> "LQ"
            tool == "Premium / Discount" -> "PD"
            tool == "OTE" -> "OT"
            tool == "Previous Day High" -> "PDH"
            tool == "Previous Day Low" -> "PDL"

            else -> "•"
        }
    }

    // =========================
    // ALL TOOLS / FAVOURITES
    // =========================

    private fun showTools() {

        val current = favourites()

        val checked = BooleanArray(tools.size)

        for (i in tools.indices) {
            checked[i] = current.contains(tools[i])
        }

        AlertDialog.Builder(this)
            .setTitle("Favourite Tools")
            .setMultiChoiceItems(
                tools,
                checked
            ) { _, which, selected ->

                val list = favourites()

                if (selected) {
                    if (!list.contains(tools[which])) {
                        list.add(tools[which])
                    }
                } else {
                    list.remove(tools[which])
                }

                saveFavourites(list)
            }
            .setNeutralButton("RESET") { _, _ ->
                saveFavourites(defaultFav)
                refreshToolbar()
            }
            .setPositiveButton("DONE") { _, _ ->
                refreshToolbar()
            }
            .show()
    }

    private fun removeFavourite(name: String) {

        val list = favourites()

        list.remove(name)

        saveFavourites(list)

        refreshToolbar()

        Toast.makeText(
            this,
            "$name removed",
            Toast.LENGTH_SHORT
        ).show()
    }
}
