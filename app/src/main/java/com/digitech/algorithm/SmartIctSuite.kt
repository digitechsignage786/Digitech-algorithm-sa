package com.digitech.algorithm

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-scope input-driven ICT analysis. All events are anchored to supplied real OHLC.
 * Intermarket, news, MTF and execution-dependent concepts require their named real inputs.
 */
internal object SmartIctSuite {
    enum class Tag {
        SWING_HIGH, SWING_LOW, HH, HL, LH, LL, SHORT_TERM_HIGH, SHORT_TERM_LOW,
        INTERMEDIATE_TERM_HIGH, INTERMEDIATE_TERM_LOW, LONG_TERM_HIGH, LONG_TERM_LOW,
        RELATIVE_EQUAL_HIGHS, RELATIVE_EQUAL_LOWS, INTERNAL_STRUCTURE, EXTERNAL_STRUCTURE,
        BOS_BULLISH, BOS_BEARISH, CHOCH_BULLISH, CHOCH_BEARISH, MSS_BULLISH, MSS_BEARISH,
        PROTECTED_HIGH, PROTECTED_LOW, BSL, SSL, EQH, EQL, LIQUIDITY_SWEEP_BUY, LIQUIDITY_SWEEP_SELL,
        IRL, ERL, DRAW_ON_LIQUIDITY_HIGH, DRAW_ON_LIQUIDITY_LOW, INDUCEMENT_BULLISH, INDUCEMENT_BEARISH, CISD_BULLISH, CISD_BEARISH, CRT_HIGH, CRT_LOW, CRT_MIDPOINT, IPDA_RANGE_20, IPDA_RANGE_40, IPDA_RANGE_60,
        FVG_BULLISH, FVG_BEARISH, FVG_PARTIAL_FILL, FVG_FULL_FILL, FVG_INVALIDATED,
        IFVG_BULLISH, IFVG_BEARISH, BPR, VOLUME_IMBALANCE,
        OPENING_GAP, NDOG, NWOG, OPENING_RANGE_HIGH, OPENING_RANGE_LOW, OPENING_RANGE_MID, LIQUIDITY_VOID_BULLISH, LIQUIDITY_VOID_BEARISH, DISPLACEMENT_BULLISH, DISPLACEMENT_BEARISH, CE_50,
        ORDER_BLOCK_BULLISH, ORDER_BLOCK_BEARISH, BREAKER_BULLISH, BREAKER_BEARISH,
        MITIGATION_BLOCK, REJECTION_BLOCK, PROPULSION_BLOCK, VACUUM_BLOCK,
        PREMIUM, DISCOUNT, EQUILIBRIUM, OTE, OTE_BULLISH, OTE_BEARISH, FIB_62, FIB_705, FIB_79,
        RANGE_EXPANSION, PRICE_DELIVERY_RANGE,
        ASIA_RANGE, LONDON_KILL_ZONE, NY_AM_KILL_ZONE, NY_PM, LONDON_CLOSE, SESSION_OPEN, MACRO_WINDOW,
        DAILY_BIAS_BULLISH, DAILY_BIAS_BEARISH, WEEKLY_BIAS_BULLISH, WEEKLY_BIAS_BEARISH,
        MONTHLY_BIAS_BULLISH, MONTHLY_BIAS_BEARISH, MIDNIGHT_OPEN,
        PDH, PDL, PWH, PWL, DAILY_OPEN, WEEKLY_OPEN, SESSION_HIGH, SESSION_LOW,
        SILVER_BULLET, JUDAS_SWING, TURTLE_SOUP, UNICORN, ICT_2022_MODEL, POWER_OF_THREE,
        MMXM, AMD_ACCUMULATION, AMD_MANIPULATION, AMD_DISTRIBUTION,
        FVG_OB_ENTRY, BREAKER_FVG_ENTRY, OTE_ENTRY,
        SMT_BULLISH, SMT_BEARISH, HTF_BIAS_BULLISH, HTF_BIAS_BEARISH,
        MTF_CONFLUENCE, MTF_CONFLICT, BUY_SETUP, SELL_SETUP, ENTRY, STOP_LOSS,
        TAKE_PROFIT_1, TAKE_PROFIT_2, TAKE_PROFIT_3, NO_TRADE, NEWS_WARNING,
        SPREAD_WARNING, STALE_DATA_WARNING
    }
    enum class FeedState { LIVE, STALE, MISSING, DISCONNECTED }
    enum class Impact { LOW, MEDIUM, HIGH }

    data class Mark(val tag: Tag, val startTime: Long, val endTime: Long, val low: Float,
                    val high: Float, val confirmedAt: Long, val invalidated: Boolean = false,
                    val note: String = "")
    data class NewsEvent(val timeMillis: Long, val currency: String, val impact: Impact, val title: String = "")
    data class Signal(val bullish: Boolean, val timeMillis: Long, val entry: Float, val stopLoss: Float,
                      val takeProfit1: Float, val takeProfit2: Float, val takeProfit3: Float,
                      val riskReward2: Float, val riskPercent: Float, val confluence: Int,
                      val tradeFiltersPassed: Boolean, val reasons: List<String>)
    data class Config(
        val zoneId: String = "America/New_York", val pivotLeft: Int = 2, val pivotRight: Int = 2,
        val equalToleranceBps: Float = 8f, val dealingRangeLookback: Int = 50, val atrPeriod: Int = 14,
        val displacementAtrMultiple: Float = 1.25f, val maxSpreadBps: Float = 3f,
        val actualSpreadBps: Float? = null, val newsBufferMinutes: Int = 15,
        val newsCalendarComplete: Boolean = false,
        val maxDataAgeMillis: Long = 120_000L, val nowMillis: Long? = null,
        val latestFeedTimeMillis: Long? = null, val maxAtrPercent: Float = 2f,
        val maxStopLossPercent: Float = 5f,
        val feedState: FeedState = FeedState.LIVE, val dailyLossPercent: Float = 0f,
        val dailyLossLimitPercent: Float = 3f, val riskPercent: Float = 1f,
        val minimumRiskReward: Float = 2f, val requireNyOrLondonSession: Boolean = false
    )
    data class Input(val candles: List<Candle>, val symbol: String = "",
                     val relatedMarkets: Map<String, List<Candle>> = emptyMap(),
                     val timeframes: Map<String, List<Candle>> = emptyMap(),
                     val news: List<NewsEvent> = emptyList(), val config: Config = Config())
    data class Capability(val name: String, val ready: Boolean, val detail: String)
    data class BacktestStats(val trades: Int, val wins: Int, val losses: Int, val winRatePercent: Float,
        val netR: Float, val profitFactor: Float, val maxDrawdownR: Float, val expectancyR: Float,
        val coverageNote: String)
    data class Result(val marks: List<Mark>, val signals: List<Signal>,
                      val capabilities: List<Capability>, val warnings: List<String>)
    private data class Gap(val bullish: Boolean, val start: Int, val end: Int, val low: Float, val high: Float)

