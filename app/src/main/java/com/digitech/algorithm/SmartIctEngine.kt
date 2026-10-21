package com.digitech.algorithm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, deterministic Smart ICT analysis over REAL OHLC candles.
 *
 * This engine deliberately does not create candles, execute orders, or change
 * chart state. Concepts are returned as time/price anchored events so the chart,
 * replay and tests can consume the same detections.
 */
internal object SmartIctEngine {
    enum class Kind {
        SWING_HIGH, SWING_LOW, HH, HL, LH, LL,
        BOS_BULLISH, BOS_BEARISH, CHOCH_BULLISH, CHOCH_BEARISH,
        BSL, SSL, EQH, EQL, LIQUIDITY_SWEEP_BUY, LIQUIDITY_SWEEP_SELL,
        FVG_BULLISH, FVG_BEARISH, IFVG_BULLISH, IFVG_BEARISH,
        ORDER_BLOCK_BULLISH, ORDER_BLOCK_BEARISH,
        BREAKER_BULLISH, BREAKER_BEARISH,
        PREMIUM, DISCOUNT, EQUILIBRIUM, OTE,
        DISPLACEMENT_BULLISH, DISPLACEMENT_BEARISH
    }

    data class Event(
        val kind: Kind,
        val startTime: Long,
        val endTime: Long,
        val low: Float,
        val high: Float,
        val confirmationTime: Long,
        val invalidated: Boolean = false,
        val note: String = ""
    )

    data class Config(
        val pivotLeft: Int = 2,
        val pivotRight: Int = 2,
        val equalToleranceBps: Float = 8f,
        val minimumFvgFraction: Float = 0f,
        val displacementBodyAtrMultiple: Float = 1.25f,
        val dealingRangeLookback: Int = 50
    )

    data class Result(
        val events: List<Event>,
        val warnings: List<String>
    )

