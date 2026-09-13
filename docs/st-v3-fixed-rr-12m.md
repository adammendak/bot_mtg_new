# ST_V3 12-month A 1:1.5 vs prod-like bakeoff

Generated **2026-09-13 19:02 UTC**. Research note — **do not merge as live Java**.

Standalone 12-month lock check after PR #151 (last-quarter fixed-RR) and PR #144 (12-month ST param / indices). Same simulator, same ST 7/2.0 lock, same loaders. **Does not change prod defaults.**

## Window and data

- **Trade window (entries):** `2025-09-13` → `2026-09-13` (365 calendar days, ending 2026-09-13).
- **Fetch / warmup:** `2025-05-16` → `2026-09-13` (120 calendar days before first entry so HTF RMA144 / ST 7/2.0 are live).
- **Data source order** (same as PR #151 / #144 `ha_hunt_st_compare`): local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 → Dukascopy M1 resampled to M5 → Coinbase Exchange 5m (BTC). Never invents prices.
- Loader notes per symbol are in the coverage table. HistData stamps are US Eastern → UTC; Dukascopy / Coinbase are UTC native.
- Last print can sit a few days before 2026-09-13 depending on vendor (HistData often lags; Coinbase is current).
- **Exact last print this run:** HistData names **2026-09-04** (XAU / US100 / US500 / GER40 / EURUSD / XAG / J225 / USDJPY); Dukascopy US30 **2026-09-11**; Coinbase BTC **2026-09-13**. Entries after a series' last print simply do not exist.

## Locked stack

- Supertrend **ATR 7 / factor 2.0** (PR #146 / #150).
- Stop = Supertrend line; **1R = |entry − ST|**. Invalid (wrong-side) ST stops skipped.
- Flat only (no pyramid). Cap 2 fills / ST regime.
- Fast RMA 33 / slow 144. HTF structure gate ON (same HTF as the Supertrend — Java `StV3Engine`).
- **A 1:1.5:** band-cross + ST bias, full close at +1.5R. No half TP1, no BE, no HA / band runner. Stop wins on a dual-hit bar.
- **Prod-like:** same entry, half @ 1:2 → stop to BE → entry-TF **band-cross** runner (PR #150 live `stV3Exit`).
- **Required compare:** M15+H1 on the full loaded book, with XAU and US100 called out (same pair as PR #151). M5+M45 is the cheap extra stack.

## Coverage

| symbol | source | n_M5 | n_M1 | days | first | last | note |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | histdata_m1_resampled_m5 | 92682 | 463318 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData XAUUSD M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |
| BTC | coinbase_exchange_m5 | 139532 | 0 | 485.0 | 2025-05-16T00:00:00 | 2026-09-13T00:00:00 | capital_unavailable: Capital DEMO credentials not set; Coinbase Exchange BTC-USD 5m public candles. Not Capital mids. HistData has no BTC. |
| US100 | histdata_m1_resampled_m5 | 89414 | 446763 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T20:10:00 | capital_unavailable: Capital DEMO credentials not set; HistData NSXUSD M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |
| US500 | histdata_m1_resampled_m5 | 89464 | 445473 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T20:10:00 | capital_unavailable: Capital DEMO credentials not set; HistData SPXUSD M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |
| US30 | dukascopy_m1_resampled_m5 | 90844 | 453766 | 483.8 | 2025-05-16T00:00:00 | 2026-09-11T20:10:00 | capital_unavailable: Capital DEMO credentials not set; Dukascopy USA30.IDX/USD M1 (UTC bid), resampled to M5. Not Capital mids. HistData has |
| GER40 | histdata_m1_resampled_m5 | 89754 | 443159 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T19:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData GRXEUR M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |
| EURUSD | histdata_m1_resampled_m5 | 97507 | 485500 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData EURUSD M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |
| XAG | histdata_m1_resampled_m5 | 92576 | 462453 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData XAGUSD M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |
| J225 | histdata_m1_resampled_m5 | 89267 | 441017 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T20:10:00 | capital_unavailable: Capital DEMO credentials not set; HistData JPXJPY M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |
| USDJPY | histdata_m1_resampled_m5 | 97590 | 485801 | 476.7 | 2025-05-16T04:00:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData USDJPY M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids. HistDat |

## M15+H1 — A 1:1.5 vs prod-like

WR% is R>0 on the booked R (full-size for A; scaled half+runner for prod-like). maxDD is peak-to-trough of that R equity, in R.

| symbol | stack | mode | n | WR% | sumR | PF | maxDD | avgR | note |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | M15+H1 | A 1:1.5 | 165 | 43.6 | +15.00 | 1.16 | 7.00 | 0.091 | required |
| XAU | M15+H1 | prod-like | 98 | 41.8 | +48.62 | 1.85 | 6.64 | 0.496 | required |
| BTC | M15+H1 | A 1:1.5 | 274 | 43.1 | +19.53 | 1.13 | 18.50 | 0.071 | primary |
| BTC | M15+H1 | prod-like | 187 | 34.2 | +4.58 | 1.04 | 19.76 | 0.024 | primary |
| US100 | M15+H1 | A 1:1.5 | 187 | 48.1 | +38.00 | 1.39 | 8.50 | 0.203 | required |
| US100 | M15+H1 | prod-like | 127 | 41.7 | +20.84 | 1.28 | 8.00 | 0.164 | required |
| US500 | M15+H1 | A 1:1.5 | 196 | 47.4 | +36.50 | 1.35 | 12.50 | 0.186 | primary |
| US500 | M15+H1 | prod-like | 127 | 40.2 | +11.01 | 1.14 | 14.07 | 0.087 | primary |
| US30 | M15+H1 | A 1:1.5 | 181 | 43.1 | +14.00 | 1.14 | 13.50 | 0.077 | primary |
| US30 | M15+H1 | prod-like | 130 | 36.2 | +9.61 | 1.12 | 19.88 | 0.074 | primary |
| GER40 | M15+H1 | A 1:1.5 | 207 | 37.7 | -11.25 | 0.91 | 18.00 | -0.054 | primary |
| GER40 | M15+H1 | prod-like | 137 | 34.3 | -14.62 | 0.84 | 18.00 | -0.107 | primary |
| EURUSD | M15+H1 | A 1:1.5 | 198 | 41.4 | +5.65 | 1.05 | 17.50 | 0.029 | primary |
| EURUSD | M15+H1 | prod-like | 133 | 33.8 | +19.95 | 1.23 | 20.48 | 0.150 | primary |
| XAG | M15+H1 | A 1:1.5 | 196 | 38.8 | -5.05 | 0.96 | 18.50 | -0.026 | extra |
| XAG | M15+H1 | prod-like | 127 | 34.6 | +25.04 | 1.31 | 14.77 | 0.197 | extra |
| J225 | M15+H1 | A 1:1.5 | 191 | 38.2 | -9.01 | 0.92 | 25.50 | -0.047 | extra |
| J225 | M15+H1 | prod-like | 135 | 32.6 | -8.20 | 0.91 | 24.68 | -0.061 | extra |
| USDJPY | M15+H1 | A 1:1.5 | 205 | 45.4 | +28.25 | 1.25 | 9.00 | 0.138 | extra |
| USDJPY | M15+H1 | prod-like | 132 | 37.9 | +9.53 | 1.12 | 13.92 | 0.072 | extra |

### Book + primary 7 + required pair

PRIMARY7 = XAU, BTC, US100, US500, US30, GER40, EURUSD. BOOK also includes XAG / J225 / USDJPY.

| book | stack | mode | n | WR% | sumR | PF | maxDD | avgR | winner |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| BOOK | M15+H1 | A 1:1.5 | 2000 | 42.6 | +131.62 | 1.11 | 41.50 | 0.066 | A 1:1.5 |
| BOOK | M15+H1 | prod-like | 1333 | 36.5 | +126.36 | 1.15 | 45.47 | 0.095 | A 1:1.5 |
| PRIMARY7 | M15+H1 | A 1:1.5 | 1408 | 43.4 | +117.43 | 1.15 | 24.00 | 0.083 | A 1:1.5 |
| PRIMARY7 | M15+H1 | prod-like | 939 | 37.1 | +99.99 | 1.17 | 39.11 | 0.106 | A 1:1.5 |
| XAU+US100 | M15+H1 | A 1:1.5 | 352 | 46.0 | +53.00 | 1.28 | 10.00 | 0.151 | prod-like |
| XAU+US100 | M15+H1 | prod-like | 225 | 41.8 | +69.46 | 1.53 | 11.00 | 0.309 | prod-like |

## M5+M45 — A 1:1.5 vs prod-like

WR% is R>0 on the booked R (full-size for A; scaled half+runner for prod-like). maxDD is peak-to-trough of that R equity, in R.

| symbol | stack | mode | n | WR% | sumR | PF | maxDD | avgR | note |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | M5+M45 | A 1:1.5 | 290 | 45.2 | +37.50 | 1.24 | 13.00 | 0.129 | required |
| XAU | M5+M45 | prod-like | 199 | 35.7 | +22.55 | 1.18 | 13.00 | 0.113 | required |
| BTC | M5+M45 | A 1:1.5 | 444 | 41.7 | +17.10 | 1.07 | 27.00 | 0.039 | primary |
| BTC | M5+M45 | prod-like | 314 | 33.8 | +10.78 | 1.05 | 28.48 | 0.034 | primary |
| US100 | M5+M45 | A 1:1.5 | 337 | 39.8 | -2.00 | 0.99 | 17.50 | -0.006 | required |
| US100 | M5+M45 | prod-like | 232 | 35.8 | +11.97 | 1.08 | 21.13 | 0.052 | required |
| US500 | M5+M45 | A 1:1.5 | 293 | 41.3 | +9.50 | 1.06 | 15.00 | 0.032 | primary |
| US500 | M5+M45 | prod-like | 184 | 42.9 | +38.85 | 1.37 | 11.76 | 0.211 | primary |
| US30 | M5+M45 | A 1:1.5 | 320 | 44.1 | +31.37 | 1.18 | 17.50 | 0.098 | primary |
| US30 | M5+M45 | prod-like | 223 | 38.6 | +12.46 | 1.09 | 28.42 | 0.056 | primary |
| GER40 | M5+M45 | A 1:1.5 | 319 | 42.9 | +24.10 | 1.13 | 13.50 | 0.076 | primary |
| GER40 | M5+M45 | prod-like | 204 | 37.7 | +21.99 | 1.17 | 16.77 | 0.108 | primary |
| EURUSD | M5+M45 | A 1:1.5 | 325 | 37.8 | -17.04 | 0.92 | 29.00 | -0.052 | primary |
| EURUSD | M5+M45 | prod-like | 224 | 32.6 | -9.98 | 0.93 | 30.39 | -0.045 | primary |
| XAG | M5+M45 | A 1:1.5 | 303 | 41.3 | +10.24 | 1.06 | 17.50 | 0.034 | extra |
| XAG | M5+M45 | prod-like | 199 | 36.7 | +33.46 | 1.27 | 13.62 | 0.168 | extra |
| J225 | M5+M45 | A 1:1.5 | 310 | 42.6 | +20.00 | 1.11 | 11.50 | 0.065 | extra |
| J225 | M5+M45 | prod-like | 209 | 37.3 | +16.34 | 1.12 | 12.06 | 0.078 | extra |
| USDJPY | M5+M45 | A 1:1.5 | 326 | 39.3 | -5.40 | 0.97 | 39.50 | -0.017 | extra |
| USDJPY | M5+M45 | prod-like | 205 | 34.1 | +20.87 | 1.15 | 33.44 | 0.102 | extra |

### Book + primary 7 + required pair

PRIMARY7 = XAU, BTC, US100, US500, US30, GER40, EURUSD. BOOK also includes XAG / J225 / USDJPY.

| book | stack | mode | n | WR% | sumR | PF | maxDD | avgR | winner |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| BOOK | M5+M45 | A 1:1.5 | 3267 | 41.5 | +125.38 | 1.07 | 52.50 | 0.038 | prod-like |
| BOOK | M5+M45 | prod-like | 2193 | 36.3 | +179.28 | 1.13 | 40.29 | 0.082 | prod-like |
| PRIMARY7 | M5+M45 | A 1:1.5 | 2328 | 41.8 | +100.54 | 1.07 | 55.00 | 0.043 | prod-like |
| PRIMARY7 | M5+M45 | prod-like | 1580 | 36.4 | +108.61 | 1.11 | 51.31 | 0.069 | prod-like |
| XAU+US100 | M5+M45 | A 1:1.5 | 627 | 42.3 | +35.50 | 1.10 | 17.00 | 0.057 | A 1:1.5 |
| XAU+US100 | M5+M45 | prod-like | 431 | 35.7 | +34.52 | 1.12 | 20.17 | 0.080 | A 1:1.5 |

## Per-symbol winner (sumR)

| symbol | stack | A 1:1.5 | prod-like | Δ sumR | winner | note |
| --- | --- | ---: | ---: | ---: | --- | --- |
| XAU | M15+H1 | +15.00 | +48.62 | -33.62 | prod-like | required |
| BTC | M15+H1 | +19.53 | +4.58 | +14.95 | A 1:1.5 | primary |
| US100 | M15+H1 | +38.00 | +20.84 | +17.16 | A 1:1.5 | required |
| US500 | M15+H1 | +36.50 | +11.01 | +25.49 | A 1:1.5 | primary |
| US30 | M15+H1 | +14.00 | +9.61 | +4.39 | A 1:1.5 | primary |
| GER40 | M15+H1 | -11.25 | -14.62 | +3.37 | A 1:1.5 | primary |
| EURUSD | M15+H1 | +5.65 | +19.95 | -14.30 | prod-like | primary |
| XAG | M15+H1 | -5.05 | +25.04 | -30.10 | prod-like | extra |
| J225 | M15+H1 | -9.01 | -8.20 | -0.80 | prod-like | extra |
| USDJPY | M15+H1 | +28.25 | +9.53 | +18.72 | A 1:1.5 | extra |
| XAU | M5+M45 | +37.50 | +22.55 | +14.95 | A 1:1.5 | required |
| BTC | M5+M45 | +17.10 | +10.78 | +6.33 | A 1:1.5 | primary |
| US100 | M5+M45 | -2.00 | +11.97 | -13.97 | prod-like | required |
| US500 | M5+M45 | +9.50 | +38.85 | -29.35 | prod-like | primary |
| US30 | M5+M45 | +31.37 | +12.46 | +18.92 | A 1:1.5 | primary |
| GER40 | M5+M45 | +24.10 | +21.99 | +2.11 | A 1:1.5 | primary |
| EURUSD | M5+M45 | -17.04 | -9.98 | -7.06 | prod-like | primary |
| XAG | M5+M45 | +10.24 | +33.46 | -23.22 | prod-like | extra |
| J225 | M5+M45 | +20.00 | +16.34 | +3.66 | A 1:1.5 | extra |
| USDJPY | M5+M45 | -5.40 | +20.87 | -26.27 | prod-like | extra |

## Call

**12-month still favors the prod runner. Do not deploy fixed 1:1.5.** Required XAU+US100 M15+H1: prod-like 41.8% / +69.5R (n=225, PF 1.53, DD 11.0) vs A 1:1.5 46.0% / +53.0R (n=352, PF 1.28, DD 10.0). The #151 quarter (A +24.5R vs prod +6.9R on this pair) does not survive 12 months. XAU is the runner lock; US100 alone prefers 1:1.5 and is not enough. 10-name M15 book is a coin flip on sumR (A +131.6R vs prod +126.4R) but prod keeps the better avgR / PF. PRIMARY7 M15: A +117.4R vs prod +100.0R (Δ -17.4R for prod). M5+M45 10-name book favors prod: A 1:1.5 41.5% / +125.4R (n=3267, PF 1.07, DD 52.5) vs prod-like 36.3% / +179.3R (n=2193, PF 1.13, DD 40.3).

- Do **not** change prod Java defaults (`HtsTradeService.ST_V3_TP1_MULT`, band-cross runner, satellite universes).
- Do **not** merge this note as a live switch.

## How this relates to #151 / #144

- PR #151 (90d to 2026-09-13) found that quarter *friendly* to fixed RR and *hostile* to the prod runner (XAU+US100 M15+H1 A 1:1.5 +24.5R vs prod +6.9R; book-wide prod about −34R).
- PR #144 / the 12-month lock that shipped ST_V3 was the current runner (half+BE+HA then, band-cross now) at ST 7/2.0.
- This note asks whether that 12-month lock still holds when management is the current band-cross runner vs a simpler full 1:1.5.

## How to rerun

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_fixed_rr_12m
```

Flags: `--symbols XAU,US100,BTC` · `--no-extra` · `--no-m5`.
Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).