    fun analyze(input: Input): Result {
        val cfg = input.config
        val candles = input.candles.asSequence().filter {
            it.time > 0L && it.open.isFinite() && it.high.isFinite() && it.low.isFinite() &&
                it.close.isFinite() && it.high >= max(it.open, it.close) &&
                it.low <= min(it.open, it.close) && it.high >= it.low
        }.distinctBy { it.time }.sortedBy { it.time }.toList()
        if (candles.size < 5) return Result(emptyList(), emptyList(), capabilities(input, false),
            listOf("At least five valid timestamped real OHLC candles are required; no candles were generated."))

        val zone = runCatching { ZoneId.of(cfg.zoneId) }.getOrDefault(ZoneId.of("America/New_York"))
        val marks = mutableListOf<Mark>()
        val signals = mutableListOf<Signal>()
        val left = cfg.pivotLeft.coerceAtLeast(1); val right = cfg.pivotRight.coerceAtLeast(1)
        val highs = mutableListOf<Int>(); val lows = mutableListOf<Int>()

        // Confirmed pivots: the right-side candles exist before a pivot is emitted.
        for (i in left until candles.size-right) {
            val c = candles[i]
            if ((i-left until i).all { candles[it].high < c.high } &&
                (i+1..i+right).all { candles[it].high <= c.high }) {
                highs += i; marks += m(Tag.SWING_HIGH,c,c,c.high,c.high,candles[i+right].time,"Confirmed pivot")
            }
            if ((i-left until i).all { candles[it].low > c.low } &&
                (i+1..i+right).all { candles[it].low >= c.low }) {
                lows += i; marks += m(Tag.SWING_LOW,c,c,c.low,c.low,candles[i+right].time,"Confirmed pivot")
            }
        }
        classify(candles, highs, true, marks); classify(candles, lows, false, marks)
        addPivotHierarchy(candles, highs, lows, right, marks)
        highs.takeLast(2).forEach { marks += m(Tag.BSL,candles[it],candles.last(),candles[it].high,candles[it].high,candles[it].time,"Swing high liquidity candidate") }
        lows.takeLast(2).forEach { marks += m(Tag.SSL,candles[it],candles.last(),candles[it].low,candles[it].low,candles[it].time,"Swing low liquidity candidate") }

        var lastHigh: Int? = null; var lastLow: Int? = null; var trend = 0
        for (i in candles.indices) {
            highs.lastOrNull { it+right == i }?.let { lastHigh = it }
            lows.lastOrNull { it+right == i }?.let { lastLow = it }
            val h = lastHigh; val l = lastLow
            if (h != null && i > h+right && candles[i].close > candles[h].high) {
                marks += m(if (trend < 0) Tag.CHOCH_BULLISH else Tag.BOS_BULLISH,candles[h],candles[i],
                    candles[h].high,candles[i].close,candles[i].time,"Close-through after pivot confirmation")
                marks += m(Tag.PROTECTED_LOW,candles[l ?: h],candles[i],candles[l ?: h].low,
                    candles[l ?: h].low,candles[i].time,"Protected low after bullish break")
                if (trend < 0) marks += m(Tag.MSS_BULLISH,candles[h],candles[i],candles[h].high,candles[i].close,candles[i].time,"Market structure shift")
                trend = 1; lastHigh = null
            }
            if (l != null && i > l+right && candles[i].close < candles[l].low) {
                marks += m(if (trend > 0) Tag.CHOCH_BEARISH else Tag.BOS_BEARISH,candles[l],candles[i],
                    candles[i].close,candles[l].low,candles[i].time,"Close-through after pivot confirmation")
                marks += m(Tag.PROTECTED_HIGH,candles[h ?: l],candles[i],candles[h ?: l].high,
                    candles[h ?: l].high,candles[i].time,"Protected high after bearish break")
                if (trend > 0) marks += m(Tag.MSS_BEARISH,candles[l],candles[i],candles[i].close,candles[l].low,candles[i].time,"Market structure shift")
                trend = -1; lastLow = null
            }
        }

        val atr = trueRanges(candles,cfg.atrPeriod.coerceAtLeast(2))
        // Detect observed opening gaps only across normal intraday intervals.
        // Overnight/weekend closures remain missing time, never synthetic candles.
        for (i in 1 until candles.size) {
            val prev = candles[i-1]
            val cur = candles[i]
            val elapsed = cur.time - prev.time
            if (elapsed in 1..108_000_000L && abs(cur.open - prev.close) >= atr[i].coerceAtLeast(0.0000001f) * 0.5f) {
                marks += m(Tag.OPENING_GAP, prev, cur, min(prev.close, cur.open), max(prev.close, cur.open),
                    cur.time, "Observed price gap in supplied candles; no candle was generated")
            }
        }
        val gaps = mutableListOf<Gap>()
        for (i in 2 until candles.size) {
            val a=candles[i-2]; val b=candles[i-1]; val c=candles[i]
            val body=abs(b.close-b.open); val tr=atr[i].coerceAtLeast(0.0000001f)
            if (body >= tr*cfg.displacementAtrMultiple.coerceAtLeast(0.1f)) {
                val bullish=b.close>b.open
                val nearExtreme=if (bullish) b.high-b.close <= (b.high-b.low)*0.25f else b.close-b.low <= (b.high-b.low)*0.25f
                if (nearExtreme) marks += m(if (bullish) Tag.DISPLACEMENT_BULLISH else Tag.DISPLACEMENT_BEARISH,b,b,b.low,b.high,b.time,"Body versus ATR displacement rule")
            }
            if (c.low>a.high) {
                val g=Gap(true,i-2,i,a.high,c.low); gaps+=g
                val future=candles.drop(i+1)
                val partial=future.firstOrNull { it.low<g.high }
                val full=future.firstOrNull { it.low<=g.low }
                val invalid=future.firstOrNull { it.close<g.low }
                val filled=full!=null
                marks+=m(Tag.FVG_BULLISH,a,c,g.low,g.high,c.time,if(filled)"Fully filled later" else "Bullish three-candle imbalance",filled)
                if(partial!=null && (full==null || partial.time<full.time)) marks+=m(Tag.FVG_PARTIAL_FILL,a,partial,g.low,g.high,partial.time,"Bullish FVG entered but not yet fully filled")
                if(full!=null) marks+=m(Tag.FVG_FULL_FILL,a,full,g.low,g.high,full.time,"Bullish FVG fully traversed")
                if(invalid!=null) marks+=m(Tag.FVG_INVALIDATED,a,invalid,g.low,g.high,invalid.time,"Bullish FVG invalidated by close below lower boundary")
                marks+=m(Tag.CE_50,a,c,(g.low+g.high)/2f,(g.low+g.high)/2f,c.time,"FVG 50% consequent encroachment")
                if (b.high>b.low && abs(b.close-b.open)/(b.high-b.low)>0.75f)
                    marks+=m(Tag.VOLUME_IMBALANCE,b,b,b.low,b.high,b.time,"Body-dominant proxy; volume data unavailable")
            }
            if (c.high<a.low) {
                val g=Gap(false,i-2,i,c.high,a.low); gaps+=g
                val future=candles.drop(i+1)
                val partial=future.firstOrNull { it.high>g.low }
                val full=future.firstOrNull { it.high>=g.high }
                val invalid=future.firstOrNull { it.close>g.high }
                val filled=full!=null
                marks+=m(Tag.FVG_BEARISH,a,c,g.low,g.high,c.time,if(filled)"Fully filled later" else "Bearish three-candle imbalance",filled)
                if(partial!=null && (full==null || partial.time<full.time)) marks+=m(Tag.FVG_PARTIAL_FILL,a,partial,g.low,g.high,partial.time,"Bearish FVG entered but not yet fully filled")
                if(full!=null) marks+=m(Tag.FVG_FULL_FILL,a,full,g.low,g.high,full.time,"Bearish FVG fully traversed")
                if(invalid!=null) marks+=m(Tag.FVG_INVALIDATED,a,invalid,g.low,g.high,invalid.time,"Bearish FVG invalidated by close above upper boundary")
                marks+=m(Tag.CE_50,a,c,(g.low+g.high)/2f,(g.low+g.high)/2f,c.time,"FVG 50% consequent encroachment")
            }
        }
        for (g in gaps) for (j in g.end+1 until candles.size) {
            if (g.bullish && candles[j].close<g.low) {
                marks+=m(Tag.IFVG_BEARISH,candles[g.start],candles[j],g.low,g.high,candles[j].time,"Bullish FVG inverted by close through lower edge"); break
            }
            if (!g.bullish && candles[j].close>g.high) {
                marks+=m(Tag.IFVG_BULLISH,candles[g.start],candles[j],g.low,g.high,candles[j].time,"Bearish FVG inverted by close through upper edge"); break
            }
        }
        for (i in gaps.indices) for (j in i+1 until gaps.size) {
            val a=gaps[i]; val b=gaps[j]; if (a.bullish==b.bullish) continue
            val lo=max(a.low,b.low); val hi=min(a.high,b.high)
            if (lo<hi) marks+=m(Tag.BPR,candles[min(a.start,b.start)],candles[max(a.end,b.end)],lo,hi,candles[max(a.end,b.end)].time,"Opposing FVG overlap")
        }

        // Observed daily/weekly opening gaps and opening-range levels. A gap is
        // emitted only when both real candles exist; no weekend candle is synthesized.
        val datedCandles = candles.map { it to Instant.ofEpochMilli(it.time).atZone(zone) }
        for (i in 1 until datedCandles.size) {
            val (prior, priorZoned) = datedCandles[i - 1]
            val (current, currentZoned) = datedCandles[i]
            val dayChanged = priorZoned.toLocalDate() != currentZoned.toLocalDate()
            val weekChanged = priorZoned.get(WeekFields.ISO.weekBasedYear()) != currentZoned.get(WeekFields.ISO.weekBasedYear()) ||
                priorZoned.get(WeekFields.ISO.weekOfWeekBasedYear()) != currentZoned.get(WeekFields.ISO.weekOfWeekBasedYear())
            if (dayChanged && current.open != prior.close) {
                marks += m(Tag.NDOG, prior, current, min(prior.close, current.open), max(prior.close, current.open),
                    current.time, "Observed daily opening gap; absent if provider has no gap")
            }
            if (weekChanged && current.open != prior.close) {
                marks += m(Tag.NWOG, prior, current, min(prior.close, current.open), max(prior.close, current.open),
                    current.time, "Observed weekly opening gap; absent if provider has no gap")
            }
        }
        // Opening range is the first supplied 30 minutes of the latest local date.
        val todayBars = datedCandles.filter { it.second.toLocalDate() == datedCandles.last().second.toLocalDate() }
            .map { it.first }
        if (todayBars.isNotEmpty()) {
            val start = todayBars.first().time
            val openingBars = todayBars.filter { it.time - start <= 30L * 60_000L }
            if (openingBars.isNotEmpty()) {
                val orHigh = openingBars.maxOf { it.high }
                val orLow = openingBars.minOf { it.low }
                marks += m(Tag.OPENING_RANGE_HIGH, openingBars.first(), openingBars.last(), orHigh, orHigh,
                    openingBars.last().time, "First 30 minutes of supplied local-day candles; provider coverage dependent")
                marks += m(Tag.OPENING_RANGE_LOW, openingBars.first(), openingBars.last(), orLow, orLow,
                    openingBars.last().time, "First 30 minutes of supplied local-day candles; provider coverage dependent")
                marks += m(Tag.OPENING_RANGE_MID, openingBars.first(), openingBars.last(), (orHigh + orLow) / 2f,
                    (orHigh + orLow) / 2f, openingBars.last().time, "Opening range midpoint")
            }
        }
        // Liquidity void proxy: an unusually large directional candle body relative
        // to ATR. Volume/order-flow data is not inferred from price alone.
        for (i in 1 until candles.size) {
            val bar = candles[i]
            val range = (bar.high - bar.low).coerceAtLeast(0.0000001f)
            val body = abs(bar.close - bar.open)
            if (body >= atr[i].coerceAtLeast(0.0000001f) * cfg.displacementAtrMultiple.coerceAtLeast(0.1f) &&
                body / range >= 0.8f) {
                marks += m(if (bar.close >= bar.open) Tag.LIQUIDITY_VOID_BULLISH else Tag.LIQUIDITY_VOID_BEARISH,
                    bar, bar, bar.low, bar.high, bar.time,
                    "Large body/range liquidity-void proxy; not true order-book liquidity")
            }
        }

        // Zone heuristics. Invalidation is explicit; none of these labels are guaranteed canonical signals.
        for (i in 1 until candles.lastIndex) {
            val p=candles[i-1]; val d=candles[i]; val conf=candles[i+1]
            val displaced=abs(d.close-d.open)>=atr[i].coerceAtLeast(0.0000001f)*cfg.displacementAtrMultiple.coerceAtLeast(0.1f)
            if (p.close<p.open && d.close>d.open && d.close>p.high && displaced) {
                val bad=candles.drop(i+2).any { it.close<p.low }
                marks+=m(Tag.ORDER_BLOCK_BULLISH,p,d,p.low,p.high,conf.time,"Last bearish candle before bullish displacement",bad)
                if (candles.drop(i+2).any { it.low<=p.high && it.high>=p.low }) marks+=m(Tag.MITIGATION_BLOCK,p,candles.last(),p.low,p.high,candles.last().time,"Bullish OB retested")
                if (bad) marks+=m(Tag.BREAKER_BEARISH,p,candles.last(),p.low,p.high,candles.last().time,"Bullish OB invalidated; bearish breaker candidate")
                marks+=m(Tag.REJECTION_BLOCK,p,d,p.low,p.high,d.time,"Bullish displacement rejected prior candle range")
                if (i>=2 && candles[i-2].close<candles[i-2].open && d.close>candles[i-2].high)
                    marks+=m(Tag.PROPULSION_BLOCK,candles[i-2],d,candles[i-2].low,candles[i-2].high,d.time,"Continuation displacement candidate")
            }
            if (p.close>p.open && d.close<d.open && d.close<p.low && displaced) {
                val bad=candles.drop(i+2).any { it.close>p.high }
                marks+=m(Tag.ORDER_BLOCK_BEARISH,p,d,p.low,p.high,conf.time,"Last bullish candle before bearish displacement",bad)
                if (candles.drop(i+2).any { it.high>=p.low && it.low<=p.high }) marks+=m(Tag.MITIGATION_BLOCK,p,candles.last(),p.low,p.high,candles.last().time,"Bearish OB retested")
                if (bad) marks+=m(Tag.BREAKER_BULLISH,p,candles.last(),p.low,p.high,candles.last().time,"Bearish OB invalidated; bullish breaker candidate")
                marks+=m(Tag.REJECTION_BLOCK,p,d,p.low,p.high,d.time,"Bearish displacement rejected prior candle range")
                if (i>=2 && candles[i-2].close>candles[i-2].open && d.close<candles[i-2].low)
                    marks+=m(Tag.PROPULSION_BLOCK,candles[i-2],d,candles[i-2].low,candles[i-2].high,d.time,"Continuation displacement candidate")
            }
            if (p.high>p.low && d.high-d.low >= (p.high-p.low)*2f && (d.low>p.high || d.high<p.low))
                marks+=m(Tag.VACUUM_BLOCK,p,d,min(p.high,d.low),max(p.low,d.high),d.time,"Large-range interval candidate")
        }

        val tolerance=cfg.equalToleranceBps.coerceAtLeast(0f)/10_000f
        for ((idxs,isHigh) in listOf(highs to true,lows to false)) {
            for (a in idxs.indices) for (b in a+1 until idxs.size) {
                val i=idxs[a]; val j=idxs[b]; if (j-i>cfg.dealingRangeLookback.coerceAtLeast(5)) continue
                val p1=if(isHigh)candles[i].high else candles[i].low
                val p2=if(isHigh)candles[j].high else candles[j].low
                if (abs(p1-p2)<=max(abs(p1),abs(p2))*tolerance) {
                    marks+=m(if(isHigh)Tag.EQH else Tag.EQL,candles[i],candles[j],min(p1,p2),max(p1,p2),candles[j+right].time,"Equal levels within configured tolerance")
                    marks+=m(if(isHigh)Tag.RELATIVE_EQUAL_HIGHS else Tag.RELATIVE_EQUAL_LOWS,candles[i],candles[j],min(p1,p2),max(p1,p2),candles[j+right].time,"Relative equal liquidity levels")
                }
            }
        }
        for (i in 1 until candles.size) {
            val ph=highs.lastOrNull { it+right<i }; val pl=lows.lastOrNull { it+right<i }
            if (ph!=null && candles[i].high>candles[ph].high && candles[i].close<candles[ph].high) {
                marks+=m(Tag.LIQUIDITY_SWEEP_BUY,candles[ph],candles[i],candles[ph].high,candles[i].high,candles[i].time,"Buy-side sweep: wick through, close back below")
                marks+=m(Tag.TURTLE_SOUP,candles[ph],candles[i],candles[ph].high,candles[i].high,candles[i].time,"Failed breakout / Turtle Soup candidate")
            }
            if (pl!=null && candles[i].low<candles[pl].low && candles[i].close>candles[pl].low) {
                marks+=m(Tag.LIQUIDITY_SWEEP_SELL,candles[pl],candles[i],candles[i].low,candles[pl].low,candles[i].time,"Sell-side sweep: wick through, close back above")
                marks+=m(Tag.TURTLE_SOUP,candles[pl],candles[i],candles[i].low,candles[pl].low,candles[i].time,"Failed breakdown / Turtle Soup candidate")
            }
        }

        // Additional ICT concepts: inducement, CISD, Candle Range Theory and IPDA ranges.
        // All levels are derived only from supplied real candles; these are heuristic candidates.
        val lastIndex = candles.lastIndex
        val latestBar = candles[lastIndex]
        val recentStart = max(0, lastIndex - 20)
        val recentBars = candles.subList(recentStart, candles.size)
        val crtSource = if (lastIndex > 0) candles[lastIndex - 1] else latestBar
        val crtMid = (crtSource.high + crtSource.low) / 2f
        marks += m(Tag.CRT_HIGH, crtSource, latestBar, crtSource.high, crtSource.high,
            latestBar.time, "CRT reference high from prior supplied candle")
        marks += m(Tag.CRT_LOW, crtSource, latestBar, crtSource.low, crtSource.low,
            latestBar.time, "CRT reference low from prior supplied candle")
        marks += m(Tag.CRT_MIDPOINT, crtSource, latestBar, crtMid, crtMid,
            latestBar.time, "CRT 50% reference; range confirmation is required")
        if (latestBar.high > crtSource.high && latestBar.close < crtSource.high)
            marks += m(Tag.TURTLE_SOUP, crtSource, latestBar, crtSource.high, latestBar.high,
                latestBar.time, "CRT upper-range raid and reclaim candidate")
        if (latestBar.low < crtSource.low && latestBar.close > crtSource.low)
            marks += m(Tag.TURTLE_SOUP, crtSource, latestBar, latestBar.low, crtSource.low,
                latestBar.time, "CRT lower-range raid and reclaim candidate")

        fun addIpdaRange(windowSize: Int, tag: Tag) {
            if (candles.size >= windowSize) {
                val w = candles.takeLast(windowSize)
                marks += m(tag, w.first(), w.last(), w.minOf { it.low }, w.maxOf { it.high },
                    w.last().time, "IPDA-style rolling range proxy over $windowSize supplied candles; not an official institutional dealing array")
            }
        }
        addIpdaRange(20, Tag.IPDA_RANGE_20)
        addIpdaRange(40, Tag.IPDA_RANGE_40)
        addIpdaRange(60, Tag.IPDA_RANGE_60)

        // Inducement candidate: a recent confirmed swing that is swept and reclaimed
        // before a close-through of the opposite confirmed structure level.
        for (i in max(1, lastIndex - 12)..lastIndex) {
            val priorHigh = highs.lastOrNull { it + right < i }
            val priorLow = lows.lastOrNull { it + right < i }
            if (priorLow != null && candles[i].low < candles[priorLow].low &&
                candles[i].close > candles[priorLow].low) {
                marks += m(Tag.INDUCEMENT_BULLISH, candles[priorLow], candles[i],
                    candles[i].low, candles[priorLow].low, candles[i].time,
                    "Sell-side inducement/sweep candidate; confirm with displacement and structure")
            }
            if (priorHigh != null && candles[i].high > candles[priorHigh].high &&
                candles[i].close < candles[priorHigh].high) {
                marks += m(Tag.INDUCEMENT_BEARISH, candles[priorHigh], candles[i],
                    candles[priorHigh].high, candles[i].high, candles[i].time,
                    "Buy-side inducement/sweep candidate; confirm with displacement and structure")
            }
        }

        // CISD proxy: after a recent liquidity sweep, close crosses the most recent
        // opposing candle's open. This is explicitly labelled a proxy, not canonical ICT judgement.
        val recentSweep = marks.lastOrNull { it.tag == Tag.LIQUIDITY_SWEEP_SELL || it.tag == Tag.LIQUIDITY_SWEEP_BUY }
        if (recentSweep != null && lastIndex >= 2) {
            val sweepIndex = candles.indexOfLast { it.time <= recentSweep.endTime }
            if (sweepIndex >= 0 && lastIndex - sweepIndex <= 8) {
                val afterSweep = candles.subList(sweepIndex + 1, candles.size)
                val bearishCandle = afterSweep.indexOfLast { it.close < it.open }
                val bullishCandle = afterSweep.indexOfLast { it.close > it.open }
                if (recentSweep.tag == Tag.LIQUIDITY_SWEEP_SELL && bearishCandle >= 0) {
                    val refBar = afterSweep[bearishCandle]
                    if (latestBar.close > refBar.open)
                        marks += m(Tag.CISD_BULLISH, refBar, latestBar, refBar.open, latestBar.close,
                            latestBar.time, "CISD-style bullish close through last bearish candle open; heuristic")
                }
                if (recentSweep.tag == Tag.LIQUIDITY_SWEEP_BUY && bullishCandle >= 0) {
                    val refBar = afterSweep[bullishCandle]
                    if (latestBar.close < refBar.open)
                        marks += m(Tag.CISD_BEARISH, refBar, latestBar, latestBar.close, refBar.open,
                            latestBar.time, "CISD-style bearish close through last bullish candle open; heuristic")
                }
            }
        }

        // Broader ICT checklist coverage: internal/external structure, AMD phases,
        // volume-imbalance proxy and the Unicorn (breaker + FVG overlap) model.
        val recentPivotHigh = highs.lastOrNull()?.let { candles[it] }
        val recentPivotLow = lows.lastOrNull()?.let { candles[it] }
        if (recentPivotHigh != null) {
            marks += m(Tag.EXTERNAL_STRUCTURE, recentPivotHigh, last, recentPivotHigh.high,
                recentPivotHigh.high, last.time, "Latest confirmed swing high as external-structure reference")
        }
        if (recentPivotLow != null) {
            marks += m(Tag.EXTERNAL_STRUCTURE, recentPivotLow, last, recentPivotLow.low,
                recentPivotLow.low, last.time, "Latest confirmed swing low as external-structure reference")
        }
        val internalStart = max(0, candles.lastIndex - 8)
        val internalBars = candles.subList(internalStart, candles.size)
        marks += m(Tag.INTERNAL_STRUCTURE, internalBars.first(), last,
            internalBars.minOf { it.low }, internalBars.maxOf { it.high }, last.time,
            "Recent 9-candle internal dealing range proxy")
        // AMD/Power-of-Three are shown as candidates only when a measurable range,
        // raid and directional close exist; no fixed narrative is assumed.
        if (recentBars.size >= 8) {
            val base = recentBars.take(5)
            val baseHigh = base.maxOf { it.high }
            val baseLow = base.minOf { it.low }
            val raid = recentBars.drop(5).firstOrNull {
                (it.high > baseHigh && it.close < baseHigh) ||
                    (it.low < baseLow && it.close > baseLow)
            }
            if (baseHigh > baseLow) {
                marks += m(Tag.AMD_ACCUMULATION, base.first(), base.last(), baseLow, baseHigh,
                    base.last().time, "Compression/range accumulation candidate")
                if (raid != null) {
                    marks += m(Tag.AMD_MANIPULATION, base.last(), raid, min(raid.low, baseLow),
                        max(raid.high, baseHigh), raid.time, "Range raid/reclaim manipulation candidate")
                    val directionAfterRaid = recentBars.dropWhile { it.time <= raid.time }
                    val follow = directionAfterRaid.lastOrNull()
                    if (follow != null && (follow.close > baseHigh || follow.close < baseLow))
                        marks += m(Tag.AMD_DISTRIBUTION, raid, follow, min(baseLow, follow.low),
                            max(baseHigh, follow.high), follow.time, "Close outside accumulation range after raid")
                }
            }
        }
        // Unicorn model candidate: a breaker range overlaps an FVG in the same
        // recent region. This is a confluence marker, not a guaranteed entry.
        val recentFvgs = marks.filter { it.tag == Tag.FVG_BULLISH || it.tag == Tag.FVG_BEARISH }
            .filter { it.endTime >= recentCutoff }
        val recentBreakers = marks.filter { it.tag == Tag.BREAKER_BULLISH || it.tag == Tag.BREAKER_BEARISH }
            .filter { it.endTime >= recentCutoff }
        for (fvg in recentFvgs) for (breaker in recentBreakers) {
            val lo = max(fvg.low, breaker.low)
            val hi = min(fvg.high, breaker.high)
            if (lo < hi && abs(fvg.endTime - breaker.endTime) <= cfg.dealingRangeLookback.coerceAtLeast(5) * 60_000L) {
                marks += m(Tag.UNICORN, candles.firstOrNull { it.time >= min(fvg.startTime, breaker.startTime) } ?: last,
                    last, lo, hi, last.time, "Recent breaker/FVG overlap candidate; timeframe and displacement confirmation required")
            }
        }

        addRangeMarks(candles,cfg,marks)
        addCalendarSessionMarks(candles,zone,marks)
        addSmtMarks(candles,input.relatedMarkets,marks)
        addMtfMarks(input.timeframes,marks)

        val last=candles.last(); val recentFrom=candles[max(0,candles.lastIndex-3)].time
        val bullSweep=marks.any { it.tag==Tag.LIQUIDITY_SWEEP_SELL && it.endTime>=recentFrom }
        val bearSweep=marks.any { it.tag==Tag.LIQUIDITY_SWEEP_BUY && it.endTime>=recentFrom }
        val bullFvg=marks.any { it.tag==Tag.FVG_BULLISH && !it.invalidated && it.endTime>=recentFrom }
        val bearFvg=marks.any { it.tag==Tag.FVG_BEARISH && !it.invalidated && it.endTime>=recentFrom }
        val bullMss=marks.any { it.tag in setOf(Tag.MSS_BULLISH,Tag.CHOCH_BULLISH) && it.endTime>=candles[max(0,candles.lastIndex-5)].time }
        val bearMss=marks.any { it.tag in setOf(Tag.MSS_BEARISH,Tag.CHOCH_BEARISH) && it.endTime>=candles[max(0,candles.lastIndex-5)].time }
        if (bullSweep && bullFvg && bullMss) marks+=m(Tag.ICT_2022_MODEL,candles[max(0,candles.lastIndex-5)],last,candles.takeLast(6).minOf { it.low },last.high,last.time,"Sweep + bullish MSS + FVG candidate")
        if (bearSweep && bearFvg && bearMss) marks+=m(Tag.ICT_2022_MODEL,candles[max(0,candles.lastIndex-5)],last,last.low,candles.takeLast(6).maxOf { it.high },last.time,"Sweep + bearish MSS + FVG candidate")

        val local=Instant.ofEpochMilli(last.time).atZone(zone).toLocalTime()
        val minute=local.hour*60+local.minute
        if ((minute in 180..240 || minute in 600..660 || minute in 840..900) && (bullFvg||bearFvg))
            marks+=m(Tag.SILVER_BULLET,candles[max(0,candles.lastIndex-3)],last,candles.takeLast(4).minOf { it.low },candles.takeLast(4).maxOf { it.high },last.time,"Silver Bullet time-window plus FVG candidate")
        if ((bullSweep||bearSweep) && (bullFvg||bearFvg))
            marks+=m(Tag.JUDAS_SWING,candles[max(0,candles.lastIndex-3)],last,candles.takeLast(4).minOf { it.low },candles.takeLast(4).maxOf { it.high },last.time,"Sweep + FVG candidate; session direction needs validation")
        val bullOb=marks.lastOrNull { it.tag==Tag.ORDER_BLOCK_BULLISH && !it.invalidated }
        val bearOb=marks.lastOrNull { it.tag==Tag.ORDER_BLOCK_BEARISH && !it.invalidated }
        if (bullOb!=null && marks.any { it.tag==Tag.FVG_BULLISH && it.low<=bullOb.high && it.high>=bullOb.low })
            marks+=m(Tag.UNICORN,last,last,bullOb.low,bullOb.high,last.time,"Bullish OB/FVG overlap candidate")
        if (bearOb!=null && marks.any { it.tag==Tag.FVG_BEARISH && it.low<=bearOb.high && it.high>=bearOb.low })
            marks+=m(Tag.UNICORN,last,last,bearOb.low,bearOb.high,last.time,"Bearish OB/FVG overlap candidate")
        val asia=marks.lastOrNull { it.tag==Tag.ASIA_RANGE }
        if (asia!=null) {
            marks+=m(Tag.AMD_ACCUMULATION,candles[max(0,candles.lastIndex-3)],last,asia.low,asia.high,last.time,"Asia/range accumulation candidate")
            if (bullSweep||bearSweep) marks+=m(Tag.AMD_MANIPULATION,candles[max(0,candles.lastIndex-3)],last,asia.low,asia.high,last.time,"Liquidity raid/manipulation candidate")
            if ((bullSweep&&bullFvg)||(bearSweep&&bearFvg)) marks+=m(Tag.AMD_DISTRIBUTION,candles[max(0,candles.lastIndex-3)],last,candles.takeLast(4).minOf { it.low },candles.takeLast(4).maxOf { it.high },last.time,"Post-raid displacement/distribution candidate")
            if (bullSweep||bearSweep) marks+=m(Tag.POWER_OF_THREE,candles[max(0,candles.lastIndex-3)],last,asia.low,asia.high,last.time,"Accumulation range then sweep candidate")
        }
        if (bullOb!=null && bullFvg) marks+=m(Tag.FVG_OB_ENTRY,last,last,bullOb.low,bullOb.high,last.time,"Bullish FVG/OB confluence entry candidate")
        if (bearOb!=null && bearFvg) marks+=m(Tag.FVG_OB_ENTRY,last,last,bearOb.low,bearOb.high,last.time,"Bearish FVG/OB confluence entry candidate")
        val bullBreaker=marks.lastOrNull { it.tag==Tag.BREAKER_BULLISH && !it.invalidated }
        val bearBreaker=marks.lastOrNull { it.tag==Tag.BREAKER_BEARISH && !it.invalidated }
        if (bullBreaker!=null && bullFvg) marks+=m(Tag.BREAKER_FVG_ENTRY,last,last,bullBreaker.low,bullBreaker.high,last.time,"Bullish breaker/FVG confluence candidate")
        if (bearBreaker!=null && bearFvg) marks+=m(Tag.BREAKER_FVG_ENTRY,last,last,bearBreaker.low,bearBreaker.high,last.time,"Bearish breaker/FVG confluence candidate")
        val oteBull=marks.lastOrNull { it.tag==Tag.OTE_BULLISH }
        val oteBear=marks.lastOrNull { it.tag==Tag.OTE_BEARISH }
        if (bullSweep && oteBull!=null && last.close>=oteBull.low && last.close<=oteBull.high)
            marks+=m(Tag.OTE_ENTRY,last,last,oteBull.low,oteBull.high,last.time,"Bullish liquidity raid inside bullish OTE band")
        if (bearSweep && oteBear!=null && last.close>=oteBear.low && last.close<=oteBear.high)
            marks+=m(Tag.OTE_ENTRY,last,last,oteBear.low,oteBear.high,last.time,"Bearish liquidity raid inside bearish OTE band")
        if (bullSweep||bearSweep) marks+=m(Tag.MMXM,candles[max(0,candles.lastIndex-3)],last,candles.takeLast(4).minOf { it.low },candles.takeLast(4).maxOf { it.high },last.time,"MMXM heuristic; requires HTF context")

        val now=cfg.nowMillis ?: last.time
        val blockers=mutableListOf<String>()
        if (cfg.feedState!=FeedState.LIVE) blockers+="Feed state is " + cfg.feedState
        val feedTime=cfg.latestFeedTimeMillis ?: last.time
        if (now-feedTime>cfg.maxDataAgeMillis) blockers+="Latest feed tick is stale"
        if (cfg.dailyLossPercent>=cfg.dailyLossLimitPercent) blockers+="Daily loss limit reached"
        val atrPercent=atr.last()/abs(last.close).coerceAtLeast(0.0000001f)*100f
        if (atrPercent>cfg.maxAtrPercent) blockers+="ATR volatility exceeds configured maximum"
        val spreadReady=cfg.actualSpreadBps!=null && cfg.actualSpreadBps<=cfg.maxSpreadBps
        val tradeFiltersReady=spreadReady && cfg.newsCalendarComplete
        val currencies=currenciesFor(input.symbol)
        val hotNews=input.news.filter { it.impact==Impact.HIGH && it.currency.uppercase() in currencies &&
            abs(it.timeMillis-now)<=cfg.newsBufferMinutes.coerceAtLeast(0)*60_000L }
        if (hotNews.isNotEmpty()) {
            blockers+="High-impact news in configured no-trade window"
            marks+=m(Tag.NEWS_WARNING,last,last,last.low,last.high,last.time,"High impact: " + hotNews.joinToString { it.title.ifBlank { it.currency } })
        }
        if (cfg.actualSpreadBps==null) marks+=m(Tag.SPREAD_WARNING,last,last,last.low,last.high,last.time,"Spread source unavailable; analytical signal cannot pass execution filters")
        else if (cfg.actualSpreadBps>cfg.maxSpreadBps) marks+=m(Tag.SPREAD_WARNING,last,last,last.low,last.high,last.time,"Spread gate blocked setup")
        if (cfg.feedState!=FeedState.LIVE || now-feedTime>cfg.maxDataAgeMillis) marks+=m(Tag.STALE_DATA_WARNING,last,last,last.low,last.high,last.time,"Feed freshness/state gate blocked setup")
        val sessionOk=!cfg.requireNyOrLondonSession || marks.any { it.tag==Tag.LONDON_KILL_ZONE || it.tag==Tag.NY_AM_KILL_ZONE }
        if (!sessionOk) blockers+="Outside configured London/NY session"
        if (bullSweep && bullFvg && bullMss && blockers.isEmpty()) signal(true,candles,marks,cfg,tradeFiltersReady)?.let { signals+=it }
        else if (bearSweep && bearFvg && bearMss && blockers.isEmpty()) signal(false,candles,marks,cfg,tradeFiltersReady)?.let { signals+=it }
        else if (bullSweep||bearSweep) marks+=m(Tag.NO_TRADE,last,last,last.low,last.high,last.time,blockers.firstOrNull() ?: "Signal confirmations are incomplete")
        signals.lastOrNull()?.let { s ->
            marks+=m(if(s.bullish)Tag.BUY_SETUP else Tag.SELL_SETUP,last,last,min(s.entry,s.stopLoss),max(s.entry,s.stopLoss),last.time,s.reasons.joinToString(" + "))
            if (!s.tradeFiltersPassed) marks+=m(Tag.NO_TRADE,last,last,last.low,last.high,last.time,"Analytical setup only: current spread and/or complete news calendar is missing")
            marks+=m(Tag.ENTRY,last,last,s.entry,s.entry,last.time,"Analytical level only; no order sent")
            marks+=m(Tag.STOP_LOSS,last,last,s.stopLoss,s.stopLoss,last.time,"Risk level only")
            marks+=m(Tag.TAKE_PROFIT_1,last,last,s.takeProfit1,s.takeProfit1,last.time,"Target 1")
            marks+=m(Tag.TAKE_PROFIT_2,last,last,s.takeProfit2,s.takeProfit2,last.time,"Target 2")
            marks+=m(Tag.TAKE_PROFIT_3,last,last,s.takeProfit3,s.takeProfit3,last.time,"Target 3")
        }

        val warnings=mutableListOf<String>()
        if (input.relatedMarkets.isEmpty()) warnings+="SMT is awaiting synchronized related-market OHLC data."
        if (input.timeframes.isEmpty()) warnings+="MTF bias/confluence is awaiting actual timeframe candles."
        if (input.news.isEmpty() || !cfg.newsCalendarComplete) warnings+="News calendar coverage is not confirmed; empty input does not mean no news."
        if (cfg.actualSpreadBps==null) warnings+="Spread is unavailable, so a signal is never cleared for execution."
        warnings+="Real-money/MT5 execution is intentionally disabled until a broker adapter and explicit user authorization exist."
        warnings+="Entry models are rule-based candidates; volume, macro, calendar and proprietary ICT judgement may require additional provider inputs."
        return Result(marks.distinctBy { listOf(it.tag,it.startTime,it.endTime,it.low,it.high,it.note) }
            .sortedWith(compareBy<Mark> { it.confirmedAt }.thenBy { it.tag.name }),signals,capabilities(input,true),warnings)
    }

