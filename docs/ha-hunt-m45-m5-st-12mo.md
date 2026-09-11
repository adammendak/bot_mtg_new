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

Generated `2026-09-11T16:22:47.838490+00:00`. Same 12m data, slow **144**, M5 band-cross, M45 structure gate **ON**, cap 2, both sides. Locked baseline **A did not half-close** at TP1.

| | full trail after TP1 touch | 50% off at 1:2, rest trails ST |
| --- | --- | --- |
| **M45 ST** bias+SL | **A** `baseline_144` (locked) | **B** `m45st_partial` |
| **H1 ST** bias+SL | **D** `h1st_full` | **C** `h1st_partial` |

Half-TP1 R: stop before TP1 = −1R; after TP1 = **+1.0 + 0.5 × runner_R**. Same-bar stop beats TP1.

### One table — book

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 1570 | 32.9 | 141.98 | 0.090 | 40.54 | 1.15 |
| B M45 ST + 50% TP1 | 1570 | 34.8 | 74.22 | 0.047 | 40.76 | 1.08 |
| C H1 ST + 50% TP1 | 1278 | 36.0 | 55.52 | 0.043 | 35.96 | 1.08 |
| D H1 ST + full trail | 1278 | 34.1 | 65.77 | 0.051 | 46.83 | 1.09 |

### One table — per symbol sumR / WR% / maxDD

| symbol | A sumR | B sumR | C sumR | D sumR | A WR | B WR | C WR | D WR | A DD | B DD | C DD | D DD | winner |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| XAU | 63.97 | 42.43 | 32.53 | 40.70 | 37.0 | 38.1 | 36.9 | 34.1 | 13.7 | 13.0 | 10.3 | 10.6 | A M45 ST + full trail |
| US100 | 39.09 | 22.06 | 18.44 | 30.85 | 34.4 | 36.4 | 35.4 | 34.6 | 21.0 | 23.3 | 16.6 | 16.4 | A M45 ST + full trail |
| EURUSD | -21.78 | -28.69 | -9.42 | -9.44 | 28.7 | 30.0 | 32.4 | 30.4 | 39.7 | 40.8 | 34.0 | 39.5 | C H1 ST + 50% TP1 |
| GER40 | 15.11 | 6.10 | 17.40 | 5.92 | 31.7 | 32.7 | 42.4 | 39.9 | 18.6 | 19.8 | 11.7 | 16.6 | C H1 ST + 50% TP1 |
| BTCUSD | 45.58 | 32.33 | -3.43 | -2.26 | 33.4 | 36.6 | 33.9 | 32.3 | 40.5 | 37.0 | 36.0 | 46.8 | A M45 ST + full trail |
| BOOK | 141.98 | 74.22 | 55.52 | 65.77 | 32.9 | 34.8 | 36.0 | 34.1 | 40.5 | 40.8 | 36.0 | 46.8 | A M45 ST + full trail |

### Detail (n / WR / sumR / avgR / DD / PF)

#### XAU

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 265 | 37.0 | 63.97 | 0.241 | 13.73 | 1.43 |
| B M45 ST + 50% TP1 | 265 | 38.1 | 42.43 | 0.160 | 12.98 | 1.29 |
| C H1 ST + 50% TP1 | 217 | 36.9 | 32.53 | 0.150 | 10.26 | 1.28 |
| D H1 ST + full trail | 217 | 34.1 | 40.70 | 0.188 | 10.61 | 1.35 |

#### US100

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 291 | 34.4 | 39.09 | 0.134 | 21.01 | 1.22 |
| B M45 ST + 50% TP1 | 291 | 36.4 | 22.06 | 0.076 | 23.29 | 1.13 |
| C H1 ST + 50% TP1 | 254 | 35.4 | 18.44 | 0.073 | 16.64 | 1.13 |
| D H1 ST + full trail | 254 | 34.6 | 30.85 | 0.121 | 16.39 | 1.21 |

#### EURUSD

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 317 | 28.7 | -21.78 | -0.069 | 39.69 | 0.89 |
| B M45 ST + 50% TP1 | 317 | 30.0 | -28.69 | -0.091 | 40.76 | 0.85 |
| C H1 ST + 50% TP1 | 253 | 32.4 | -9.42 | -0.037 | 34.03 | 0.94 |
| D H1 ST + full trail | 253 | 30.4 | -9.44 | -0.037 | 39.49 | 0.94 |

#### GER40

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 284 | 31.7 | 15.11 | 0.053 | 18.65 | 1.09 |
| B M45 ST + 50% TP1 | 284 | 32.7 | 6.10 | 0.021 | 19.76 | 1.04 |
| C H1 ST + 50% TP1 | 238 | 42.4 | 17.40 | 0.073 | 11.71 | 1.14 |
| D H1 ST + full trail | 238 | 39.9 | 5.92 | 0.025 | 16.60 | 1.05 |

#### BTCUSD

| cell | n | WR% | sumR | avgR | maxDD(R) | PF |
| --- | --- | --- | --- | --- | --- | --- |
| A M45 ST + full trail | 413 | 33.4 | 45.58 | 0.110 | 40.54 | 1.19 |
| B M45 ST + 50% TP1 | 413 | 36.6 | 32.33 | 0.078 | 36.97 | 1.14 |
| C H1 ST + 50% TP1 | 316 | 33.9 | -3.43 | -0.011 | 35.96 | 0.98 |
| D H1 ST + full trail | 316 | 32.3 | -2.26 | -0.007 | 46.83 | 0.99 |

### Call

**A wins the book, XAU, US100, and BTC.** Do not add half-TP1 to the locked M45 stack (B is the same 1570 trades as A, only the R split changes — it gives up **−68R**). Do not switch ST to H1.

| winner | cell |
| --- | --- |
| **Book / XAU / US100 / BTC** | **A — M45 ST + full trail** (locked) |
| GER40 / EURUSD (least-bad) | C — H1 ST + 50% TP1 |

XAU A +64 vs B +42 (−22R). US100 A +39 vs B +22 (−17R). Book A **+142** vs B +74 vs D +66 vs C +56.

## How to rerun

```bash
python3 -m tools.ha_hunt_st_compare.run_bakeoff
python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup
```

With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5.
Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).
