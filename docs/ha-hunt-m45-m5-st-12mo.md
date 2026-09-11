# HA-Hunt M45 / M5 + M45 Supertrend — ~12-month research backtest

Generated `2026-09-11T16:14:52.331023+00:00`. Simulator: `tools/ha_hunt_st_compare` matching `pine/ha_hunt_m45_m5_st.pine`.

**No Java / prod HTS changes.** One position at a time. Conservative same-bar (stop before TP1).

## Locked baseline

- Chart / entry TF: **M5**
- Structure: **M45** large RMA high-low band; gate **ON**
- Trigger: M5 small-band **CROSS** (first close beyond M5 fast band); `bandCrossStrict` **off**
- Bias + SL: closed **M45 Supertrend** (same line). Long only ST bull, short only ST bear.
- Slow RMA **144** (Adam), fast **33**. ST ATR **10**, factor **2.0**. `capReg` **2**.
- TP1 = 2 × |entry − SL|; runner trails M45 ST; full exit on M45 ST flip and/or M45 slow-band body.
- Closed HTF = last completed M45 bar, then Pine `f_*Closed()[1]` (no forming-bar leak).
- R = full-position exit / initial 1R (Pine does not scale out). `r_split` in JSON is HTS 50/50.

## Data coverage

Capital DEMO mid caches / API keys were **not** available in this agent environment. Prices are **real** HistData M1 OHLC resampled to M5 (XAU, US100=NSXUSD, GER40=GRXEUR, EURUSD) and Coinbase Exchange 5m for BTCUSD. That is **not** Capital mid; do not treat levels as fillable Capital quotes. Gaps are stated per symbol — no invented bars.