    /** Prefix-only replay prevents a signal from seeing future candles. Simplified fills; not broker-accurate. */
    fun backtest(input: Input, startIndex: Int = 60, maxBars: Int = 200): BacktestStats {
        val c=input.candles.filter { it.time>0L }.distinctBy { it.time }.sortedBy { it.time }
        if(c.size<8) return BacktestStats(0,0,0,0f,0f,0f,0f,0f,"NOT ENOUGH DATA")
        val first=startIndex.coerceAtLeast(5).coerceAtMost(c.lastIndex); val last=min(c.lastIndex,first+maxBars.coerceIn(1,300))
        val results=mutableListOf<Float>()
        for(i in first until last) {
            val prefix=c.take(i+1); val r=analyze(input.copy(candles=prefix,config=input.config.copy(nowMillis=prefix.last().time,latestFeedTimeMillis=prefix.last().time,feedState=FeedState.LIVE)))
            val s=r.signals.lastOrNull() ?: continue
            if (!s.tradeFiltersPassed) continue
            val risk=abs(s.entry-s.stopLoss).coerceAtLeast(0.0000001f)
            var outcome:Float?=null
            for(bar in c.drop(i+1).take(30)) {
                val stop=if(s.bullish)bar.low<=s.stopLoss else bar.high>=s.stopLoss
                val tp=if(s.bullish)bar.high>=s.takeProfit2 else bar.low<=s.takeProfit2
                if(stop){outcome=-1f;break}
                if(tp){outcome=abs(s.takeProfit2-s.entry)/risk;break}
            }
            if(outcome!=null)results+=outcome
        }
        val wins=results.count { it>0f }; val losses=results.count { it<=0f }
        val gw=results.filter { it>0f }.sum(); val gl=-results.filter { it<0f }.sum()
        var equity=0f; var peak=0f; var dd=0f
        results.forEach { equity+=it; peak=max(peak,equity); dd=max(dd,peak-equity) }
        return BacktestStats(results.size,wins,losses,if(results.isEmpty())0f else wins*100f/results.size,
            results.sum(),if(gl==0f){if(gw>0f)Float.POSITIVE_INFINITY else 0f}else gw/gl,dd,
            if(results.isEmpty())0f else results.average().toFloat(),
            "Prefix replay; SL wins same-bar tie; slippage/spread/commission not modeled.")
    }

