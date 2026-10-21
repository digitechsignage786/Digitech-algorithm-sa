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
}