| symbol | source | first | last | n_m5 | days | note |
| --- | --- | --- | --- | --- | --- | --- |
| XAU | histdata_m1_resampled_m5 | 2025-09-11T00:00:00 | 2026-09-04T20:55:00 | 69685 | 358.9 | capital_unavailable: Capital DEMO credentials not set; HistData M1 (Eastern stam |
| US100 | histdata_m1_resampled_m5 | 2025-09-11T00:00:00 | 2026-09-04T20:10:00 | 67303 | 358.8 | capital_unavailable: Capital DEMO credentials not set; HistData M1 (Eastern stam |
| EURUSD | histdata_m1_resampled_m5 | 2025-09-11T00:00:00 | 2026-09-04T20:55:00 | 73364 | 358.9 | capital_unavailable: Capital DEMO credentials not set; HistData M1 (Eastern stam |
| GER40 | histdata_m1_resampled_m5 | 2025-09-11T00:00:00 | 2026-09-04T19:55:00 | 67493 | 358.8 | capital_unavailable: Capital DEMO credentials not set; HistData M1 (Eastern stam |
| BTCUSD | coinbase_exchange_m5 | 2025-09-11T00:00:00 | 2026-09-11T00:00:00 | 104972 | 365.0 | capital_unavailable: Capital DEMO credentials not set; Coinbase Exchange BTC-USD |

## Baseline (slow=144) per symbol + book

| name | n | WR% | sumR | avgR | maxDD(R) | PF | stop | st_flip | slow | eod | L/S | TP1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| XAU | 265 | 37.0 | 63.97 | 0.241 | 13.73 | 1.43 | 212 | 47 | 6 | 0 | 148/117 | 81 |
| BTCUSD | 413 | 33.4 | 45.58 | 0.110 | 40.54 | 1.19 | 327 | 76 | 9 | 1 | 199/214 | 120 |
| US100 | 291 | 34.4 | 39.09 | 0.134 | 21.01 | 1.22 | 238 | 44 | 9 | 0 | 156/135 | 85 |
| GER40 | 284 | 31.7 | 15.11 | 0.053 | 18.65 | 1.09 | 239 | 34 | 11 | 0 | 154/130 | 83 |
| EURUSD | 317 | 28.7 | -21.78 | -0.069 | 39.69 | 0.89 | 245 | 63 | 8 | 1 | 157/160 | 77 |
| baseline_144 | 1570 | 32.9 | 141.98 | 0.090 | 40.54 | 1.15 | 1261 | 264 | 43 | 2 | 814/756 | 446 |

Book row is the sum of independent one-position-per-name books (not a single portfolio slot).
maxDD on the BOOK row is the **worst single-name** baseline DD, not a combined equity DD.

Baseline long vs short sumR: XAU +44.6 / +19.4 · US100 +23.9 / +15.2 · GER40 +18.7 / −3.6 · BTC +0.8 / +44.8 · EURUSD longs and shorts both lose.

## Permutation bake-off (book totals)

| name | n | WR% | sumR | avgR | maxDD(R) | PF | stop | st_flip | slow | eod | L/S | TP1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| nogate | 2124 | 33.5 | 172.41 | 0.081 | 54.25 | 1.14 | 1566 | 403 | 151 | 4 | 1050/1074 | 588 |
| baseline_144 | 1570 | 32.9 | 141.98 | 0.090 | 40.54 | 1.15 | 1261 | 264 | 43 | 2 | 814/756 | 446 |
| slow100 | 1557 | 32.6 | 132.62 | 0.085 | 40.94 | 1.14 | 1269 | 258 | 28 | 2 | 810/747 | 444 |
| strict | 1453 | 35.4 | 83.38 | 0.057 | 33.19 | 1.11 | 896 | 528 | 27 | 2 | 743/710 | 329 |
| long_only | 814 | 33.2 | 80.49 | 0.099 | 28.16 | 1.17 | 646 | 155 | 13 | 0 | 814/0 | 222 |
| cap1_strict | 1257 | 36.0 | 78.72 | 0.063 | 29.33 | 1.13 | 741 | 491 | 23 | 2 | 647/610 | 279 |
| stop_band | 1391 | 32.9 | 76.91 | 0.055 | 39.31 | 1.11 | 536 | 812 | 41 | 2 | 722/669 | 281 |
| cap1 | 1265 | 32.6 | 68.08 | 0.054 | 49.17 | 1.09 | 1012 | 215 | 36 | 2 | 667/598 | 358 |
| stop_atr | 1294 | 35.5 | 64.40 | 0.050 | 27.32 | 1.11 | 357 | 921 | 13 | 3 | 676/618 | 245 |
| st3 | 1006 | 36.5 | 62.86 | 0.062 | 45.49 | 1.12 | 714 | 270 | 18 | 4 | 518/488 | 272 |

Variants (one-at-a-time from locked baseline, plus two cheap combos):

| variant | change |
| --- | --- |
| baseline_144 | locked stack (slow 144, loose cross, gate on, cap 2, ST stop, factor 2, both sides) |
| cap1 | capReg = 1 |
| strict | bandCrossStrict on |
| nogate | M45 structure gate off |
| slow100 | slow RMA 100 (A/B only) |
| stop_atr | SL = M45 ATR14 × 2.5 |
| stop_band | SL = M45 fast-band far edge ± 0.25×width |
| st3 | Supertrend factor 3.0 |
| long_only | longs only |
| cap1_strict | capReg 1 + strict band-clear |

## Per-ticker winner (by sumR)

| symbol | best variant | n | sumR | PF | WR% |
| --- | --- | --- | --- | --- | --- |
| XAU | nogate | 370 | 92.19 | 1.46 | 38.4 |
| US100 | nogate | 403 | 47.01 | 1.20 | 35.7 |
| EURUSD | st3 | 199 | 6.64 | 1.06 | 35.7 |
| GER40 | st3 | 180 | 30.57 | 1.34 | 42.8 |
| BTCUSD | nogate | 541 | 48.14 | 1.15 | 32.5 |

## Recommendation

**Trade the locked stack (slow=144, gate ON, cap 2, M45 ST stop, factor 2.0, loose M5 cross) on XAU first, then US100.** BTC is positive on the book but almost all of that R is **shorts** (longs ≈ flat) and DD is ~40R. GER40 is thin. **Skip EURUSD** on this stack (negative sumR, PF 0.89).

| rank | what | why |
| --- | --- | --- |
| 1 | **XAU** baseline | +64R / 12mo, PF 1.43, DD 13.7R — best name |
| 2 | **US100** baseline | +39R, PF 1.22, DD 21R |
| 3 | **BTCUSD** baseline | +46R, PF 1.19, but DD 40R; shorts carry the book |
| — | GER40 | +15R, PF 1.09 — optional |
| — | EURUSD | **−22R**, PF 0.89 — off |

Permutation notes (keep **slow=144**):

- **`slow100` is a wash** (−9R vs 144 on the book). Adam’s 144 lock does not cost edge.
- **`capReg=2` beats 1** (+142R vs +68R). Do not tighten to 1 on the locked stack.
- **M45 ST stop is the right default.** ATR×2.5 and band-edge both cut book sumR in half.
- **`nogate` wins raw sumR** (+172 vs +142) by taking more trades. It also fattens BTC/EUR DD. Use only if Adam wants a busier book; the locked design keeps the M45 structure gate **ON**.
- **`st3`** is the only cell that makes EURUSD slightly green and is the GER40 winner — it **hurts BTC** (−22R). Not a book winner.
- **Both sides > long-only** on book sumR (+142 vs +80). XAU/US100 longs are the quality; BTC needs shorts; GER40 shorts are slightly negative.

Practical call for Adam: **Pine defaults = locked baseline, universe = XAU + US100 (+ BTC shorts if DD is acceptable). Leave EURUSD off. Leave slow at 144.**

## ST TF × TP1 matrix (A/B/C/D)

Generated `2026-09-11T16:24:47.117251+00:00`. Same 12m data, slow **144**, M5 band-cross, M45 structure gate **ON**, cap 2, both sides. Locked baseline **A did not half-close** at TP1.

| | full trail after TP1 touch (no forced BE) | 50% off at 1:2, runner → BE, then trail in favor |
| --- | --- | --- |
| **M45 ST** bias+SL | **A** `baseline_144` (locked) | **B** `m45st_partial` |
| **H1 ST** bias+SL | **D** `h1st_full` | **C** `h1st_partial` |

Half-TP1 R: stop before TP1 = −1R; after TP1 = **+1.0 + 0.5 × runner_R** (BE runner = +1.0R). Same-bar stop beats TP1 (no scale / no BE that bar).
B/C: after the 50% fill, remaining stop jumps to **entry**, then M45/H1 ST may only tighten. A/D unchanged — no forced BE.

### One table — book

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 1570 | 32.9 | 141.98 | 0.090 | 40.54 | 1.15 |
| B M45 ST + 50% TP1 + BE | 1577 | 34.7 | 61.99 | 0.039 | 42.70 | 1.07 |
| C H1 ST + 50% TP1 + BE | 1284 | 35.9 | 52.62 | 0.041 | 36.06 | 1.07 |
| D H1 ST + full trail | 1278 | 34.1 | 65.77 | 0.051 | 46.83 | 1.09 |

### One table — per symbol sumR / WR% / maxDD

| symbol | A sumR | B sumR | C sumR | D sumR | A WR | B WR | C WR | D WR | A DD | B DD | C DD | D DD | winner |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| XAU | 63.97 | 40.61 | 30.74 | 40.70 | 37.0 | 38.3 | 36.7 | 34.1 | 13.7 | 13.0 | 10.3 | 10.6 | A M45 ST + full trail |
| US100 | 39.09 | 21.98 | 18.44 | 30.85 | 34.4 | 36.3 | 35.4 | 34.6 | 21.0 | 23.8 | 16.6 | 16.4 | A M45 ST + full trail |
| EURUSD | -21.78 | -30.63 | -10.79 | -9.44 | 28.7 | 29.9 | 32.3 | 30.4 | 39.7 | 42.7 | 34.2 | 39.5 | D H1 ST + full trail |
| GER40 | 15.11 | 3.82 | 17.18 | 5.92 | 31.7 | 32.5 | 42.1 | 39.9 | 18.6 | 19.8 | 11.7 | 16.6 | C H1 ST + 50% TP1 + BE |
| BTCUSD | 45.58 | 26.20 | -2.95 | -2.26 | 33.4 | 36.4 | 34.0 | 32.3 | 40.5 | 36.6 | 36.1 | 46.8 | A M45 ST + full trail |
| BOOK | 141.98 | 61.99 | 52.62 | 65.77 | 32.9 | 34.7 | 35.9 | 34.1 | 40.5 | 42.7 | 36.1 | 46.8 | A M45 ST + full trail |

### Detail (n / WR / sumR / avgR / DD / PF)

#### XAU

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 265 | 37.0 | 63.97 | 0.241 | 13.73 | 1.43 |
| B M45 ST + 50% TP1 + BE | 266 | 38.3 | 40.61 | 0.153 | 12.98 | 1.27 |
| C H1 ST + 50% TP1 + BE | 218 | 36.7 | 30.74 | 0.141 | 10.26 | 1.26 |
| D H1 ST + full trail | 217 | 34.1 | 40.70 | 0.188 | 10.61 | 1.35 |

#### US100

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 291 | 34.4 | 39.09 | 0.134 | 21.01 | 1.22 |
| B M45 ST + 50% TP1 + BE | 292 | 36.3 | 21.98 | 0.075 | 23.75 | 1.13 |
| C H1 ST + 50% TP1 + BE | 254 | 35.4 | 18.44 | 0.073 | 16.64 | 1.13 |
| D H1 ST + full trail | 254 | 34.6 | 30.85 | 0.121 | 16.39 | 1.21 |

#### EURUSD

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 317 | 28.7 | -21.78 | -0.069 | 39.69 | 0.89 |
| B M45 ST + 50% TP1 + BE | 318 | 29.9 | -30.63 | -0.096 | 42.70 | 0.85 |
| C H1 ST + 50% TP1 + BE | 254 | 32.3 | -10.79 | -0.042 | 34.20 | 0.93 |
| D H1 ST + full trail | 253 | 30.4 | -9.44 | -0.037 | 39.49 | 0.94 |

#### GER40

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 284 | 31.7 | 15.11 | 0.053 | 18.65 | 1.09 |
| B M45 ST + 50% TP1 + BE | 286 | 32.5 | 3.82 | 0.013 | 19.76 | 1.02 |
| C H1 ST + 50% TP1 + BE | 240 | 42.1 | 17.18 | 0.072 | 11.71 | 1.13 |
| D H1 ST + full trail | 238 | 39.9 | 5.92 | 0.025 | 16.60 | 1.05 |

#### BTCUSD

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 413 | 33.4 | 45.58 | 0.110 | 40.54 | 1.19 |
| B M45 ST + 50% TP1 + BE | 415 | 36.4 | 26.20 | 0.063 | 36.63 | 1.11 |
| C H1 ST + 50% TP1 + BE | 318 | 34.0 | -2.95 | -0.009 | 36.06 | 0.98 |
| D H1 ST + full trail | 316 | 32.3 | -2.26 | -0.007 | 46.83 | 0.99 |

### Call

**A still wins** the book, XAU, US100, and BTC. Forcing BE on the runner after half-TP1 made B/C **worse** than half-TP1 without BE (B +62 vs prior +74; C +53 vs +56) — it cuts runner give-back but also cuts the runners that were paying.

| | A M45 full | B M45 50%+BE | C H1 50%+BE | D H1 full |
| --- | ---: | ---: | ---: | ---: |
| Book sumR | **+142** | +62 | +53 | +66 |
| XAU | **+64** | +41 | +31 | +41 |
| US100 | **+39** | +22 | +18 | +31 |

Do not add half-TP1+BE to the locked stack. Do not switch ST to H1. GER40 is the only name that still prefers C.

## M5 HA-flip runner (A vs B)

Generated `2026-09-11T16:35:04.073997+00:00`. Same 12m data, slow **144**, M5 band-cross, M45 structure gate **ON**, cap 2, both sides unless a WR perm says otherwise.

**Locked runner (this retest):** 50% at 1:2 → remaining stop to **entry (BE)** → full exit on confirmed **M5 HA colour flip** against the position (body close), or BE/stop. ST is bias + initial SL only. No M45 slow-band, no ST-line trail, ST-flip **OFF**.

| cell | ST TF | exit |
| --- | --- | --- |
| **A** `ha_m45` | M45 | half + BE + M5 HA flip |
| **B** `ha_h1` | H1 | half + BE + M5 HA flip |
| **C** `baseline_144` | M45 | **OLD** full-trail (reference only — not this lock) |

Win = total R > 0 (half at +2R books +1R; BE runner = 0 → trade ≈ +1R win). Loss = stopped before TP1 (−1R) or net R ≤ 0. Same-bar stop beats TP1.

### Leaderboard — book

| cell | n | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + half TP1 + BE + M5 HA flip | 1608 | 34.3 | 33.9 | 34.3 | 39.02 | 0.024 | 29.72 | 1.04 |
| B H1 ST + half TP1 + BE + M5 HA flip | 1262 | 34.4 | 34.0 | 34.2 | 21.81 | 0.017 | 33.29 | 1.03 |
| C OLD M45 ST full-trail (no half, ST trail / ST flip / slow-band) | 1570 | 32.9 | 32.9 | 28.4 | 141.98 | 0.090 | 40.54 | 1.15 |

### Per symbol — sumR / WR (A vs B; C = OLD exit)

| symbol | A sumR | B sumR | C OLD sumR | A WR | B WR | C OLD WR | HA-exit winner |
| --- | --- | --- | --- | --- | --- | --- | --- |
| XAU | 31.00 | 38.33 | 63.97 | 37.7 | 39.4 | 37.0 | B H1 ST + half TP1 + BE + M5 HA flip |
| US100 | 13.05 | 9.30 | 39.09 | 35.0 | 35.4 | 34.4 | A M45 ST + half TP1 + BE + M5 HA flip |
| EURUSD | -20.18 | -4.59 | -21.78 | 31.8 | 33.3 | 28.7 | B H1 ST + half TP1 + BE + M5 HA flip |
| GER40 | -12.07 | -4.33 | 15.11 | 31.6 | 33.0 | 31.7 | B H1 ST + half TP1 + BE + M5 HA flip |
| BTCUSD | 27.23 | -16.90 | 45.58 | 35.5 | 31.6 | 33.4 | A M45 ST + half TP1 + BE + M5 HA flip |
| BOOK | 39.02 | 21.81 | 141.98 | 34.3 | 34.4 | 32.9 | A M45 ST + half TP1 + BE + M5 HA flip |

### Detail

#### XAU

| cell | n | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + half TP1 + BE + M5 HA flip | 273 | 37.7 | 37.0 | 37.7 | 31.00 | 0.114 | 13.00 | 1.18 |
| B H1 ST + half TP1 + BE + M5 HA flip | 231 | 39.4 | 39.0 | 39.4 | 38.33 | 0.166 | 12.12 | 1.28 |
| C OLD M45 ST full-trail (no half, ST trail / ST flip / slow-band) | 265 | 37.0 | 37.0 | 30.6 | 63.97 | 0.241 | 13.73 | 1.43 |

Exits A: `{'stop': 170, 'm5_ha_flip': 101, 'be': 2}` · B: `{'stop': 139, 'm5_ha_flip': 90, 'be': 1, 'open_eod': 1}`. TP1 fills A 103/273 · B 91/231.

#### US100

| cell | n | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + half TP1 + BE + M5 HA flip | 303 | 35.0 | 34.7 | 35.0 | 13.05 | 0.043 | 28.90 | 1.07 |
| B H1 ST + half TP1 + BE + M5 HA flip | 260 | 35.4 | 35.0 | 35.0 | 9.30 | 0.036 | 22.07 | 1.06 |
| C OLD M45 ST full-trail (no half, ST trail / ST flip / slow-band) | 291 | 34.4 | 34.4 | 29.2 | 39.09 | 0.134 | 21.01 | 1.22 |

Exits A: `{'m5_ha_flip': 105, 'stop': 197, 'be': 1}` · B: `{'m5_ha_flip': 90, 'stop': 168, 'be': 1, 'open_eod': 1}`. TP1 fills A 106/303 · B 91/260.

#### EURUSD

| cell | n | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + half TP1 + BE + M5 HA flip | 308 | 31.8 | 31.2 | 31.8 | -20.18 | -0.066 | 27.96 | 0.90 |
| B H1 ST + half TP1 + BE + M5 HA flip | 237 | 33.3 | 32.5 | 33.3 | -4.59 | -0.019 | 33.29 | 0.97 |
| C OLD M45 ST full-trail (no half, ST trail / ST flip / slow-band) | 317 | 28.7 | 28.7 | 24.3 | -21.78 | -0.069 | 39.69 | 0.89 |

Exits A: `{'stop': 209, 'm5_ha_flip': 96, 'be': 2, 'open_eod': 1}` · B: `{'stop': 157, 'm5_ha_flip': 77, 'be': 2, 'open_eod': 1}`. TP1 fills A 98/308 · B 79/237.

#### GER40

| cell | n | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + half TP1 + BE + M5 HA flip | 307 | 31.6 | 31.3 | 31.6 | -12.07 | -0.039 | 29.72 | 0.94 |
| B H1 ST + half TP1 + BE + M5 HA flip | 230 | 33.0 | 33.0 | 32.6 | -4.33 | -0.019 | 26.88 | 0.97 |
| C OLD M45 ST full-trail (no half, ST trail / ST flip / slow-band) | 284 | 31.7 | 31.7 | 29.2 | 15.11 | 0.053 | 18.65 | 1.09 |

Exits A: `{'stop': 210, 'm5_ha_flip': 96, 'be': 1}` · B: `{'stop': 154, 'm5_ha_flip': 75, 'open_eod': 1}`. TP1 fills A 97/307 · B 75/230.

#### BTCUSD

| cell | n | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + half TP1 + BE + M5 HA flip | 417 | 35.5 | 35.3 | 35.5 | 27.23 | 0.065 | 25.41 | 1.10 |
| B H1 ST + half TP1 + BE + M5 HA flip | 304 | 31.6 | 31.2 | 31.2 | -16.90 | -0.056 | 25.02 | 0.92 |
| C OLD M45 ST full-trail (no half, ST trail / ST flip / slow-band) | 413 | 33.4 | 33.4 | 29.1 | 45.58 | 0.110 | 40.54 | 1.19 |

Exits A: `{'stop': 269, 'm5_ha_flip': 147, 'be': 1}` · B: `{'stop': 208, 'm5_ha_flip': 94, 'be': 1, 'open_eod': 1}`. TP1 fills A 148/417 · B 95/304.

### Call — ~50% WR vs old ~33%

Adam’s score: a trade is a **win iff scaled total R > 0** (50% @ 2R = +1R; BE runner = 0 → ≈ +1R win). Loss = stopped before TP1 (−1R) or net R ≤ 0.

| book | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD | PF |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| OLD full-trail (C) | **32.9** | 32.9 | 28.4 | **+142** | 0.09 | 40.5 | 1.15 |
| A M45 half+BE+HA | **34.3** | 33.9 | 34.3 | +39 | 0.02 | 29.7 | 1.04 |
| B H1 half+BE+HA | **34.4** | 34.0 | 34.2 | +22 | 0.02 | 33.3 | 1.03 |
| Best WR perm (`strict+cap1+st3` M45) | **35.9** | 35.8 | 35.6 | +44 | 0.08 | 22.1 | 1.12 |
| **Target** | **~50** | | | | | | |

**No cell hits ~50% WR.** Half+BE+HA lifts book WR by only **+1.4pp** vs the old 32.9% full-trail book. Full-size WR is almost the same as scaled WR (BE is rare). WR **equals TP1%** — after TP1 the trade is almost always R>0, so 50% WR means 50% of entries must tag 1:2 before the initial ST stop. Entry filters (strict / cap1 / st3 / long-only) do not get there.

Closest single-name WR: H1 `strict+cap1` XAU **42.2%** / +33R (US100 only 36.0% / +7R). XAU+US100 long-only H1: **37.8%** / +31R. Still ~12pp short.

### WR permutations (slow 144, same HA-exit lock)

Adam target **~50% WR** (win = scaled total R > 0). OLD full-trail book was **32.9%**. One-at-a-time plus a few stricter combos. `longonly` is all names; XAU+US100 long-only is those two books only.

| cell | n | WR% (R>0) | WR% full-size | TP1% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ha_m45_strict_cap1_st3 | 576 | 35.9 | 35.8 | 35.6 | 43.91 | 0.076 | 22.08 | 1.12 |
| ha_h1_cap1 | 931 | 35.0 | 34.8 | 34.8 | 35.22 | 0.038 | 40.90 | 1.06 |
| ha_m45_st3 | 902 | 34.7 | 34.5 | 34.5 | 32.70 | 0.036 | 30.80 | 1.06 |
| ha_h1_longonly | 683 | 35.0 | 34.7 | 34.7 | 25.46 | 0.037 | 35.89 | 1.06 |
| ha_m45_strict_st3 | 690 | 34.6 | 34.3 | 34.3 | 24.63 | 0.036 | 22.08 | 1.05 |
| ha_h1_strict_cap1 | 688 | 34.6 | 34.3 | 34.2 | 15.73 | 0.023 | 23.89 | 1.04 |
| ha_m45_cap1 | 1188 | 33.8 | 33.3 | 33.8 | 14.14 | 0.012 | 42.09 | 1.02 |
| ha_h1_strict_cap1_st3 | 471 | 34.4 | 34.4 | 33.8 | 11.86 | 0.025 | 23.25 | 1.04 |
| ha_h1_strict_st3 | 583 | 34.1 | 34.1 | 33.6 | 11.45 | 0.020 | 22.28 | 1.03 |
| ha_h1_strict | 892 | 34.3 | 33.9 | 34.0 | 10.16 | 0.011 | 27.20 | 1.02 |
| ha_m45_strict_cap1 | 867 | 33.9 | 33.4 | 33.8 | 8.25 | 0.010 | 26.33 | 1.01 |
| ha_m45_strict | 1092 | 33.9 | 33.4 | 33.8 | 4.53 | 0.004 | 32.97 | 1.01 |
| ha_h1_st3 | 718 | 33.4 | 33.4 | 33.0 | 1.03 | 0.001 | 23.21 | 1.00 |
| ha_m45_longonly | 855 | 32.7 | 32.3 | 32.7 | -16.08 | -0.019 | 30.07 | 0.97 |

| perm | book WR% | book sumR | XAU sumR | US100 sumR | XAU WR | US100 WR |
| --- | --- | --- | --- | --- | --- | --- |
| ha_h1_strict | 34.3 | 10.2 | 31.6 | 2.6 | 40.4 | 34.8 |
| ha_h1_cap1 | 35.0 | 35.2 | 37.6 | 15.5 | 41.1 | 37.2 |
| ha_h1_st3 | 33.4 | 1.0 | 5.6 | -13.5 | 34.3 | 30.2 |
| ha_h1_longonly | 35.0 | 25.5 | 18.5 | 12.9 | 38.2 | 37.4 |
| ha_h1_strict_cap1 | 34.6 | 15.7 | 32.9 | 7.0 | 42.2 | 36.0 |
| ha_h1_strict_st3 | 34.1 | 11.5 | 7.6 | -14.9 | 35.4 | 29.2 |
| ha_h1_strict_cap1_st3 | 34.4 | 11.9 | 17.5 | -10.9 | 40.3 | 29.4 |
| ha_m45_strict | 33.9 | 4.5 | 31.1 | 12.5 | 38.7 | 35.9 |
| ha_m45_cap1 | 33.8 | 14.1 | 28.0 | -6.4 | 39.0 | 32.6 |
| ha_m45_st3 | 34.7 | 32.7 | 5.1 | 9.0 | 34.1 | 35.5 |
| ha_m45_longonly | 32.7 | -16.1 | 26.5 | -2.0 | 39.1 | 33.1 |
| ha_m45_strict_cap1 | 33.9 | 8.3 | 20.0 | 8.8 | 37.9 | 35.5 |
| ha_m45_strict_st3 | 34.6 | 24.6 | 15.5 | -11.0 | 36.8 | 30.6 |
| ha_m45_strict_cap1_st3 | 35.9 | 43.9 | 19.1 | 8.2 | 39.2 | 36.2 |

**No combo reached ~50% WR.** Highest book WR: **ha_m45_strict_cap1_st3** 35.9% / sumR=+43.9 — still ~14pp short of the target and only +3.0pp vs the old 32.9% full-trail book.
XAU+US100 long-only **M45**: n=322 WR=36.0% sumR=+24.5 (XAU 39.1% / +26.5, US100 33.1% / -2.0).
XAU+US100 long-only **H1**: n=270 WR=37.8% sumR=+31.3 (XAU 38.2% / +18.5, US100 37.4% / +12.9).

## How to rerun

```bash
python3 -m tools.ha_hunt_st_compare.run_bakeoff
python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup
python3 -m tools.ha_hunt_st_compare.run_bakeoff --ha-exit
```

With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5.
Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).
