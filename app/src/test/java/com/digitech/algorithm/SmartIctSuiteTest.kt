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
    }

    @Test fun duplicateChartDataCannotMasqueradeAsSmt() {
        val b = bars()
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = b,
            relatedMarkets = mapOf("EURUSD" to b),
            timeframes = mapOf("1H" to b, "4H" to b)
        ))
        assertFalse(result.capabilities.any { it.name.startsWith("SMT") && it.ready })
        assertFalse(result.capabilities.any { it.name.startsWith("Multi-timeframe") && it.ready })
        assertFalse(result.marks.any {
            it.tag == SmartIctSuite.Tag.HTF_BIAS_BULLISH ||
                it.tag == SmartIctSuite.Tag.HTF_BIAS_BEARISH ||
                it.tag == SmartIctSuite.Tag.MTF_CONFLUENCE ||
                it.tag == SmartIctSuite.Tag.MTF_CONFLICT
        })
        assertFalse(result.marks.any { it.tag == SmartIctSuite.Tag.SMT_BULLISH || it.tag == SmartIctSuite.Tag.SMT_BEARISH })
    }


    @Test fun smtReadinessRequiresFiveValidAlignedIndependentMarketBars() {
        val chart = bars()
        val independent = chart.mapIndexed { i, bar ->
            Candle(bar.time, bar.open + 0.1f, bar.high + 0.2f, bar.low + 0.05f, bar.close + 0.1f)
        }
        val ready = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = chart,
            relatedMarkets = mapOf("GBPUSD" to independent)
        ))
        assertTrue(ready.capabilities.any { it.name.startsWith("SMT") && it.ready })

        val malformed = independent.mapIndexed { i, bar ->
            if (i < 6) Candle(bar.time, bar.open, bar.low - 1f, bar.low, bar.close) else bar
        }
        val notReady = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = chart,
            relatedMarkets = mapOf("GBPUSD" to malformed)
        ))
        assertFalse(notReady.capabilities.any { it.name.startsWith("SMT") && it.ready })
        assertFalse(notReady.marks.any {
            it.tag == SmartIctSuite.Tag.SMT_BULLISH || it.tag == SmartIctSuite.Tag.SMT_BEARISH
        })
    }

    @Test fun independentHigherTimeframeBarsCanProduceMtfBias() {
        val chart = bars()
        val start = chart.first().time
        val higher = listOf(
            floatArrayOf(9f, 10f, 8f, 9f),
            floatArrayOf(9f, 11f, 8.5f, 10f),
            floatArrayOf(10f, 12f, 9f, 11f),
            floatArrayOf(11f, 13f, 10f, 12f),
            floatArrayOf(12f, 14f, 11f, 13.5f)
        ).mapIndexed { i,v ->
            Candle(start + i * 3_600_000L, v[0], v[1], v[2], v[3])
        }
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = chart,
            timeframes = mapOf("1H" to higher)
        ))
        assertTrue(result.capabilities.any { it.name.startsWith("Multi-timeframe") && it.ready })
        assertTrue(result.marks.any { it.tag == SmartIctSuite.Tag.HTF_BIAS_BULLISH })
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
        assertFalse(result.capabilities.any { it.name.startsWith("News calendar + live spread") && it.ready })
        assertFalse(SmartIctSuite.realMoneyExecutionAllowed())
    }

    @Test fun invalidSpreadValuesNeverMarkNewsAndSpreadCapabilityReady() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 99f).forEach { spread ->
            val result = SmartIctSuite.analyze(SmartIctSuite.Input(
                candles = bars(),
                config = SmartIctSuite.Config(newsCalendarComplete = true, actualSpreadBps = spread)
            ))
            assertFalse("Invalid spread accepted: $spread",
                result.capabilities.any { it.name.startsWith("News calendar + live spread") && it.ready })
            assertTrue(result.marks.any { it.tag == SmartIctSuite.Tag.SPREAD_WARNING })
        }
    }

    @Test fun newsAndSpreadCapabilityRequiresProviderValues() {
        val incomplete = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = bars(),
            config = SmartIctSuite.Config(newsCalendarComplete = false, actualSpreadBps = null)
        ))
        assertFalse(incomplete.capabilities.any { it.name.startsWith("News calendar + live spread") && it.ready })

        val supplied = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = bars(),
            config = SmartIctSuite.Config(newsCalendarComplete = true, actualSpreadBps = 1.2f)
        ))
        assertTrue(supplied.capabilities.any { it.name.startsWith("News calendar + live spread") && it.ready })
    }

    private fun fixture(values: List<FloatArray>): List<Candle> {
        val start = 1_790_100_000_000L
        return values.mapIndexed { i, v ->
            Candle(start + i * 60_000L, v[0], v[1], v[2], v[3])
        }
    }

    @Test fun bullishStructureBreakIsNotConfirmedBeforePivotRightBarsExist() {
        val candles = fixture(listOf(
            floatArrayOf(9f, 10f, 8f, 9f),
            floatArrayOf(10f, 12f, 9f, 11f),
            floatArrayOf(12f, 15f, 11f, 14f),
            floatArrayOf(13f, 13.5f, 10f, 11f),
            floatArrayOf(11f, 12f, 9.5f, 10f),
            floatArrayOf(14f, 16f, 13f, 15.5f),
            floatArrayOf(15.5f, 15.8f, 14f, 15f)
        ))
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles))
        val pivot = result.marks.first { it.tag == SmartIctSuite.Tag.SWING_HIGH && it.startTime == candles[2].time }
        assertTrue(pivot.confirmedAt == candles[4].time)
        val bos = result.marks.first { it.tag == SmartIctSuite.Tag.BOS_BULLISH && it.startTime == candles[2].time }
        assertTrue(bos.confirmedAt == candles[5].time)
        assertTrue(bos.confirmedAt > pivot.confirmedAt)
    }

    @Test fun bearishFvgInvalidationRequiresCloseBeyondItsFarBoundary() {
        val candles = fixture(listOf(
            floatArrayOf(12.5f, 13f, 12f, 12.5f),
            floatArrayOf(12.4f, 12.6f, 10.8f, 11f),
            floatArrayOf(10.5f, 11f, 9.5f, 10f),
            floatArrayOf(11.5f, 12.5f, 11f, 12.2f),
            floatArrayOf(12.1f, 12.3f, 11.8f, 12f)
        ))
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles))
        assertTrue(result.marks.any { it.tag == SmartIctSuite.Tag.FVG_BEARISH })
        val invalidation = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_INVALIDATED }
        assertTrue(invalidation.confirmedAt == candles[3].time)
        assertTrue(invalidation.note.contains("close above upper boundary"))
    }


    @Test fun liquiditySweepsRequireWickThroughAndCloseBackInside() {
        val buySide = fixture(listOf(
            floatArrayOf(9f, 10f, 8f, 9f),
            floatArrayOf(10f, 12f, 9f, 11f),
            floatArrayOf(12f, 15f, 11f, 14f),
            floatArrayOf(13f, 13.5f, 10f, 11f),
            floatArrayOf(11f, 12f, 9.5f, 10f),
            floatArrayOf(14f, 16f, 13f, 14.5f)
        ))
        val buyResult = SmartIctSuite.analyze(SmartIctSuite.Input(buySide))
        assertTrue(buyResult.marks.any {
            it.tag == SmartIctSuite.Tag.LIQUIDITY_SWEEP_BUY &&
                it.confirmedAt == buySide[5].time &&
                it.note.contains("wick through, close back below")
        })

        val sellSide = fixture(listOf(
            floatArrayOf(9f, 10f, 8f, 9f),
            floatArrayOf(10f, 12f, 7f, 11f),
            floatArrayOf(11f, 13f, 5f, 8f),
            floatArrayOf(8f, 12f, 7f, 10f),
            floatArrayOf(10f, 12f, 6f, 11f),
            floatArrayOf(7f, 9f, 4f, 6f)
        ))
        val sellResult = SmartIctSuite.analyze(SmartIctSuite.Input(sellSide))
        assertTrue(sellResult.marks.any {
            it.tag == SmartIctSuite.Tag.LIQUIDITY_SWEEP_SELL &&
                it.confirmedAt == sellSide[5].time &&
                it.note.contains("wick through, close back above")
        })
    }



    @Test fun dealingRangeAndEquilibriumUseConfiguredRealCandleWindow() {
        val candles = bars()
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(
            candles = candles,
            config = SmartIctSuite.Config(dealingRangeLookback = 5)
        ))
        val range = result.marks.first { it.tag == SmartIctSuite.Tag.PRICE_DELIVERY_RANGE }
        val equilibrium = result.marks.first { it.tag == SmartIctSuite.Tag.EQUILIBRIUM }
        val premium = result.marks.first { it.tag == SmartIctSuite.Tag.PREMIUM }
        val discount = result.marks.first { it.tag == SmartIctSuite.Tag.DISCOUNT }

        // Last five real bars: low=10, high=14.2, equilibrium=12.1.
        assertTrue(kotlin.math.abs(range.low - 10f) < 0.0001f)
        assertTrue(kotlin.math.abs(range.high - 14.2f) < 0.0001f)
        assertTrue(kotlin.math.abs(equilibrium.low - 12.1f) < 0.0001f)
        assertTrue(kotlin.math.abs(premium.low - equilibrium.low) < 0.0001f)
        assertTrue(kotlin.math.abs(premium.high - range.high) < 0.0001f)
        assertTrue(kotlin.math.abs(discount.low - range.low) < 0.0001f)
        assertTrue(kotlin.math.abs(discount.high - equilibrium.high) < 0.0001f)
        assertTrue(range.startTime == candles[candles.lastIndex - 4].time)
        assertTrue(range.endTime == candles.last().time)
    }



    @Test fun bullishFvgTracksPartialFillFullFillAndInversionFromLaterCloses() {
        val candles = fixture(listOf(
            floatArrayOf(9f, 10f, 8f, 9f),
            floatArrayOf(10f, 11f, 9.5f, 10.8f),
            floatArrayOf(12f, 13f, 12f, 12.8f), // gap: 10..12
            floatArrayOf(12.5f, 12.8f, 11f, 11.5f), // enters gap, not full
            floatArrayOf(10.5f, 11f, 9f, 9.5f) // traverses and closes below lower edge
        ))
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles))
        val fvg = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_BULLISH }
        val partial = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_PARTIAL_FILL }
        val full = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_FULL_FILL }
        val invalid = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_INVALIDATED }
        val inverse = result.marks.first { it.tag == SmartIctSuite.Tag.IFVG_BEARISH }

        assertTrue(kotlin.math.abs(fvg.low - 10f) < 0.0001f)
        assertTrue(kotlin.math.abs(fvg.high - 12f) < 0.0001f)
        assertTrue(fvg.invalidated)
        assertTrue(partial.confirmedAt == candles[3].time)
        assertTrue(full.confirmedAt == candles[4].time)
        assertTrue(invalid.confirmedAt == candles[4].time)
        assertTrue(inverse.confirmedAt == candles[4].time)
    }

    @Test fun shortTermSwingRequiresConfiguredRightSideConfirmationBars() {
        val candles = fixture(listOf(
            floatArrayOf(9f, 10f, 8f, 9f),
            floatArrayOf(10f, 12f, 9f, 11f),
            floatArrayOf(11f, 15f, 10f, 14f),
            floatArrayOf(12f, 13f, 9f, 10f),
            floatArrayOf(10f, 12f, 8.5f, 11f),
            floatArrayOf(11f, 12f, 9f, 10f)
        ))
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles))
        val shortHigh = result.marks.first {
            it.tag == SmartIctSuite.Tag.SHORT_TERM_HIGH && it.startTime == candles[2].time
        }
        assertTrue(shortHigh.confirmedAt == candles[4].time)
        assertTrue(shortHigh.confirmedAt > shortHigh.startTime)
        assertTrue(shortHigh.low == 15f && shortHigh.high == 15f)
    }



    @Test fun bearishFvgTracksPartialFillFullFillAndInversionFromLaterCloses() {
        val candles = fixture(listOf(
            floatArrayOf(12.5f, 13f, 12f, 12.5f),
            floatArrayOf(12.4f, 12.6f, 9f, 9.2f),
            floatArrayOf(10f, 11f, 9.5f, 10f), // bearish gap: 11..12
            floatArrayOf(10.5f, 11.5f, 10f, 11f), // partial entry
            floatArrayOf(11f, 12.5f, 10.8f, 12.2f) // full traversal and close above
        ))
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles))
        val fvg = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_BEARISH }
        val partial = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_PARTIAL_FILL }
        val full = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_FULL_FILL }
        val invalid = result.marks.first { it.tag == SmartIctSuite.Tag.FVG_INVALIDATED }
        val inverse = result.marks.first { it.tag == SmartIctSuite.Tag.IFVG_BULLISH }

        assertTrue(kotlin.math.abs(fvg.low - 11f) < 0.0001f)
        assertTrue(kotlin.math.abs(fvg.high - 12f) < 0.0001f)
        assertTrue(fvg.invalidated)
        assertTrue(partial.confirmedAt == candles[3].time)
        assertTrue(full.confirmedAt == candles[4].time)
        assertTrue(invalid.confirmedAt == candles[4].time)
        assertTrue(inverse.confirmedAt == candles[4].time)
    }

    @Test fun bearishOrderBlockRetestAndInvalidationAreDistinguished() {
        val candles = fixture(listOf(
            floatArrayOf(10f, 10.4f, 9.8f, 10.1f),
            floatArrayOf(10f, 11f, 9.8f, 10.8f), // bullish order-block candle
            floatArrayOf(10.7f, 10.8f, 5f, 5.2f), // strong bearish displacement
            floatArrayOf(5.2f, 7f, 5f, 6f),
            floatArrayOf(6f, 11.5f, 5.8f, 11.2f) // retest and close above OB high
        ))
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles))
        val ob = result.marks.first { it.tag == SmartIctSuite.Tag.ORDER_BLOCK_BEARISH }
        val breaker = result.marks.first { it.tag == SmartIctSuite.Tag.BREAKER_BULLISH }
        val mitigation = result.marks.first { it.tag == SmartIctSuite.Tag.MITIGATION_BLOCK }

        assertTrue(kotlin.math.abs(ob.low - 9.8f) < 0.0001f)
        assertTrue(kotlin.math.abs(ob.high - 11f) < 0.0001f)
        assertTrue(ob.invalidated)
        assertTrue(breaker.confirmedAt == candles.last().time)
        assertTrue(mitigation.confirmedAt == candles.last().time)
    }


    @Test fun invalidAndDuplicateOhlcRowsAreIgnoredWithoutInventingCandles() {
        val valid = fixture(listOf(
            floatArrayOf(9f, 10f, 8f, 9f),
            floatArrayOf(10f, 12f, 9f, 11f),
            floatArrayOf(11f, 13f, 10f, 12f),
            floatArrayOf(12f, 12.5f, 9.5f, 10f),
            floatArrayOf(10f, 11f, 9f, 10.5f)
        ))
        val malformed = listOf(
            Candle(valid[1].time + 1L, 10f, 8f, 11f, 9f), // high below open/close
            Candle(valid[2].time + 1L, Float.NaN, 13f, 10f, 12f),
            Candle(valid[3].time + 1L, 10f, 11f, 9f, Float.POSITIVE_INFINITY)
        )
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles = valid + valid[2] + malformed))
        val range = result.marks.first { it.tag == SmartIctSuite.Tag.PRICE_DELIVERY_RANGE }
        assertTrue(range.startTime == valid.first().time)
        assertTrue(range.endTime == valid.last().time)
        assertTrue(result.warnings.none { it.contains("generated") })
    }

    @Test fun shortTermSwingLowIsConfirmedOnlyAfterRightSideBars() {
        val candles = fixture(listOf(
            floatArrayOf(10f, 11f, 8f, 9f),
            floatArrayOf(9f, 10f, 6f, 7f),
            floatArrayOf(7f, 9f, 4f, 5f),
            floatArrayOf(5f, 8f, 5f, 7f),
            floatArrayOf(7f, 9f, 6f, 8f),
            floatArrayOf(8f, 10f, 7f, 9f)
        ))
        val result = SmartIctSuite.analyze(SmartIctSuite.Input(candles))
        val shortLow = result.marks.first {
            it.tag == SmartIctSuite.Tag.SHORT_TERM_LOW && it.startTime == candles[2].time
        }
        assertTrue(shortLow.confirmedAt == candles[4].time)
        assertTrue(shortLow.low == 4f && shortLow.high == 4f)
    }

}
