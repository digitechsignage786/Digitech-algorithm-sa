# Smart ICT master coverage

This branch is a test integration, separate from `main`. The analyzer only examines caller-supplied OHLC bars and never manufactures candles or sends live orders.

## Rule-engine coverage

- [x] Market structure: confirmed swing points, HH/HL/LH/LL, short/intermediate/long-term pivot candidates, internal/external labels, BOS, CHoCH, MSS and protected levels.
- [x] Liquidity: BSL/SSL candidates, EQH/EQL and relative equal levels, wick/close sweeps, IRL/ERL and draw-on-liquidity candidates.
- [x] Imbalance/displacement: FVG, IFVG, BPR, CE 50%, displacement, volume-imbalance proxy, observed opening gaps, partial/full fill and invalidation status.
- [x] Zones: bullish/bearish OB, breaker candidates, mitigation/retest, rejection, propulsion and vacuum-block candidates with invalidation notes.
- [x] Range/Fibonacci: dealing/price-delivery ranges, premium/discount/equilibrium, OTE and directional OTE candidates, 62/70.5/79 references and range-expansion context.
- [x] Time/market structure context: observed Asia/London/NY session range and open/high/low candidates, kill-zone windows, PDH/PDL/PWH/PWL, daily/weekly/midnight opens (only when data contains the actual timestamp), macro-window context and period-bias proxies.
- [x] Setup candidates: liquidity raid + MSS/FVG, Silver Bullet, Judas Swing, Turtle Soup, Unicorn OB/FVG overlap, ICT 2022-style model, AMD/Power-of-Three, MMXM, OTE, FVG/OB and breaker/FVG confluence.
- [x] Multi-timeframe framework: aggregate *upward only* from candles actually present in the chart; return directional proxy/confluence/conflict context.
- [x] SMT framework: comparisons require synchronized related-market OHLC passed explicitly to the engine.
- [x] Smart signal result: analytical entry/SL/TP1/TP2/TP3, R:R, confluence and rationale; stale-data, ATR-volatility, stop-distance, daily-loss, news and spread gates; NO_TRADE notes.
- [x] Prefix-replay backtest framework with trade counts, win/loss, win rate, net R, profit factor, max drawdown and expectancy; bounded to 300 bars maximum for mobile responsiveness.
- [x] Unit tests for real-OHLC output, missing-data/no-synthetic-candle behavior, external-input capabilities, master concept-tag coverage, insufficient backtest history and disabled real-money execution.

## External/live dependencies not yet connected or verified

- [ ] News-calendar provider, currency-impact mapping and verified calendar-completeness state. An empty list does **not** mean there is no news.
- [ ] Actual bid/ask spread feed and broker-specific spread/commission/slippage assumptions.
- [ ] Correlated instruments (for example, suitable FX pairs/metals) with synchronized timestamps for SMT. Current chart gives the analyzer its selected instrument only.
- [ ] Independent lower-timeframe candles for LTF entry models when chart timeframe is higher. The engine deliberately does not fabricate lower-timeframe candles.
- [ ] Broker/MT5 adapter, credentials management, order acknowledgements/rejections, protective-order validation and account selection. Real-money execution is deliberately disabled.
- [ ] On-device visual validation of all overlays across pan/zoom/rotation, symbol changes and live reconnection.
- [ ] Full, statistically meaningful backtesting using a representative historical dataset plus spread, commission, slippage, session and provider-data assumptions. Current tests are software tests, not proof of strategy profitability.

## Interpretation

The checked rule-engine items mean a deterministic heuristic/rule path and a mark type exist; they do **not** mean each ICT concept is a unique universally agreed textbook algorithm, that all required provider inputs are currently wired into the app, or that a setup has trading edge. Treat these marks as analytical candidates until verified against market history and on-device rendering. The live-order path is disabled.
