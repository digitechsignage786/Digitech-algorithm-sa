# Smart ICT master coverage — verified status

This is a test-branch audit, not a release certification. The analyzer must use supplied real OHLC and must never manufacture candles or place live orders.

## Implemented rule paths (unit-tested where noted)

- [x] Confirmed swing highs/lows with right-side confirmation; HH/HL/LH/LL classification.
- [x] BOS/CHoCH close-through logic after pivot confirmation.
- [x] Liquidity sweep candidates requiring wick-through and close-back; EQH/EQL candidates.
- [x] FVG detection, partial/full fill tracking and close-based invalidation/inversion.
- [x] Dealing range, equilibrium/50%, premium/discount and OTE range calculations.
- [x] Selected order-block, breaker and mitigation/retest heuristics.
- [x] Input validation, duplicate timestamp filtering, stale/missing-feed and spread/news fail-closed capability gates.
- [x] Analytical signal fields and risk filters are represented in the suite; real-money execution remains disabled.
- [x] Prefix-replay backtest framework and summary metrics exist; this is not yet a validated production backtester.
- [x] Automatic overlay relevance filtering is retained; renderer currently caps visible active marks.

## Partial / heuristic — do not treat as fully verified ICT implementation

- [ ] Short/intermediate/long-term swing hierarchy and protected/internal/external structure need broader edge-case and multi-timeframe validation.
- [ ] IRL/ERL, draw-on-liquidity, inducement, CISD, CRT and IPDA ranges need independent rule fixtures and visual review.
- [ ] IFVG/BPR, volume imbalance, opening gaps/NDOG/NWOG, liquidity void and displacement variants need expanded validation across both directions and session boundaries.
- [ ] Rejection/propulsion/vacuum blocks, OTE directional models, range expansion and price-delivery context need additional false-positive and invalidation tests.
- [ ] Session windows, kill zones, macro windows, daily/weekly/monthly bias and period levels need timezone, DST, missing-session and holiday tests.
- [ ] Silver Bullet, Judas Swing, Turtle Soup, Unicorn, ICT 2022 model, PO3/AMD and MMXM are heuristic setup candidates, not certified textbook implementations.
- [ ] Entry/stop/target and BUY/SELL setup candidates require broader historical validation with realistic fills and costs.

## External dependencies not connected or not verified

- [ ] Android Test Branch now routes historical HTTP and live WebSocket requests through the Cloudflare Worker; live verification remains pending until the owner deploys the Worker, sets `TWELVE_DATA_API_KEY` as a Cloudflare secret, and adds the Worker HTTPS origin as the GitHub Actions `MARKET_DATA_WORKER_URL` secret.
- [ ] Complete news-calendar provider, currency-impact mapping and trustworthy calendar-completeness signal.
- [ ] Actual bid/ask spread feed and broker-specific commission/slippage assumptions.
- [ ] Synchronized independent related-market OHLC for SMT.
- [ ] Independent provider-supplied higher-timeframe OHLC for true MTF confluence; chart-candle aggregation is only a proxy.
- [ ] Independent lower-timeframe OHLC for lower-timeframe entry models.
- [ ] Broker/MT5 adapter, secure credential handling, order acknowledgements/rejections, protective-order checks and account selection. Real-money execution stays disabled.
- [ ] On-device visual regression for overlays during pan, zoom, rotation, symbol change, reconnect and live candle updates.
- [ ] Representative historical backtest validation including spread, commission, slippage, sessions and provider-data quality.

## Release interpretation

A tag in an enum, a candidate mark, or a passing unit test does not prove that every named concept is fully implemented, correctly rendered, profitable, or ready for live trading. Treat unverified marks as analytical candidates. No release or merge should be declared complete until the applicable data, test and device gates pass. The locked Main App remains separate and unchanged.