    fun analyze(input: List<Candle>, config: Config = Config()): Result {
        if (input.size < 5) return Result(emptyList(), listOf("At least five real OHLC candles are required"))
        val candles = input.filter {
            it.time > 0L && it.open.isFinite() && it.high.isFinite() &&
                it.low.isFinite() && it.close.isFinite() &&
                it.high >= max(it.open, it.close) && it.low <= min(it.open, it.close) &&
                it.high >= it.low
        }
        if (candles.size < 5) return Result(emptyList(), listOf("Insufficient valid OHLC candles"))
        val out = mutableListOf<Event>()
        val warnings = mutableListOf<String>()
        val left = config.pivotLeft.coerceAtLeast(1)
        val right = config.pivotRight.coerceAtLeast(1)
        val highs = mutableListOf<Int>()
        val lows = mutableListOf<Int>()

        // Confirm pivots only after the right-hand candles exist; no look-ahead.
        for (i in left until candles.size - right) {
            val c = candles[i]
            if ((i-left until i).all { candles[it].high < c.high } &&
                (i+1..i+right).all { candles[it].high <= c.high }) {
                highs.add(i)
                out.add(Event(Kind.SWING_HIGH, c.time, c.time, c.high, c.high,
                    candles[i+right].time, note = "Confirmed pivot"))
            }
            if ((i-left until i).all { candles[it].low > c.low } &&
                (i+1..i+right).all { candles[it].low >= c.low }) {
                lows.add(i)
                out.add(Event(Kind.SWING_LOW, c.time, c.time, c.low, c.low,
                    candles[i+right].time, note = "Confirmed pivot"))
            }
        }

        classifySwings(candles, highs, true, out)
        classifySwings(candles, lows, false, out)

        // BOS / CHoCH: only examine closes after the pivot's confirmation candle.
        var lastHigh: Int? = null
        var lastLow: Int? = null
        var trend = 0
        for (i in candles.indices) {
            val newHigh = highs.lastOrNull { it + right == i }
            val newLow = lows.lastOrNull { it + right == i }
            if (newHigh != null) lastHigh = newHigh
            if (newLow != null) lastLow = newLow
            val h = lastHigh
            val l = lastLow
            if (h != null && i > h + right && candles[i].close > candles[h].high) {
                val kind = if (trend < 0) Kind.CHOCH_BULLISH else Kind.BOS_BULLISH
                if (out.none { it.kind == kind && it.confirmationTime == candles[i].time }) {
                    out.add(Event(kind, candles[h].time, candles[i].time, candles[h].high,
                        candles[i].close, candles[i].time, note = "Close-through confirmed"))
                }
                trend = 1
                lastHigh = null
            }
            if (l != null && i > l + right && candles[i].close < candles[l].low) {
                val kind = if (trend > 0) Kind.CHOCH_BEARISH else Kind.BOS_BEARISH
                if (out.none { it.kind == kind && it.confirmationTime == candles[i].time }) {
                    out.add(Event(kind, candles[l].time, candles[i].time, candles[i].close,
                        candles[l].low, candles[i].time, note = "Close-through confirmed"))
                }
                trend = -1
                lastLow = null
            }
        }

        // Three-candle FVG, using closed candles only.
        for (i in 2 until candles.size) {
            val a = candles[i-2]; val c = candles[i]
            val span = max(0.0000001f, c.high - c.low)
            if (c.low > a.high) {
                val gap = c.low - a.high
                if (gap / span >= config.minimumFvgFraction) {
                    val filled = candles.drop(i+1).any { it.low <= a.high }
                    out.add(Event(Kind.FVG_BULLISH, a.time, c.time, a.high, c.low,
                        c.time, invalidated = filled, note = if (filled) "Later filled" else "Open gap"))
                }
            }
            if (c.high < a.low) {
                val gap = a.low - c.high
                if (gap / span >= config.minimumFvgFraction) {
                    val filled = candles.drop(i+1).any { it.high >= a.low }
                    out.add(Event(Kind.FVG_BEARISH, a.time, c.time, c.high, a.low,
                        c.time, invalidated = filled, note = if (filled) "Later filled" else "Open gap"))
                }
            }
        }

        // Basic last-opposite-candle OB candidates; invalidation is explicit.
        for (i in 1 until candles.lastIndex) {
            val prev = candles[i-1]; val cur = candles[i]; val next = candles[i+1]
            if (prev.close < prev.open && cur.close > cur.open && cur.close > prev.high && next.close >= cur.close) {
                val invalid = candles.drop(i+2).any { it.close < prev.low }
                out.add(Event(Kind.ORDER_BLOCK_BULLISH, prev.time, cur.time, prev.low,
                    prev.high, next.time, invalid, "Last bearish candle before bullish displacement"))
            }
            if (prev.close > prev.open && cur.close < cur.open && cur.close < prev.low && next.close <= cur.close) {
                val invalid = candles.drop(i+2).any { it.close > prev.high }
                out.add(Event(Kind.ORDER_BLOCK_BEARISH, prev.time, cur.time, prev.low,
                    prev.high, next.time, invalid, "Last bullish candle before bearish displacement"))
            }
        }

        // Equal highs/lows and liquidity sweep candidates.
        val tolerance = config.equalToleranceBps.coerceAtLeast(0f) / 10_000f
        for (a in highs.indices) for (b in a+1 until highs.size) {
            val i = highs[a]; val j = highs[b]
            if (j - i > config.dealingRangeLookback) continue
            val p1 = candles[i].high; val p2 = candles[j].high
            if (abs(p1-p2) <= max(abs(p1), abs(p2)) * tolerance) {
                out.add(Event(Kind.EQH, candles[i].time, candles[j].time, min(p1,p2),
                    max(p1,p2), candles[j+right].time, note = "Equal highs within configured tolerance"))
                out.add(Event(Kind.BSL, candles[i].time, candles[j].time, min(p1,p2),
                    max(p1,p2), candles[j+right].time, note = "Buy-side liquidity candidate"))
            }
        }
        for (a in lows.indices) for (b in a+1 until lows.size) {
            val i = lows[a]; val j = lows[b]
            if (j - i > config.dealingRangeLookback) continue
            val p1 = candles[i].low; val p2 = candles[j].low
            if (abs(p1-p2) <= max(abs(p1), abs(p2)) * tolerance) {
                out.add(Event(Kind.EQL, candles[i].time, candles[j].time, min(p1,p2),
                    max(p1,p2), candles[j+right].time, note = "Equal lows within configured tolerance"))
                out.add(Event(Kind.SSL, candles[i].time, candles[j].time, min(p1,p2),
                    max(p1,p2), candles[j+right].time, note = "Sell-side liquidity candidate"))
            }
        }
        for (i in 1 until candles.size) {
            val priorHigh = highs.lastOrNull { it + right < i }
            val priorLow = lows.lastOrNull { it + right < i }
            if (priorHigh != null && candles[i].high > candles[priorHigh].high && candles[i].close < candles[priorHigh].high)
                out.add(Event(Kind.LIQUIDITY_SWEEP_BUY, candles[priorHigh].time, candles[i].time,
                    candles[priorHigh].high, candles[i].high, candles[i].time, note = "Wick above confirmed high, close back below"))
            if (priorLow != null && candles[i].low < candles[priorLow].low && candles[i].close > candles[priorLow].low)
                out.add(Event(Kind.LIQUIDITY_SWEEP_SELL, candles[priorLow].time, candles[i].time,
                    candles[i].low, candles[priorLow].low, candles[i].time, note = "Wick below confirmed low, close back above"))
        }

        // Dealing range and 50% equilibrium are derived from the configured recent window.
        val window = candles.takeLast(config.dealingRangeLookback.coerceAtLeast(5))
        val rangeHigh = window.maxOf { it.high }
        val rangeLow = window.minOf { it.low }
        val eq = (rangeHigh + rangeLow) / 2f
        out.add(Event(Kind.EQUILIBRIUM, window.first().time, window.last().time, eq, eq,
            window.last().time, note = "50% of recent dealing range"))
        out.add(Event(Kind.PREMIUM, window.first().time, window.last().time, eq, rangeHigh,
            window.last().time, note = "Above equilibrium"))
        out.add(Event(Kind.DISCOUNT, window.first().time, window.last().time, rangeLow, eq,
            window.last().time, note = "Below equilibrium"))
        val oteLow = rangeLow + (rangeHigh-rangeLow)*0.62f
        val oteHigh = rangeLow + (rangeHigh-rangeLow)*0.79f
        out.add(Event(Kind.OTE, window.first().time, window.last().time, oteLow, oteHigh,
            window.last().time, note = "62%-79% range retracement; directional context required"))

        // Concepts needing external/contextual inputs are intentionally not fabricated.
        warnings.add("IFVG, breaker/mitigation blocks, session kill zones, Silver Bullet, Judas Swing, Turtle Soup, Unicorn, 2022 model, SMT, news filters and live execution need dedicated contextual rules/data; this engine does not mark them as verified.")
        warnings.add("This engine is a deterministic first integration layer, not a profitability guarantee or permission to place real-money orders.")
        return Result(out.distinctBy { listOf(it.kind, it.startTime, it.endTime, it.low, it.high) }
            .sortedWith(compareBy<Event> { it.confirmationTime }.thenBy { it.kind.name }), warnings)
    }

    private fun classifySwings(candles: List<Candle>, indices: List<Int>, high: Boolean, out: MutableList<Event>) {
        var previous: Float? = null
        for (i in indices) {
            val price = if (high) candles[i].high else candles[i].low
            val prior = previous
            if (prior != null) {
                val kind = when {
                    high && price > prior -> Kind.HH
                    high -> Kind.LH
                    !high && price > prior -> Kind.HL
                    else -> Kind.LL
                }
                out.add(Event(kind, candles[i].time, candles[i].time, price, price,
                    candles[i].time, note = "Swing classification"))
            }
            previous = price
        }
    }
}