    fun realMoneyExecutionAllowed(): Boolean = false

    private fun addPivotHierarchy(c:List<Candle>, highPivots:List<Int>, lowPivots:List<Int>, right:Int, out:MutableList<Mark>) {
        highPivots.forEach { i -> out+=m(Tag.SHORT_TERM_HIGH,c[i],c[i],c[i].high,c[i].high,c[i+right].time,"Confirmed short-term high") }
        lowPivots.forEach { i -> out+=m(Tag.SHORT_TERM_LOW,c[i],c[i],c[i].low,c[i].low,c[i+right].time,"Confirmed short-term low") }
        val midHigh=mutableListOf<Int>(); val midLow=mutableListOf<Int>()
        for (j in 1 until highPivots.lastIndex) {
            val p=highPivots[j]
            if(c[p].high>c[highPivots[j-1]].high && c[p].high>c[highPivots[j+1]].high) {
                midHigh+=p
                out+=m(Tag.INTERMEDIATE_TERM_HIGH,c[p],c[highPivots[j+1]+right],c[p].high,c[p].high,c[highPivots[j+1]+right].time,"Pivot higher than adjacent confirmed swing highs")
            }
        }
        for (j in 1 until lowPivots.lastIndex) {
            val p=lowPivots[j]
            if(c[p].low<c[lowPivots[j-1]].low && c[p].low<c[lowPivots[j+1]].low) {
                midLow+=p
                out+=m(Tag.INTERMEDIATE_TERM_LOW,c[p],c[lowPivots[j+1]+right],c[p].low,c[p].low,c[lowPivots[j+1]+right].time,"Pivot lower than adjacent confirmed swing lows")
            }
        }
        for (j in 1 until midHigh.lastIndex) {
            val p=midHigh[j]
            if(c[p].high>c[midHigh[j-1]].high && c[p].high>c[midHigh[j+1]].high)
                out+=m(Tag.LONG_TERM_HIGH,c[p],c[midHigh[j+1]],c[p].high,c[p].high,c[midHigh[j+1]+right].time,"Fractal long-term high candidate")
        }
        for (j in 1 until midLow.lastIndex) {
            val p=midLow[j]
            if(c[p].low<c[midLow[j-1]].low && c[p].low<c[midLow[j+1]].low)
                out+=m(Tag.LONG_TERM_LOW,c[p],c[midLow[j+1]],c[p].low,c[p].low,c[midLow[j+1]+right].time,"Fractal long-term low candidate")
        }
    }

