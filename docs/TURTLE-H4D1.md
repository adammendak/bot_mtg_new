# Turtle Breakout H4/D1 (Revelio) — indicator and 12-month test

`pine/turtle_breakout_h4d1.pine` — Turtle 20/10 counted in chart candles, **long and short** (switchable), for H4 and D1.
Works on any chart type: input *Price data* = `Real candles` (default, correct even on a Heikin Ashi chart) or
`Heikin Ashi` (bands, ATR and fills from HA candles; HA prices are not tradeable, so HA stats compare signal quality only).

Rules (completed candles only):

| | long | short |
|---|---|---|
| entry | high > highest high of the previous 20 candles, fill `max(band, open)` | low < lowest low of the previous 20, fill `min(band, open)` |
| stop | fill − 2 × ATR20 (previous candle), fixed | fill + 2 × ATR20, fixed |
| exit | low < max(stop, lowest low of the previous 10), fill `min(open, level)` | high > min(stop, highest high of the previous 10), fill `max(open, level)` |

One position at a time; a candle that closes a trade cannot open the next; if both bands break on one flat candle the
side whose band is closer to the open is taken. Stop is checked on the breakout candle (pessimistic). Alerts: intrabar
"band broken" (place the stop order at the band for a real fill) and candle-close entry/exit.

## Test: `tools/turtle_h4d1_bt.py`

Last 12 months (2025-11-23 → 2026-11-23), ten tickers (XAU, BTC, US100, GER40, EURUSD, XAG, J225, USDJPY, US500, US30),
Capital demo mids, **net of a rough round-trip spread** (same table as `tools/turtle_bt.py`), no swaps/financing, 1R = 2 ATR20.
Besides the per-ticker table: bootstrap 95% CI of avg net R, a random-entry baseline (same exits, random entry bars, 300 runs),
half-year splits, buy&hold, exposure, closed-trade drawdown, longer history by year.

### Pooled results, Real candles

| TF | side | n | avg net R | sum net R | PF | 95% CI avg | random-entry runs that beat it |
|---|---|---:|---:|---:|---:|---|---:|
| D1 | Long only | 75 | +0.17 | +13.0 | 1.44 | [−0.18, +0.60] | 6% |
| D1 | Short only | 78 | +0.10 | +7.5 | 1.17 | [−0.23, +0.45] | 57% |
| D1 | Long & Short | 142 | +0.21 | +29.4 | 1.47 | [−0.05, +0.51] | 9% |
| H4 | Long only | 433 | −0.05 | −20.4 | 0.91 | [−0.18, +0.11] | 79% |
| H4 | Short only | 434 | −0.19 | −84.3 | 0.67 | [−0.32, −0.06] | 100% |
| H4 | Long & Short | 833 | −0.11 | −89.3 | 0.80 | [−0.20, −0.00] | 99% |

Heikin Ashi gives about the same picture (D1 Long & Short +0.16 R, PF 1.40; H4 Long & Short −0.11 R, PF 0.78).

D1 Long & Short per ticker (avg net R, PF): XAG +1.21 (3.46), EURUSD +0.72 (7.89), BTC +0.75 (3.12), J225 +0.62 (3.99),
XAU +0.34 (2.17), US100 +0.02 (1.03), US30 −0.11 (0.75), US500 −0.11 (0.83), GER40 −0.18 (0.63), USDJPY −0.45 (0.28).

### Longer history (Real candles, pooled, net R)

* **D1 long only, 2019–2026:** n=619, avg +0.32 R, PF 1.81, positive in every calendar year (2021 +0.04, 2022 +0.06, 2026 +0.05 are the weak ones).
* **D1 short only, 2019–2026:** n=572, avg −0.22 R, PF 0.61; positive only in 2020, 2022 and 2026.
* **D1 long & short:** n=1149, avg +0.09 R, PF 1.19; negative in 2021.
* **H4 (2024-05 → now):** long only PF 1.09 but negative in both 2026 halves; short only PF 0.64; long & short PF 0.90.

### Reading it

* D1 is the only timeframe with an edge; the last 12 months are consistent with the multi-year long-only result, but the
  sample is small (≈75 long trades) and the CI on the combined run still touches zero.
* The short side adds nothing that a random short would not (57% of random runs beat it) and is a drag over 2019–2026;
  keep it as an option, default decisions should lean on D1 long.
* H4 is worse than random entries over the last year (99% of random runs beat it). Do not use it.
* Gold/silver/BTC/J225/EURUSD carry the D1 result; USDJPY, GER40, US500, US30 lose.
* Not modelled: swaps/financing on ~15-day CFD holds, real spread widening, TradingView's own D1/H4 candle boundaries
  (these tests use Capital's candle grid).

Run: `python tools/turtle_h4d1_bt.py --end 2026-11-23 --days 365` (needs the Capital demo credentials in `.env`).
