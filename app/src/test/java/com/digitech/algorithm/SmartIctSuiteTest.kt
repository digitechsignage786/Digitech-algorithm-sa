package com.digitech.algorithm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartIctSuiteTest {
    private fun bars(): List<Candle> {
        val start = 1_790_000_000_000L
        val values = listOf(
            floatArrayOf(9f,10f,8f,9.5f),
            floatArrayOf(9.5f,11f,9f,10.8f),
            floatArrayOf(11f,13f,10.8f,12.8f), // leaves a bullish gap from bar 0
            floatArrayOf(12.8f,13f,11.8f,12f),
            floatArrayOf(12f,12.5f,10.5f,11f),
            floatArrayOf(11f,12f,10f,11.5f),
            floatArrayOf(11.5f,14f,11.4f,13.8f),
            floatArrayOf(13.8f,14.2f,12.7f,13f),
            floatArrayOf(13f,13.5f,11.5f,12f),
            floatArrayOf(12f,12.5f,10.8f,11f)
        )
        return values.mapIndexed { i, v ->
            Candle(start + i * 60_000L, v[0], v[1], v[2], v[3])
        }
    }

    @Test fun realOhlcProducesRangeAndFvgMarks() {
        val r = SmartIctSuite.analyze(SmartIctSuite.Input(bars()))
        assertTrue(r.marks.any { it.tag == SmartIctSuite.Tag.EQUILIBRIUM })
        assertTrue(r.marks.any { it.tag == SmartIctSuite.Tag.PREMIUM })
        assertTrue(r.marks.any { it.tag == SmartIctSuite.Tag.DISCOUNT })
        assertTrue(r.marks.any { it.tag == SmartIctSuite.Tag.FVG_BULLISH })
        assertTrue("Bullish FVG midpoint should be emitted", r.marks.any { it.tag == SmartIctSuite.Tag.CE_50 })
        assertTrue("Observed partial mitigation should be marked", r.marks.any { it.tag == SmartIctSuite.Tag.FVG_PARTIAL_FILL })
        assertTrue("Observed full mitigation should be marked", r.marks.any { it.tag == SmartIctSuite.Tag.FVG_FULL_FILL })
    }

    @Test fun rangeAndCandleRangeTheoryConceptsAreEmittedFromRealBars() {
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(bars()))
        val emitted = result.marks.map { it.tag }.toSet()
        val expected = setOf(
            SmartIctSuite.Tag.OTE,
            SmartIctSuite.Tag.OTE_BULLISH,
            SmartIctSuite.Tag.OTE_BEARISH,
            SmartIctSuite.Tag.FIB_62,
            SmartIctSuite.Tag.FIB_705,
            SmartIctSuite.Tag.FIB_79,
            SmartIctSuite.Tag.PRICE_DELIVERY_RANGE,
            SmartIctSuite.Tag.EQUILIBRIUM,
            SmartIctSuite.Tag.CRT_HIGH,
            SmartIctSuite.Tag.CRT_LOW,
            SmartIctSuite.Tag.CRT_MIDPOINT,
            SmartIctSuite.Tag.IPDA_RANGE_20
        )
        assertTrue("Missing emitted range concepts: " + (expected - emitted), emitted.containsAll(expected))
    }

    @Test fun synchronizedInputsAffectSmtAndMtfCapability() {
        val b = bars()
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = b,
            relatedMarkets = mapOf("EURUSD" to b),
            timeframes = mapOf("1H" to b, "4H" to b)
        ))
        assertTrue(result.capabilities.any { it.name.startsWith("SMT") && it.ready })
        assertTrue(result.capabilities.any { it.name.startsWith("Multi-timeframe") && it.ready })
    }

    @Test fun missingMarketDataDoesNotGenerateSyntheticCandles() {
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(emptyList()))
        assertTrue(result.marks.isEmpty())
        assertTrue(result.signals.isEmpty())
        assertTrue(result.warnings.any { it.contains("no candles were generated") })
    }

    @Test fun realMoneyExecutionIsExplicitlyDisabled() {
        assertFalse(SmartIctSuite.realMoneyExecutionAllowed())
    }

    @Test fun backtestReportsInsufficientHistoryHonestly() {
        val stats = SmartIctSuite.backtest(SmartIctSuite.Input(bars().take(6)))
        assertNotNull(stats)
        assertTrue(stats.coverageNote.contains("NOT ENOUGH DATA"))
        assertTrue(stats.trades == 0)
    }

    @Test fun masterConceptFamiliesAreRepresentedInTheSuite() {
        val tags = SmartIctSuite.Tag.values().map { it.name }.toSet()
        val required = setOf(
            "BOS_BULLISH", "CHOCH_BEARISH", "MSS_BULLISH", "PROTECTED_HIGH",
            "SHORT_TERM_HIGH", "INTERMEDIATE_TERM_LOW", "LONG_TERM_HIGH",
            "BSL", "SSL", "EQH", "EQL", "LIQUIDITY_SWEEP_BUY", "IRL", "ERL",
            "FVG_BULLISH", "IFVG_BEARISH", "BPR", "CE_50", "FVG_PARTIAL_FILL",
            "FVG_FULL_FILL", "FVG_INVALIDATED", "DISPLACEMENT_BULLISH",
            "ORDER_BLOCK_BULLISH", "BREAKER_BEARISH", "MITIGATION_BLOCK",
            "REJECTION_BLOCK", "PROPULSION_BLOCK", "VACUUM_BLOCK",
            "PREMIUM", "DISCOUNT", "EQUILIBRIUM", "OTE_BULLISH", "FIB_62", "FIB_705", "FIB_79",
            "ASIA_RANGE", "LONDON_KILL_ZONE", "NY_AM_KILL_ZONE", "NY_PM", "LONDON_CLOSE",
            "PDH", "PDL", "PWH", "PWL", "DAILY_OPEN", "WEEKLY_OPEN", "MIDNIGHT_OPEN", "MACRO_WINDOW",
            "SILVER_BULLET", "JUDAS_SWING", "TURTLE_SOUP", "UNICORN", "ICT_2022_MODEL",
            "POWER_OF_THREE", "MMXM", "SMT_BULLISH", "SMT_BEARISH",
            "HTF_BIAS_BULLISH", "MTF_CONFLUENCE", "MTF_CONFLICT",
            "BUY_SETUP", "SELL_SETUP", "ENTRY", "STOP_LOSS", "TAKE_PROFIT_1",
            "TAKE_PROFIT_2", "TAKE_PROFIT_3", "NO_TRADE", "NEWS_WARNING",
            "SPREAD_WARNING", "STALE_DATA_WARNING", "AMD_ACCUMULATION",
            "AMD_MANIPULATION", "AMD_DISTRIBUTION", "FVG_OB_ENTRY", "BREAKER_FVG_ENTRY", "OTE_ENTRY"
        )
        assertTrue("Missing master tags: " + (required - tags), tags.containsAll(required))
    }

    @Test fun unknownSpreadAndCalendarCannotClearExecutionFilters() {
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = bars(),
            config = SmartIctSuite.Config(newsCalendarComplete = false, actualSpreadBps = null)
        ))
        assertTrue(result.warnings.any { it.contains("News calendar coverage is not confirmed") })
        assertTrue(result.warnings.any { it.contains("Spread is unavailable") })
        assertFalse(SmartIctSuite.realMoneyExecutionAllowed())
    }

    @Test fun emittedMarksUseRealCandleTimesAndValidPriceBounds() {
        val input = SmartIctSuite.Input(bars())
        val result = SmartIctSuite.analyze(input)
        val validTimes = input.candles.map { it.time }.toSet()
        assertTrue("Expected real-OHLC marks", result.marks.isNotEmpty())
        result.marks.forEach { mark ->
            assertTrue("Start time must be a supplied candle time: ${mark.tag}", validTimes.contains(mark.startTime))
            assertTrue("End time must be a supplied candle time: ${mark.tag}", validTimes.contains(mark.endTime))
            assertTrue("Mark confirmation cannot precede its start: ${mark.tag}", mark.confirmedAt >= mark.startTime)
            assertTrue("Mark prices must be finite: ${mark.tag}", mark.low.isFinite() && mark.high.isFinite())
            assertTrue("Mark price bounds must be ordered: ${mark.tag}", mark.low <= mark.high)
        }
    }

    @Test fun missingExternalInputsRemainExplicitlyPending() {
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(bars()))
        assertTrue(result.warnings.any { it.contains("SMT is awaiting synchronized related-market OHLC") })
        assertTrue(result.warnings.any { it.contains("MTF bias/confluence is awaiting actual timeframe candles") })
        assertTrue(result.warnings.any { it.contains("News calendar coverage is not confirmed") })
        assertTrue(result.marks.none { it.tag == SmartIctSuite.Tag.SMT_BULLISH || it.tag == SmartIctSuite.Tag.SMT_BEARISH })
        assertFalse(SmartIctSuite.realMoneyExecutionAllowed())
    }

}