    private fun addRangeMarks(c:List<Candle>,cfg:Config,o:MutableList<Mark>) {
        val w=c.takeLast(cfg.dealingRangeLookback.coerceAtLeast(5)); val hi=w.maxOf { it.high }; val lo=w.minOf { it.low }; val eq=(hi+lo)/2f
        o+=m(Tag.EQUILIBRIUM,w.first(),w.last(),eq,eq,w.last().time,"50% equilibrium")
        o+=m(Tag.PREMIUM,w.first(),w.last(),eq,hi,w.last().time,"Above equilibrium")
        o+=m(Tag.DISCOUNT,w.first(),w.last(),lo,eq,w.last().time,"Below equilibrium")
        o+=m(Tag.PRICE_DELIVERY_RANGE,w.first(),w.last(),lo,hi,w.last().time,"Recent dealing range")
        o+=m(Tag.IRL,w.first(),w.last(),lo,hi,w.last().time,"Internal range liquidity context")
        o+=m(Tag.ERL,w.first(),w.last(),lo,hi,w.last().time,"External range liquidity context")
        o+=m(Tag.DRAW_ON_LIQUIDITY_HIGH,w.last(),w.last(),hi,hi,w.last().time,"Upper liquidity target candidate")
        o+=m(Tag.DRAW_ON_LIQUIDITY_LOW,w.last(),w.last(),lo,lo,w.last().time,"Lower liquidity target candidate")
        o+=m(Tag.RANGE_EXPANSION,w.last(),w.last(),lo,hi,w.last().time,"Range window; expansion needs confirmation")
        o+=m(Tag.OTE,w.first(),w.last(),lo+(hi-lo)*0.62f,lo+(hi-lo)*0.79f,w.last().time,"Generic 62-79% OTE range; use directional OTE marks")
        o+=m(Tag.OTE_BULLISH,w.first(),w.last(),lo+(hi-lo)*0.21f,lo+(hi-lo)*0.38f,w.last().time,"Bullish 62-79% retracement from range high")
        o+=m(Tag.OTE_BEARISH,w.first(),w.last(),lo+(hi-lo)*0.62f,lo+(hi-lo)*0.79f,w.last().time,"Bearish 62-79% retracement from range low")
        o+=m(Tag.FIB_62,w.last(),w.last(),lo+(hi-lo)*0.62f,lo+(hi-lo)*0.62f,w.last().time,"62% retracement reference")
        o+=m(Tag.FIB_705,w.last(),w.last(),lo+(hi-lo)*0.705f,lo+(hi-lo)*0.705f,w.last().time,"70.5% OTE reference")
        o+=m(Tag.FIB_79,w.last(),w.last(),lo+(hi-lo)*0.79f,lo+(hi-lo)*0.79f,w.last().time,"79% retracement reference")
    }

