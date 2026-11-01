package com.digitech.algorithm

import java.time.Instant
import java.time.ZoneOffset

/**
 * Local-only paper execution simulator. It never connects to a broker or places real orders.
 * One open position at a time; conservative same-candle handling gives stop loss priority.
 */
internal class PaperTradingEngine(
    initialBalance: Double = 10_000.0,
    private val riskPercent: Double = 1.0,
    private val dailyLossLimitPercent: Double = 3.0
) {
    data class Position(
        val id: Long,
        val symbol: String,
        val bullish: Boolean,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val quantity: Double,
        val openedAt: Long
    )
    data class ClosedTrade(
        val position: Position,
        val exitPrice: Double,
        val closedAt: Long,
        val pnl: Double,
        val resultR: Double,
        val reason: String
    )
    data class Snapshot(
        val balance: Double,
        val equity: Double,
        val dailyRealizedPnl: Double,
        val openPosition: Position?,
        val closedTrades: List<ClosedTrade>,
        val blockedReason: String?
    )

    private var balance = initialBalance
    private var nextId = 1L
    private var open: Position? = null
    private val history = mutableListOf<ClosedTrade>()
    private var dayKey: String? = null
    private var dayStartBalance = initialBalance
    private var dailyPnl = 0.0
    private var blocked: String? = null

    @Synchronized
    fun openFromSignal(symbol: String, signal: SmartIctSuite.Signal, timeMillis: Long): Position? {
        blocked = null
        if (open != null) return reject("An open paper position already exists")
        if (!signal.tradeFiltersPassed) return reject("ICT execution filters did not pass")
        if (symbol.isBlank() || timeMillis <= 0L) return reject("Symbol or timestamp is invalid")
        val entry = signal.entry.toDouble()
        val stop = signal.stopLoss.toDouble()
        val target = signal.takeProfit2.toDouble()
        val riskDistance = kotlin.math.abs(entry - stop)
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() ||
            entry <= 0.0 || riskDistance <= 0.0 ||
            (if (signal.bullish) target <= entry || stop >= entry else target >= entry || stop <= entry)
        ) return reject("Entry, stop-loss or TP2 levels are invalid")
        rollDay(timeMillis)
        if (dailyPnl <= -dayStartBalance * dailyLossLimitPercent / 100.0)
            return reject("Daily loss limit reached")
        if (riskPercent <= 0.0 || riskPercent > 2.0 || balance <= 0.0)
            return reject("Risk configuration is outside the safe 0–2% range")
        val riskCash = balance * riskPercent / 100.0
        val quantity = riskCash / riskDistance
        if (!quantity.isFinite() || quantity <= 0.0) return reject("Position size could not be calculated")
        val p = Position(nextId++, symbol, signal.bullish, entry, stop, target, quantity, timeMillis)
        open = p
        return p
    }

    /** Process only a supplied real OHLC candle. Stop wins if SL and TP2 touch in the same bar. */
    @Synchronized
    fun onCandle(candle: Candle): ClosedTrade? {
        val p = open ?: return null
        if (candle.time <= p.openedAt || !valid(candle)) return null
        val stopHit = if (p.bullish) candle.low <= p.stopLoss else candle.high >= p.stopLoss
        val targetHit = if (p.bullish) candle.high >= p.takeProfit else candle.low <= p.takeProfit
        if (!stopHit && !targetHit) return null
        val exit = if (stopHit) p.stopLoss else p.takeProfit
        val pnl = (if (p.bullish) exit - p.entry else p.entry - exit) * p.quantity
        val riskCash = kotlin.math.abs(p.entry - p.stopLoss) * p.quantity
        val closed = ClosedTrade(p, exit, candle.time, pnl, if (riskCash > 0.0) pnl / riskCash else 0.0,
            if (stopHit) "STOP_LOSS" else "TAKE_PROFIT_2")
        balance += pnl
        rollDay(candle.time)
        dailyPnl += pnl
        history += closed
        open = null
        return closed
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val unrealized = open?.let { p ->
            // Mark-to-market requires a real price update; without one, equity equals balance.
            0.0
        } ?: 0.0
        return Snapshot(balance, balance + unrealized, dailyPnl, open, history.toList(), blocked)
    }

    private fun reject(reason: String): Position? { blocked = reason; return null }
    private fun rollDay(timeMillis: Long) {
        val day = Instant.ofEpochMilli(timeMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
        if (dayKey != day) {
            dayKey = day
            dayStartBalance = balance
            dailyPnl = 0.0
        }
    }
    private fun valid(c: Candle): Boolean =
        c.time > 0L && c.open.isFinite() && c.high.isFinite() && c.low.isFinite() &&
            c.close.isFinite() && c.high >= maxOf(c.open, c.close) &&
            c.low <= minOf(c.open, c.close) && c.high >= c.low
}
