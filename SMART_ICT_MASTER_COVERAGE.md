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


## Requested master ICT checklist (catalog scope)

The app's `SmartIctSuite.Tag` taxonomy is the machine-readable checklist. The following families must remain covered during implementation and follow-up verification:

- [ ] Market structure: swing highs/lows, HH/HL/LH/LL, short/intermediate/long-term highs/lows, internal/external structure, BOS, CHoCH, MSS, protected levels, inducement, CISD and displacement.
- [ ] Liquidity: BSL/SSL, EQH/EQL, relative equal highs/lows, sweeps, IRL/ERL, draw-on-liquidity, liquidity void, Turtle Soup and Judas Swing.
- [ ] Imbalance: bullish/bearish FVG, partial/full fill, invalidation, IFVG, BPR, CE 50%, volume imbalance, opening gap, NDOG/NWOG.
- [ ] Blocks/zones: bullish/bearish order blocks and breakers, mitigation, rejection, propulsion and vacuum blocks.
- [ ] Range/Fibonacci: dealing range, premium/discount/equilibrium, OTE, 62/70.5/79 references, price-delivery range, range expansion and IPDA 20/40/60 ranges.
- [ ] Time/session: Asia range, London/NY kill zones, NY PM, London close, session open/high/low, opening range, macro windows and midnight/daily/weekly opens.
- [ ] Reference levels: PDH/PDL, PWH/PWL and session highs/lows.
- [ ] Bias/MTF: daily/weekly/monthly bias, HTF bias, MTF confluence/conflict, synchronized SMT comparison.
- [ ] Models: Silver Bullet, ICT 2022-style setup, Unicorn, Power of Three, AMD, MMXM, FVG/OB, breaker/FVG and OTE entry models.
- [ ] Trade planning: buy/sell setup, entry, stop loss, TP1/TP2/TP3, risk/reward, NO_TRADE and invalidation.
- [ ] Safety/data gates: news, spread, stale/missing data, insufficient history, session state and conflicting-signal warnings.

### Status semantics

- A tag existing in the enum is **catalogued**, not proof of working detection.
- A heuristic mark is a **candidate**, not a guaranteed or canonical ICT interpretation.
- Provider-dependent concepts stay pending until real inputs are connected and verified; never substitute fabricated candles, fake news, assumed spread, or unsynchronized markets.
- Chart visibility must be context-sensitive; the full taxonomy should not mean every overlay is drawn at once.
- Verification is still required on-device for drawing alignment, pan/zoom, symbol/timeframe changes, performance and the live candle/countdown baseline.