    private fun addCalendarSessionMarks(c:List<Candle>,zone:ZoneId,o:MutableList<Mark>) {
        val dated=c.map { it to Instant.ofEpochMilli(it.time).atZone(zone) }
        val latestDate=dated.last().second.toLocalDate()
        val days=dated.groupBy { it.second.toLocalDate() }.toSortedMap()
        val prevDay=days.keys.filter { it<latestDate }.lastOrNull()
        if(prevDay!=null) {
            val g=days.getValue(prevDay); val bars=g.map { it.first }
            o+=m(Tag.PDH,bars.first(),bars.last(),bars.maxOf { it.high },bars.maxOf { it.high },bars.last().time,"Previous supplied local day high")
            o+=m(Tag.PDL,bars.first(),bars.last(),bars.minOf { it.low },bars.minOf { it.low },bars.last().time,"Previous supplied local day low")
        }
        val wf=WeekFields.ISO
        val weekKey=latestDate.get(wf.weekBasedYear()) to latestDate.get(wf.weekOfWeekBasedYear())
        val weeks=dated.groupBy { it.second.get(wf.weekBasedYear()) to it.second.get(wf.weekOfWeekBasedYear()) }
        val prevWeek=weeks.keys.filter { it.first<weekKey.first || (it.first==weekKey.first&&it.second<weekKey.second) }
            .maxWithOrNull(compareBy<Pair<Int,Int>> { it.first }.thenBy { it.second })
        if(prevWeek!=null) {
            val bars=weeks.getValue(prevWeek).map { it.first }
            o+=m(Tag.PWH,bars.first(),bars.last(),bars.maxOf { it.high },bars.maxOf { it.high },bars.last().time,"Previous supplied ISO week high")
            o+=m(Tag.PWL,bars.first(),bars.last(),bars.minOf { it.low },bars.minOf { it.low },bars.last().time,"Previous supplied ISO week low")
        }
        val today=days[latestDate].orEmpty().map { it.first }
        if(today.isNotEmpty())o+=m(Tag.DAILY_OPEN,today.first(),today.first(),today.first().open,today.first().open,today.first().time,"First supplied candle of local day")
        dated.filter { it.second.hour==0 && it.second.minute==0 }.forEach { (bar, _) ->
            o+=m(Tag.MIDNIGHT_OPEN,bar,bar,bar.open,bar.open,bar.time,"Observed midnight-open candle; omitted when feed lacks that timestamp")
        }
        val currentWeek=dated.filter { it.second.get(wf.weekBasedYear())==weekKey.first && it.second.get(wf.weekOfWeekBasedYear())==weekKey.second }.map { it.first }
        if(currentWeek.isNotEmpty())o+=m(Tag.WEEKLY_OPEN,currentWeek.first(),currentWeek.first(),currentWeek.first().open,currentWeek.first().open,currentWeek.first().time,"First supplied candle of local week")
        // Directional bias is computed from supplied completed period closes, not guessed future data.
        val dayKeys=days.keys.sorted()
        if(dayKeys.size>=2) {
            val current=days.getValue(dayKeys.last()).map { it.first }; val previous=days.getValue(dayKeys[dayKeys.lastIndex-1]).map { it.first }
            val tag=if(current.last().close>=previous.last().close)Tag.DAILY_BIAS_BULLISH else Tag.DAILY_BIAS_BEARISH
            o+=m(tag,current.first(),current.last(),current.minOf { it.low },current.maxOf { it.high },current.last().time,"Local daily close compared with prior supplied day")
        }
        val sortedWeeks=weeks.keys.sortedWith(compareBy<Pair<Int,Int>> { it.first }.thenBy { it.second })
        if(sortedWeeks.size>=2) {
            val current=weeks.getValue(sortedWeeks.last()).map { it.first }; val previous=weeks.getValue(sortedWeeks[sortedWeeks.lastIndex-1]).map { it.first }
            val tag=if(current.last().close>=previous.last().close)Tag.WEEKLY_BIAS_BULLISH else Tag.WEEKLY_BIAS_BEARISH
            o+=m(tag,current.first(),current.last(),current.minOf { it.low },current.maxOf { it.high },current.last().time,"Weekly close compared with prior supplied week")
        }
        val months=dated.groupBy { it.second.year*100+it.second.monthValue }.toSortedMap()
        if(months.size>=2) {
            val keys=months.keys.toList(); val current=months.getValue(keys.last()).map { it.first }; val previous=months.getValue(keys[keys.lastIndex-1]).map { it.first }
            val tag=if(current.last().close>=previous.last().close)Tag.MONTHLY_BIAS_BULLISH else Tag.MONTHLY_BIAS_BEARISH
            o+=m(tag,current.first(),current.last(),current.minOf { it.low },current.maxOf { it.high },current.last().time,"Monthly close compared with prior supplied month")
        }
        val latest=c.last(); val latestTime=Instant.ofEpochMilli(latest.time).atZone(zone).toLocalTime()
        val minute=latestTime.hour*60+latestTime.minute
        if(minute in 590..610 || minute in 650..670 || minute in 830..850 || minute in 915..945)
            o+=m(Tag.MACRO_WINDOW,latest,latest,latest.low,latest.high,latest.time,"ICT macro time window in configured time zone")
        val sessions=dated.groupBy { pair ->
            val z=pair.second; val h=z.hour
            val s=when { h>=19||h<=1->"ASIA"; h in 2..5->"LONDON"; h in 7..9->"NY_AM"; h in 13..16->"NY_PM"; h in 10..12->"LONDON_CLOSE"; else->"OTHER" }
            val date=if(s=="ASIA"&&h<=1)z.toLocalDate().minusDays(1) else z.toLocalDate()
            date to s
        }
        sessions.forEach { (key,vals) ->
            if(key.second=="OTHER"||vals.isEmpty())return@forEach
            val bars=vals.map { it.first }; val hi=bars.maxOf { it.high }; val lo=bars.minOf { it.low }
            val tag=when(key.second){"ASIA"->Tag.ASIA_RANGE;"LONDON"->Tag.LONDON_KILL_ZONE;"NY_AM"->Tag.NY_AM_KILL_ZONE;"NY_PM"->Tag.NY_PM;else->Tag.LONDON_CLOSE}
            val note=key.second+" observed range in "+zone.id+"; partial if session candles are incomplete"
            o+=m(tag,bars.first(),bars.last(),lo,hi,bars.last().time,note)
            o+=m(Tag.SESSION_OPEN,bars.first(),bars.first(),bars.first().open,bars.first().open,bars.first().time,key.second+" open")
            o+=m(Tag.SESSION_HIGH,bars.first(),bars.last(),hi,hi,bars.last().time,key.second+" high")
            o+=m(Tag.SESSION_LOW,bars.first(),bars.last(),lo,lo,bars.last().time,key.second+" low")
        }
    }

    private fun addSmtMarks(c:List<Candle>,related:Map<String,List<Candle>>,o:MutableList<Mark>) {
        if(related.isEmpty()||c.size<5)return
        val w=c.takeLast(20); val pHigh=w.dropLast(1).maxOf { it.high }; val pLow=w.dropLast(1).minOf { it.low }; val p=w.last()
        related.forEach { (name,raw)->
            val byTime=raw.filter { it.time>0L }.associateBy { it.time }
            val aligned=w.mapNotNull { x->byTime[x.time]?.let { x to it } }
            if(aligned.size<5)return@forEach
            val now=aligned.last(); val old=aligned.dropLast(1)
            if(now.first.low<pLow&&now.second.low>=old.minOf { it.second.low })
                o+=m(Tag.SMT_BULLISH,aligned.first().first,now.first,now.first.low,now.second.low,now.first.time,"Lower low not confirmed by aligned "+name)
            if(now.first.high>pHigh&&now.second.high<=old.maxOf { it.second.high })
                o+=m(Tag.SMT_BEARISH,aligned.first().first,now.first,now.second.high,now.first.high,now.first.time,"Higher high not confirmed by aligned "+name)
        }
    }

    private fun addMtfMarks(frames:Map<String,List<Candle>>,o:MutableList<Mark>) {
        val bias=frames.mapNotNull { (name,raw)->
            val c=raw.filter { it.time>0L }.sortedBy { it.time }; if(c.size<5)null else {
                val w=c.takeLast(20); val mid=(w.maxOf { it.high }+w.minOf { it.low })/2f
                name to when { c.last().close>mid&&c.last().close>c[c.lastIndex-1].close->1
                    c.last().close<mid&&c.last().close<c[c.lastIndex-1].close->-1 else->0 }
            }
        }
        val bars=frames.values.filter { it.isNotEmpty() }.map { it.last() }; if(bars.isEmpty())return
        val bull=bias.count { it.second>0 }; val bear=bias.count { it.second<0 }
        if(bull>bear)o+=m(Tag.HTF_BIAS_BULLISH,bars.first(),bars.last(),bars.minOf { it.low },bars.maxOf { it.high },bars.maxOf { it.time },"Supplied timeframe directional proxy")
        if(bear>bull)o+=m(Tag.HTF_BIAS_BEARISH,bars.first(),bars.last(),bars.minOf { it.low },bars.maxOf { it.high },bars.maxOf { it.time },"Supplied timeframe directional proxy")
        if(bull>0&&bear>0)o+=m(Tag.MTF_CONFLICT,bars.first(),bars.last(),bars.minOf { it.low },bars.maxOf { it.high },bars.maxOf { it.time },"Timeframe disagreement")
        else if(bias.count { it.second!=0 }>=2)o+=m(Tag.MTF_CONFLUENCE,bars.first(),bars.last(),bars.minOf { it.low },bars.maxOf { it.high },bars.maxOf { it.time },"Multiple supplied timeframes aligned")
    }

    private fun signal(bull:Boolean,c:List<Candle>,marks:List<Mark>,cfg:Config,tradeFiltersReady:Boolean):Signal? {
        val last=c.last(); val w=c.takeLast(20); val entry=last.close
        val sl=if(bull)w.minOf { it.low } else w.maxOf { it.high }; val risk=abs(entry-sl)
        if(!risk.isFinite()||risk<=0f)return null
        if(risk/abs(entry).coerceAtLeast(0.0000001f)*100f>cfg.maxStopLossPercent)return null
        val tp2=entry+(if(bull)2f else -2f)*risk
        if(abs(tp2-entry)/risk<cfg.minimumRiskReward)return null
        val tp3=if(bull)max(w.maxOf { it.high },entry+3f*risk) else min(w.minOf { it.low },entry-3f*risk)
        val f=marks.count { it.confirmedAt>=c[max(0,c.lastIndex-5)].time && it.tag in setOf(Tag.FVG_BULLISH,Tag.FVG_BEARISH,Tag.MSS_BULLISH,Tag.MSS_BEARISH,Tag.CHOCH_BULLISH,Tag.CHOCH_BEARISH,Tag.EQH,Tag.EQL) }
        return Signal(bull,last.time,entry,sl,entry+(if(bull)1f else -1f)*risk,tp2,tp3,2f,
            cfg.riskPercent.coerceIn(0.01f,2f),min(10,3+f),tradeFiltersReady,
            listOf("Liquidity raid","MSS/CHoCH","FVG confluence","2R plan"))
    }

    private fun trueRanges(c:List<Candle>,p:Int):List<Float> {
        val tr=c.indices.map { i->val x=c[i]; if(i==0)x.high-x.low else max(x.high-x.low,max(abs(x.high-c[i-1].close),abs(x.low-c[i-1].close))) }
        return c.indices.map { i->tr.subList(max(0,i-p+1),i+1).average().toFloat() }
    }
    private fun classify(c:List<Candle>,idx:List<Int>,high:Boolean,o:MutableList<Mark>) {
        var prev:Float?=null
        idx.forEach { i->val x=c[i]; val p=if(high)x.high else x.low; prev?.let { old->
            val tag=when{high&&p>old->Tag.HH;high->Tag.LH;!high&&p>old->Tag.HL;else->Tag.LL}
            o+=m(tag,x,x,p,p,x.time,"Swing classification")
            o+=m(if(high)Tag.EXTERNAL_STRUCTURE else Tag.INTERNAL_STRUCTURE,x,x,p,p,x.time,"Structure hierarchy candidate")
        };prev=p}
    }
    private fun currenciesFor(symbol:String):Set<String> {
        val s=symbol.uppercase().replace("/","").replace("-","")
        return listOf("USD","EUR","GBP","JPY","CHF","CAD","AUD","NZD","XAU").filter { s.contains(it) }.toSet().ifEmpty { setOf("USD") }
    }
    private fun m(tag:Tag,a:Candle,b:Candle,low:Float,high:Float,confirmed:Long,note:String,invalid:Boolean=false)=
        Mark(tag,a.time,b.time,min(low,high),max(low,high),confirmed,invalid,note)

    private fun capabilities(input:Input,hasBars:Boolean)=listOf(
        Capability("Structure, BOS/CHoCH/MSS, protected/internal/external",hasBars,"Confirmed OHLC pivot rules"),
        Capability("Liquidity, EQH/EQL, BSL/SSL, sweeps, IRL/ERL",hasBars,"Candle-derived candidates"),
        Capability("FVG/IFVG/BPR/CE/displacement",hasBars,"OHLC heuristics; actual volume is not supplied"),
        Capability("Order/blocker/mitigation/rejection/propulsion/vacuum",hasBars,"Rule-based zone candidates"),
        Capability("Premium/discount/equilibrium/OTE/dealing range",hasBars,"Configured rolling range"),
        Capability("Sessions, kill zones, PDH/PDL/PWH/PWL and opens",hasBars,"IANA time zone and observed candles"),
        Capability("Silver Bullet/Judas/Turtle Soup/Unicorn/ICT 2022/PO3/MMXM",hasBars,"Heuristic candidates; require contextual validation"),
        Capability("SMT / related instruments",input.relatedMarkets.isNotEmpty(),"Requires synchronized real related-market OHLC"),
        Capability("Multi-timeframe bias/confluence",input.timeframes.isNotEmpty(),"Requires actual bars per timeframe"),
        Capability("News/risk/spread/feed filters",hasBars,"Calendar completeness and spread depend on supplied provider data"),
        Capability("Backtesting",hasBars,"Historical prefix replay; simplified fills"),
        Capability("MT5 / real-money execution",false,"Disabled pending broker adapter and explicit authorization")
    )
}
