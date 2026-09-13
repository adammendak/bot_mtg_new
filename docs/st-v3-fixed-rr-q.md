# ST_V3 last-quarter fixed-RR bakeoff

Generated **2026-09-13 18:41 UTC**. Research note — **do not merge as live Java**.

## Window and data

- **Trade window (entries):** `2026-06-15` → `2026-09-13` (90 calendar days, ending ~2026-09-13).
- **Fetch / warmup:** `2026-02-15` → `2026-09-13` (120 calendar days before first entry so HTF RMA144 / ST 7/2.0 are live).
- **Data source order** (same as prior `ha_hunt_st_compare` bakeoffs): local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 → Dukascopy M1 resampled to M5 → Coinbase Exchange 5m (BTC). Never invents prices.
- Loader notes per symbol are in the coverage table. HistData stamps are US Eastern → UTC; Dukascopy / Coinbase are UTC native.
- **Last print:** HistData names end **2026-09-04** (vendor lag vs the 2026-09-13 window end). US30 Dukascopy through **2026-09-11**. BTC Coinbase through **2026-09-13**. No Capital DEMO creds in this environment.

## Locked stack (current ST_V3, management replaced on RR cells)

- Supertrend **ATR 7 / factor 2.0** (PR #146 / #150).
- Stop = Supertrend line; **1R = |entry − ST|**. Invalid (wrong-side) ST stops skipped.
- Flat only (no pyramid). Cap 2 fills / ST regime.
- Fast RMA 33 / slow 144. HTF structure gate ON for band-cross cells (same HTF as the Supertrend — Java `StV3Engine`, not the older pine M45-always gate).
- **Fixed-RR cells:** full close at +1.0R or +1.5R. No half TP1, no BE runner, no HA / band runner. Still flatten if the original ST stop is hit first (stop wins on a dual-hit bar).
- **Prod-like baseline:** half @ 1:2 → stop to BE → entry-TF **band-cross** runner (PR #150 live `stV3Exit`). Labeled `prod-like`. Required compare: M15+H1 on XAU and US100; the same cell is also printed for every loaded symbol.

### Entry modes

- **A — band-cross + ST bias:** first entry-TF close beyond the fast band, direction must match HTF Supertrend (current ST_V3 trigger).
- **B — Supertrend flip only:** enter on the chart bar where the closed HTF Supertrend prints a new direction; no band-cross required.
- **C — band-cross without ST agree:** same band-cross as A, ST direction not required. With a strict ST-line stop this is usually identical to A (against-ST entries have the line on the wrong side and are skipped).

## Coverage

| symbol | source | n_M5 | n_M1 | days | first | last | note |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | histdata_m1_resampled_m5 | 39564 | 197792 | 200.9 | 2026-02-15T23:00:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData XAUUSD M1 (Eastern stamps → UTC), resampled to M5. Not C |
| BTC | coinbase_exchange_m5 | 60402 | 0 | 210.0 | 2026-02-15T00:00:00 | 2026-09-13T00:00:00 | capital_unavailable: Capital DEMO credentials not set; Coinbase Exchange BTC-USD 5m public candles. Not Capital mids. Hi |
| US100 | histdata_m1_resampled_m5 | 38235 | 191037 | 200.9 | 2026-02-15T23:00:00 | 2026-09-04T20:10:00 | capital_unavailable: Capital DEMO credentials not set; HistData NSXUSD M1 (Eastern stamps → UTC), resampled to M5. Not C |
| US500 | histdata_m1_resampled_m5 | 38246 | 190981 | 200.9 | 2026-02-15T23:00:00 | 2026-09-04T20:10:00 | capital_unavailable: Capital DEMO credentials not set; HistData SPXUSD M1 (Eastern stamps → UTC), resampled to M5. Not C |
| US30 | dukascopy_m1_resampled_m5 | 39560 | 197555 | 207.9 | 2026-02-15T23:00:00 | 2026-09-11T20:10:00 | capital_unavailable: Capital DEMO credentials not set; Dukascopy USA30.IDX/USD M1 (UTC bid), resampled to M5. Not Capita |
| GER40 | histdata_m1_resampled_m5 | 38292 | 190363 | 200.9 | 2026-02-15T23:00:00 | 2026-09-04T19:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData GRXEUR M1 (Eastern stamps → UTC), resampled to M5. Not C |
| EURUSD | histdata_m1_resampled_m5 | 41636 | 207252 | 200.9 | 2026-02-15T22:00:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData EURUSD M1 (Eastern stamps → UTC), resampled to M5. Not C |
| XAG | histdata_m1_resampled_m5 | 39473 | 197232 | 200.9 | 2026-02-15T23:05:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData XAGUSD M1 (Eastern stamps → UTC), resampled to M5. Not C |
| J225 | histdata_m1_resampled_m5 | 38159 | 189237 | 200.9 | 2026-02-15T23:00:00 | 2026-09-04T20:10:00 | capital_unavailable: Capital DEMO credentials not set; HistData JPXJPY M1 (Eastern stamps → UTC), resampled to M5. Not C |
| USDJPY | histdata_m1_resampled_m5 | 41696 | 207539 | 200.9 | 2026-02-15T22:00:00 | 2026-09-04T20:55:00 | capital_unavailable: Capital DEMO credentials not set; HistData USDJPY M1 (Eastern stamps → UTC), resampled to M5. Not C |

## Master matrix — symbol × stack × entry × RR

WR% is R>0 on the booked (full-size) R. maxDD is peak-to-trough of the R equity, in R.

| symbol | stack | entry | RR | n | WR% | sumR | PF | maxDD | avgR | note |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | M5+M45 | A | 1:1 | 79 | 53.2 | +5.00 | 1.14 | 6.00 | 0.063 | hits ~50%+ WR and +EV |
| XAU | M5+M45 | A | 1:1.5 | 65 | 47.7 | +12.50 | 1.37 | 5.50 | 0.192 | +EV, WR below 50% |
| XAU | M5+M45 | B | 1:1 | 40 | 57.5 | +6.71 | 1.41 | 3.00 | 0.168 | hits ~50%+ WR and +EV |
| XAU | M5+M45 | B | 1:1.5 | 37 | 56.8 | +16.21 | 2.06 | 2.00 | 0.438 | hits ~50%+ WR and +EV |
| XAU | M5+M45 | C | 1:1 | 83 | 53.0 | +5.00 | 1.13 | 7.00 | 0.060 | hits ~50%+ WR and +EV |
| XAU | M5+M45 | C | 1:1.5 | 69 | 47.8 | +13.50 | 1.37 | 6.50 | 0.196 | +EV, WR below 50% |
| BTC | M5+M45 | A | 1:1 | 116 | 55.2 | +11.10 | 1.21 | 9.00 | 0.096 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | A | 1:1.5 | 112 | 45.5 | +14.10 | 1.23 | 10.00 | 0.126 | +EV, WR below 50% |
| BTC | M5+M45 | B | 1:1 | 45 | 60.0 | +9.96 | 1.58 | 4.00 | 0.221 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | B | 1:1.5 | 33 | 57.6 | +15.46 | 2.18 | 3.50 | 0.468 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | C | 1:1 | 118 | 55.1 | +11.10 | 1.21 | 10.00 | 0.094 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | C | 1:1.5 | 114 | 45.6 | +14.60 | 1.24 | 11.00 | 0.128 | +EV, WR below 50% |
| US100 | M5+M45 | A | 1:1 | 84 | 54.8 | +8.00 | 1.21 | 9.00 | 0.095 | hits ~50%+ WR and +EV |
| US100 | M5+M45 | A | 1:1.5 | 78 | 39.7 | -0.50 | 0.99 | 10.50 | -0.006 | −EV |
| US100 | M5+M45 | B | 1:1 | 50 | 52.0 | +2.00 | 1.08 | 5.00 | 0.040 | hits ~50%+ WR and +EV |
| US100 | M5+M45 | B | 1:1.5 | 34 | 38.2 | -1.50 | 0.93 | 5.00 | -0.044 | −EV |
| US100 | M5+M45 | C | 1:1 | 91 | 51.6 | +3.00 | 1.07 | 10.00 | 0.033 | hits ~50%+ WR and +EV |
| US100 | M5+M45 | C | 1:1.5 | 85 | 37.6 | -5.00 | 0.91 | 10.50 | -0.059 | −EV |
| US500 | M5+M45 | A | 1:1 | 70 | 51.4 | +2.00 | 1.06 | 9.00 | 0.029 | hits ~50%+ WR and +EV |
| US500 | M5+M45 | A | 1:1.5 | 56 | 39.3 | -1.00 | 0.97 | 10.00 | -0.018 | −EV |
| US500 | M5+M45 | B | 1:1 | 42 | 45.2 | -4.00 | 0.83 | 10.00 | -0.095 | −EV |
| US500 | M5+M45 | B | 1:1.5 | 37 | 43.2 | +2.43 | 1.12 | 8.00 | 0.066 | +EV, WR below 50% |
| US500 | M5+M45 | C | 1:1 | 75 | 50.7 | +1.00 | 1.03 | 8.00 | 0.013 | marginal +EV |
| US500 | M5+M45 | C | 1:1.5 | 59 | 39.0 | -1.50 | 0.96 | 10.50 | -0.025 | −EV |
| US30 | M5+M45 | A | 1:1 | 96 | 54.2 | +8.00 | 1.18 | 15.00 | 0.083 | hits ~50%+ WR and +EV |
| US30 | M5+M45 | A | 1:1.5 | 90 | 43.3 | +6.37 | 1.12 | 13.50 | 0.071 | +EV, WR below 50% |
| US30 | M5+M45 | B | 1:1 | 50 | 42.0 | -8.00 | 0.72 | 15.00 | -0.160 | −EV |
| US30 | M5+M45 | B | 1:1.5 | 45 | 42.2 | +2.50 | 1.10 | 13.50 | 0.056 | marginal +EV |
| US30 | M5+M45 | C | 1:1 | 99 | 53.5 | +7.00 | 1.15 | 16.00 | 0.071 | hits ~50%+ WR and +EV |
| US30 | M5+M45 | C | 1:1.5 | 94 | 41.5 | +2.37 | 1.04 | 17.50 | 0.025 | marginal +EV |
| GER40 | M5+M45 | A | 1:1 | 83 | 45.8 | -6.40 | 0.86 | 12.00 | -0.077 | −EV |
| GER40 | M5+M45 | A | 1:1.5 | 78 | 35.9 | -7.40 | 0.85 | 10.00 | -0.095 | −EV |
| GER40 | M5+M45 | B | 1:1 | 59 | 45.8 | -4.42 | 0.86 | 8.42 | -0.075 | −EV |
| GER40 | M5+M45 | B | 1:1.5 | 45 | 33.3 | -6.92 | 0.76 | 9.50 | -0.154 | −EV |
| GER40 | M5+M45 | C | 1:1 | 84 | 45.2 | -7.40 | 0.84 | 12.00 | -0.088 | −EV |
| GER40 | M5+M45 | C | 1:1.5 | 79 | 35.4 | -8.40 | 0.83 | 10.00 | -0.106 | −EV |
| EURUSD | M5+M45 | A | 1:1 | 82 | 50.0 | +0.46 | 1.01 | 14.00 | 0.006 | marginal +EV |
| EURUSD | M5+M45 | A | 1:1.5 | 72 | 38.9 | -1.54 | 0.96 | 20.00 | -0.021 | −EV |
| EURUSD | M5+M45 | B | 1:1 | 49 | 61.2 | +11.00 | 1.58 | 3.00 | 0.224 | hits ~50%+ WR and +EV |
| EURUSD | M5+M45 | B | 1:1.5 | 45 | 48.9 | +10.00 | 1.43 | 3.50 | 0.222 | +EV, WR below 50% |
| EURUSD | M5+M45 | C | 1:1 | 82 | 50.0 | +0.46 | 1.01 | 14.00 | 0.006 | marginal +EV |
| EURUSD | M5+M45 | C | 1:1.5 | 67 | 38.8 | -1.54 | 0.96 | 20.00 | -0.023 | −EV |
| XAG | M5+M45 | A | 1:1 | 71 | 49.3 | -0.26 | 0.99 | 8.00 | -0.004 | −EV |
| XAG | M5+M45 | A | 1:1.5 | 67 | 41.8 | +3.74 | 1.10 | 6.50 | 0.056 | marginal +EV |
| XAG | M5+M45 | B | 1:1 | 41 | 53.7 | +3.56 | 1.19 | 5.00 | 0.087 | hits ~50%+ WR and +EV |
| XAG | M5+M45 | B | 1:1.5 | 28 | 42.9 | +1.07 | 1.07 | 3.50 | 0.038 | marginal +EV |
| XAG | M5+M45 | C | 1:1 | 74 | 48.6 | -1.26 | 0.97 | 10.00 | -0.017 | −EV |
| XAG | M5+M45 | C | 1:1.5 | 70 | 41.4 | +3.24 | 1.08 | 8.00 | 0.046 | marginal +EV |
| J225 | M5+M45 | A | 1:1 | 90 | 50.0 | -0.00 | 1.00 | 11.00 | -0.000 | hits ~50%+ WR but −EV |
| J225 | M5+M45 | A | 1:1.5 | 76 | 38.2 | -3.50 | 0.93 | 10.00 | -0.046 | −EV |
| J225 | M5+M45 | B | 1:1 | 48 | 45.8 | -3.51 | 0.86 | 5.51 | -0.073 | −EV |
| J225 | M5+M45 | B | 1:1.5 | 42 | 42.9 | +3.00 | 1.12 | 10.50 | 0.071 | +EV, WR below 50% |
| J225 | M5+M45 | C | 1:1 | 91 | 50.5 | +1.00 | 1.02 | 11.00 | 0.011 | marginal +EV |
| J225 | M5+M45 | C | 1:1.5 | 77 | 39.0 | -2.00 | 0.96 | 10.00 | -0.026 | −EV |
| USDJPY | M5+M45 | A | 1:1 | 74 | 41.9 | -11.40 | 0.73 | 17.00 | -0.154 | −EV |
| USDJPY | M5+M45 | A | 1:1.5 | 69 | 34.8 | -8.40 | 0.81 | 18.50 | -0.122 | −EV |
| USDJPY | M5+M45 | B | 1:1 | 50 | 50.0 | +0.75 | 1.03 | 5.00 | 0.015 | marginal +EV |
| USDJPY | M5+M45 | B | 1:1.5 | 42 | 50.0 | +11.25 | 1.56 | 4.00 | 0.268 | hits ~50%+ WR and +EV |
| USDJPY | M5+M45 | C | 1:1 | 75 | 41.3 | -12.40 | 0.71 | 19.00 | -0.165 | −EV |
| USDJPY | M5+M45 | C | 1:1.5 | 70 | 34.3 | -9.40 | 0.79 | 21.00 | -0.134 | −EV |
| XAU | M15+H1 | A | 1:1 | 44 | 52.3 | +2.00 | 1.10 | 6.00 | 0.045 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | A | 1:1.5 | 43 | 46.5 | +7.00 | 1.30 | 5.50 | 0.163 | +EV, WR below 50% |
| XAU | M15+H1 | B | 1:1 | 33 | 69.7 | +13.74 | 2.48 | 2.00 | 0.416 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | B | 1:1.5 | 25 | 64.0 | +13.85 | 2.54 | 2.00 | 0.554 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | C | 1:1 | 49 | 51.0 | +1.37 | 1.06 | 6.00 | 0.028 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | C | 1:1.5 | 48 | 43.8 | +4.87 | 1.18 | 5.50 | 0.101 | +EV, WR below 50% |
| BTC | M15+H1 | A | 1:1 | 77 | 51.9 | +2.03 | 1.05 | 11.00 | 0.026 | hits ~50%+ WR and +EV |
| BTC | M15+H1 | A | 1:1.5 | 73 | 42.5 | +3.03 | 1.07 | 12.00 | 0.041 | marginal +EV |
| BTC | M15+H1 | B | 1:1 | 37 | 59.5 | +7.23 | 1.49 | 5.00 | 0.195 | hits ~50%+ WR and +EV |
| BTC | M15+H1 | B | 1:1.5 | 29 | 51.7 | +7.92 | 1.57 | 5.00 | 0.273 | hits ~50%+ WR and +EV |
| BTC | M15+H1 | C | 1:1 | 90 | 50.0 | -0.97 | 0.98 | 13.00 | -0.011 | hits ~50%+ WR but −EV |
| BTC | M15+H1 | C | 1:1.5 | 84 | 41.7 | +2.03 | 1.04 | 13.50 | 0.024 | marginal +EV |
| US100 | M15+H1 | A | 1:1 | 52 | 61.5 | +12.00 | 1.60 | 3.00 | 0.231 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | A | 1:1.5 | 50 | 54.0 | +17.50 | 1.76 | 3.50 | 0.350 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | B | 1:1 | 36 | 44.4 | -4.00 | 0.80 | 10.00 | -0.111 | −EV |
| US100 | M15+H1 | B | 1:1.5 | 32 | 53.1 | +10.50 | 1.70 | 3.50 | 0.328 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | C | 1:1 | 59 | 57.6 | +9.00 | 1.36 | 5.00 | 0.153 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | C | 1:1.5 | 57 | 50.9 | +15.50 | 1.55 | 5.00 | 0.272 | hits ~50%+ WR and +EV |
| US500 | M15+H1 | A | 1:1 | 50 | 44.0 | -6.00 | 0.79 | 15.00 | -0.120 | −EV |
| US500 | M15+H1 | A | 1:1.5 | 45 | 37.8 | -2.50 | 0.91 | 12.50 | -0.056 | −EV |
| US500 | M15+H1 | B | 1:1 | 42 | 47.6 | -2.00 | 0.91 | 7.00 | -0.048 | −EV |
| US500 | M15+H1 | B | 1:1.5 | 37 | 32.4 | -7.77 | 0.69 | 13.00 | -0.210 | −EV |
| US500 | M15+H1 | C | 1:1 | 55 | 41.8 | -9.00 | 0.72 | 15.00 | -0.164 | −EV |
| US500 | M15+H1 | C | 1:1.5 | 48 | 39.6 | -0.50 | 0.98 | 13.00 | -0.010 | −EV |
| US30 | M15+H1 | A | 1:1 | 45 | 57.8 | +7.00 | 1.37 | 5.00 | 0.156 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | A | 1:1.5 | 43 | 51.2 | +12.00 | 1.57 | 5.50 | 0.279 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | B | 1:1 | 36 | 55.6 | +4.00 | 1.25 | 5.00 | 0.111 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | B | 1:1.5 | 32 | 34.4 | -4.50 | 0.79 | 14.50 | -0.141 | −EV |
| US30 | M15+H1 | C | 1:1 | 55 | 54.5 | +5.00 | 1.20 | 10.00 | 0.091 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | C | 1:1.5 | 53 | 49.1 | +12.00 | 1.44 | 8.50 | 0.226 | +EV, WR below 50% |
| GER40 | M15+H1 | A | 1:1 | 56 | 48.2 | -1.25 | 0.96 | 7.00 | -0.022 | −EV |
| GER40 | M15+H1 | A | 1:1.5 | 53 | 32.1 | -9.75 | 0.72 | 11.25 | -0.184 | −EV |
| GER40 | M15+H1 | B | 1:1 | 44 | 36.4 | -11.51 | 0.58 | 14.00 | -0.262 | −EV |
| GER40 | M15+H1 | B | 1:1.5 | 38 | 34.2 | -5.01 | 0.80 | 10.50 | -0.132 | −EV |
| GER40 | M15+H1 | C | 1:1 | 59 | 49.2 | -0.25 | 0.99 | 7.00 | -0.004 | −EV |
| GER40 | M15+H1 | C | 1:1.5 | 56 | 33.9 | -7.75 | 0.79 | 9.25 | -0.138 | −EV |
| EURUSD | M15+H1 | A | 1:1 | 49 | 44.9 | -5.85 | 0.78 | 9.00 | -0.119 | −EV |
| EURUSD | M15+H1 | A | 1:1.5 | 46 | 39.1 | -2.35 | 0.92 | 8.50 | -0.051 | −EV |
| EURUSD | M15+H1 | B | 1:1 | 40 | 55.0 | +4.83 | 1.28 | 4.00 | 0.121 | hits ~50%+ WR and +EV |
| EURUSD | M15+H1 | B | 1:1.5 | 32 | 56.2 | +13.83 | 2.05 | 3.00 | 0.432 | hits ~50%+ WR and +EV |
| EURUSD | M15+H1 | C | 1:1 | 51 | 43.1 | -7.85 | 0.73 | 9.00 | -0.154 | −EV |
| EURUSD | M15+H1 | C | 1:1.5 | 48 | 37.5 | -4.35 | 0.86 | 8.50 | -0.091 | −EV |
| XAG | M15+H1 | A | 1:1 | 51 | 45.1 | -4.05 | 0.85 | 13.00 | -0.080 | −EV |
| XAG | M15+H1 | A | 1:1.5 | 48 | 33.3 | -7.05 | 0.77 | 16.50 | -0.147 | −EV |
| XAG | M15+H1 | B | 1:1 | 35 | 54.3 | +2.18 | 1.14 | 6.00 | 0.062 | hits ~50%+ WR and +EV |
| XAG | M15+H1 | B | 1:1.5 | 31 | 38.7 | -0.38 | 0.98 | 7.50 | -0.012 | −EV |
| XAG | M15+H1 | C | 1:1 | 58 | 39.7 | -11.05 | 0.68 | 15.00 | -0.191 | −EV |
| XAG | M15+H1 | C | 1:1.5 | 55 | 29.1 | -14.05 | 0.63 | 20.00 | -0.256 | −EV |
| J225 | M15+H1 | A | 1:1 | 49 | 46.9 | -3.00 | 0.88 | 9.00 | -0.061 | −EV |
| J225 | M15+H1 | A | 1:1.5 | 47 | 38.3 | -2.51 | 0.91 | 10.50 | -0.053 | −EV |
| J225 | M15+H1 | B | 1:1 | 43 | 51.2 | +0.01 | 1.00 | 7.00 | 0.000 | marginal +EV |
| J225 | M15+H1 | B | 1:1.5 | 35 | 28.6 | -9.61 | 0.61 | 10.61 | -0.275 | −EV |
| J225 | M15+H1 | C | 1:1 | 55 | 45.5 | -5.00 | 0.83 | 13.00 | -0.091 | −EV |
| J225 | M15+H1 | C | 1:1.5 | 53 | 37.7 | -3.51 | 0.89 | 13.50 | -0.066 | −EV |
| USDJPY | M15+H1 | A | 1:1 | 50 | 46.0 | -3.25 | 0.88 | 7.00 | -0.065 | −EV |
| USDJPY | M15+H1 | A | 1:1.5 | 46 | 37.0 | -2.75 | 0.90 | 9.00 | -0.060 | −EV |
| USDJPY | M15+H1 | B | 1:1 | 40 | 55.0 | +4.59 | 1.26 | 5.00 | 0.115 | hits ~50%+ WR and +EV |
| USDJPY | M15+H1 | B | 1:1.5 | 37 | 48.6 | +8.59 | 1.47 | 5.00 | 0.232 | +EV, WR below 50% |
| USDJPY | M15+H1 | C | 1:1 | 56 | 41.1 | -9.25 | 0.71 | 9.00 | -0.165 | −EV |
| USDJPY | M15+H1 | C | 1:1.5 | 53 | 34.0 | -7.25 | 0.79 | 10.50 | -0.137 | −EV |
| XAU | H1+H4 | A | 1:1 | 13 | 30.8 | -5.00 | 0.44 | 7.00 | -0.385 | −EV |
| XAU | H1+H4 | A | 1:1.5 | 13 | 23.1 | -5.50 | 0.45 | 8.50 | -0.423 | −EV |
| XAU | H1+H4 | B | 1:1 | 13 | 53.8 | +1.00 | 1.17 | 3.00 | 0.077 | hits ~50%+ WR and +EV |
| XAU | H1+H4 | B | 1:1.5 | 10 | 50.0 | +1.50 | 1.30 | 3.00 | 0.150 | hits ~50%+ WR and +EV |
| XAU | H1+H4 | C | 1:1 | 18 | 27.8 | -8.00 | 0.38 | 10.00 | -0.444 | −EV |
| XAU | H1+H4 | C | 1:1.5 | 18 | 22.2 | -8.00 | 0.43 | 11.00 | -0.444 | −EV |
| BTC | H1+H4 | A | 1:1 | 16 | 43.8 | -2.70 | 0.70 | 5.00 | -0.169 | −EV |
| BTC | H1+H4 | A | 1:1.5 | 15 | 26.7 | -6.20 | 0.44 | 7.00 | -0.413 | −EV |
| BTC | H1+H4 | B | 1:1 | 14 | 50.0 | +0.00 | 1.00 | 2.00 | 0.000 | hits ~50%+ WR but −EV |
| BTC | H1+H4 | B | 1:1.5 | 10 | 40.0 | +0.00 | 1.00 | 3.50 | 0.000 | marginal +EV |
| BTC | H1+H4 | C | 1:1 | 18 | 44.4 | -2.70 | 0.73 | 4.00 | -0.150 | −EV |
| BTC | H1+H4 | C | 1:1.5 | 17 | 29.4 | -5.70 | 0.53 | 6.50 | -0.335 | −EV |
| US100 | H1+H4 | A | 1:1 | 17 | 52.9 | +1.00 | 1.12 | 4.00 | 0.059 | hits ~50%+ WR and +EV |
| US100 | H1+H4 | A | 1:1.5 | 15 | 53.3 | +5.00 | 1.71 | 3.00 | 0.333 | hits ~50%+ WR and +EV |
| US100 | H1+H4 | B | 1:1 | 13 | 53.8 | +0.04 | 1.01 | 3.00 | 0.003 | marginal +EV |
| US100 | H1+H4 | B | 1:1.5 | 9 | 44.4 | -0.46 | 0.91 | 3.00 | -0.051 | −EV |
| US100 | H1+H4 | C | 1:1 | 18 | 50.0 | +0.00 | 1.00 | 5.00 | 0.000 | hits ~50%+ WR but −EV |
| US100 | H1+H4 | C | 1:1.5 | 16 | 50.0 | +4.00 | 1.50 | 4.00 | 0.250 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | A | 1:1 | 19 | 52.6 | +1.00 | 1.11 | 2.00 | 0.053 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | A | 1:1.5 | 17 | 35.3 | -2.00 | 0.82 | 4.00 | -0.118 | −EV |
| US500 | H1+H4 | B | 1:1 | 13 | 53.8 | +1.00 | 1.17 | 3.00 | 0.077 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | B | 1:1.5 | 8 | 50.0 | +1.16 | 1.29 | 2.00 | 0.145 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | C | 1:1 | 20 | 50.0 | +0.00 | 1.00 | 2.00 | 0.000 | hits ~50%+ WR but −EV |
| US500 | H1+H4 | C | 1:1.5 | 18 | 33.3 | -3.00 | 0.75 | 4.00 | -0.167 | −EV |
| US30 | H1+H4 | A | 1:1 | 14 | 42.9 | -2.00 | 0.75 | 3.00 | -0.143 | −EV |
| US30 | H1+H4 | A | 1:1.5 | 14 | 35.7 | -1.50 | 0.83 | 3.00 | -0.107 | −EV |
| US30 | H1+H4 | B | 1:1 | 11 | 72.7 | +4.01 | 2.34 | 1.00 | 0.364 | hits ~50%+ WR and +EV |
| US30 | H1+H4 | B | 1:1.5 | 4 | 50.0 | -0.49 | 0.75 | 1.00 | -0.123 | thin |
| US30 | H1+H4 | C | 1:1 | 16 | 43.8 | -2.00 | 0.78 | 3.00 | -0.125 | −EV |
| US30 | H1+H4 | C | 1:1.5 | 16 | 37.5 | -1.00 | 0.90 | 3.00 | -0.063 | −EV |
| GER40 | H1+H4 | A | 1:1 | 10 | 70.0 | +4.00 | 2.33 | 1.00 | 0.400 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | A | 1:1.5 | 9 | 44.4 | +1.00 | 1.20 | 2.00 | 0.111 | +EV, WR below 50% |
| GER40 | H1+H4 | B | 1:1 | 11 | 63.6 | +2.09 | 1.52 | 2.00 | 0.190 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | B | 1:1.5 | 8 | 62.5 | +3.41 | 2.14 | 1.00 | 0.426 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | C | 1:1 | 12 | 66.7 | +4.00 | 2.00 | 2.00 | 0.333 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | C | 1:1.5 | 11 | 45.5 | +1.50 | 1.25 | 2.00 | 0.136 | +EV, WR below 50% |
| EURUSD | H1+H4 | A | 1:1 | 17 | 41.2 | -2.21 | 0.76 | 3.21 | -0.130 | −EV |
| EURUSD | H1+H4 | A | 1:1.5 | 16 | 37.5 | -0.21 | 0.98 | 3.21 | -0.013 | −EV |
| EURUSD | H1+H4 | B | 1:1 | 13 | 46.2 | -0.20 | 0.97 | 3.20 | -0.015 | −EV |
| EURUSD | H1+H4 | B | 1:1.5 | 9 | 44.4 | +1.80 | 1.43 | 2.20 | 0.200 | +EV, WR below 50% |
| EURUSD | H1+H4 | C | 1:1 | 20 | 40.0 | -3.21 | 0.71 | 5.00 | -0.160 | −EV |
| EURUSD | H1+H4 | C | 1:1.5 | 19 | 36.8 | -0.71 | 0.94 | 4.50 | -0.037 | −EV |
| XAG | H1+H4 | A | 1:1 | 11 | 63.6 | +3.00 | 1.75 | 2.00 | 0.273 | hits ~50%+ WR and +EV |
| XAG | H1+H4 | A | 1:1.5 | 11 | 45.5 | +1.50 | 1.25 | 4.50 | 0.136 | +EV, WR below 50% |
| XAG | H1+H4 | B | 1:1 | 12 | 58.3 | +1.04 | 1.21 | 4.00 | 0.087 | hits ~50%+ WR and +EV |
| XAG | H1+H4 | B | 1:1.5 | 11 | 36.4 | -0.05 | 0.99 | 6.05 | -0.005 | −EV |
| XAG | H1+H4 | C | 1:1 | 13 | 61.5 | +3.00 | 1.60 | 2.00 | 0.231 | hits ~50%+ WR and +EV |
| XAG | H1+H4 | C | 1:1.5 | 13 | 46.2 | +2.00 | 1.29 | 5.50 | 0.154 | +EV, WR below 50% |
| J225 | H1+H4 | A | 1:1 | 17 | 35.3 | -5.00 | 0.55 | 7.00 | -0.294 | −EV |
| J225 | H1+H4 | A | 1:1.5 | 17 | 29.4 | -4.05 | 0.65 | 5.55 | -0.238 | −EV |
| J225 | H1+H4 | B | 1:1 | 11 | 54.5 | +1.99 | 1.50 | 2.01 | 0.181 | hits ~50%+ WR and +EV |
| J225 | H1+H4 | B | 1:1.5 | 5 | 60.0 | +3.49 | 4.46 | 1.00 | 0.698 | thin |
| J225 | H1+H4 | C | 1:1 | 18 | 33.3 | -6.00 | 0.50 | 8.00 | -0.333 | −EV |
| J225 | H1+H4 | C | 1:1.5 | 18 | 27.8 | -5.05 | 0.60 | 6.55 | -0.280 | −EV |
| USDJPY | H1+H4 | A | 1:1 | 10 | 60.0 | +2.00 | 1.50 | 2.00 | 0.200 | hits ~50%+ WR and +EV |
| USDJPY | H1+H4 | A | 1:1.5 | 10 | 60.0 | +5.00 | 2.25 | 2.00 | 0.500 | hits ~50%+ WR and +EV |
| USDJPY | H1+H4 | B | 1:1 | 8 | 50.0 | +0.00 | 1.00 | 2.00 | 0.000 | hits ~50%+ WR but −EV |
| USDJPY | H1+H4 | B | 1:1.5 | 5 | 40.0 | +0.00 | 1.00 | 2.00 | 0.000 | thin |
| USDJPY | H1+H4 | C | 1:1 | 11 | 54.5 | +1.00 | 1.20 | 3.00 | 0.091 | hits ~50%+ WR and +EV |
| USDJPY | H1+H4 | C | 1:1.5 | 11 | 54.5 | +4.00 | 1.80 | 3.00 | 0.364 | hits ~50%+ WR and +EV |

## Cells with WR ≥ 50% (n ≥ 8)

| symbol | stack | entry | RR | n | WR% | sumR | PF | maxDD | avgR | note |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | M5+M45 | A | 1:1 | 79 | 53.2 | +5.00 | 1.14 | 6.00 | 0.063 | hits ~50%+ WR and +EV |
| XAU | M5+M45 | B | 1:1 | 40 | 57.5 | +6.71 | 1.41 | 3.00 | 0.168 | hits ~50%+ WR and +EV |
| XAU | M5+M45 | B | 1:1.5 | 37 | 56.8 | +16.21 | 2.06 | 2.00 | 0.438 | hits ~50%+ WR and +EV |
| XAU | M5+M45 | C | 1:1 | 83 | 53.0 | +5.00 | 1.13 | 7.00 | 0.060 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | A | 1:1 | 116 | 55.2 | +11.10 | 1.21 | 9.00 | 0.096 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | B | 1:1 | 45 | 60.0 | +9.96 | 1.58 | 4.00 | 0.221 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | B | 1:1.5 | 33 | 57.6 | +15.46 | 2.18 | 3.50 | 0.468 | hits ~50%+ WR and +EV |
| BTC | M5+M45 | C | 1:1 | 118 | 55.1 | +11.10 | 1.21 | 10.00 | 0.094 | hits ~50%+ WR and +EV |
| US100 | M5+M45 | A | 1:1 | 84 | 54.8 | +8.00 | 1.21 | 9.00 | 0.095 | hits ~50%+ WR and +EV |
| US100 | M5+M45 | B | 1:1 | 50 | 52.0 | +2.00 | 1.08 | 5.00 | 0.040 | hits ~50%+ WR and +EV |
| US100 | M5+M45 | C | 1:1 | 91 | 51.6 | +3.00 | 1.07 | 10.00 | 0.033 | hits ~50%+ WR and +EV |
| US500 | M5+M45 | A | 1:1 | 70 | 51.4 | +2.00 | 1.06 | 9.00 | 0.029 | hits ~50%+ WR and +EV |
| US500 | M5+M45 | C | 1:1 | 75 | 50.7 | +1.00 | 1.03 | 8.00 | 0.013 | marginal +EV |
| US30 | M5+M45 | A | 1:1 | 96 | 54.2 | +8.00 | 1.18 | 15.00 | 0.083 | hits ~50%+ WR and +EV |
| US30 | M5+M45 | C | 1:1 | 99 | 53.5 | +7.00 | 1.15 | 16.00 | 0.071 | hits ~50%+ WR and +EV |
| EURUSD | M5+M45 | A | 1:1 | 82 | 50.0 | +0.46 | 1.01 | 14.00 | 0.006 | marginal +EV |
| EURUSD | M5+M45 | B | 1:1 | 49 | 61.2 | +11.00 | 1.58 | 3.00 | 0.224 | hits ~50%+ WR and +EV |
| EURUSD | M5+M45 | C | 1:1 | 82 | 50.0 | +0.46 | 1.01 | 14.00 | 0.006 | marginal +EV |
| XAG | M5+M45 | B | 1:1 | 41 | 53.7 | +3.56 | 1.19 | 5.00 | 0.087 | hits ~50%+ WR and +EV |
| J225 | M5+M45 | A | 1:1 | 90 | 50.0 | -0.00 | 1.00 | 11.00 | -0.000 | hits ~50%+ WR but −EV |
| J225 | M5+M45 | C | 1:1 | 91 | 50.5 | +1.00 | 1.02 | 11.00 | 0.011 | marginal +EV |
| USDJPY | M5+M45 | B | 1:1 | 50 | 50.0 | +0.75 | 1.03 | 5.00 | 0.015 | marginal +EV |
| USDJPY | M5+M45 | B | 1:1.5 | 42 | 50.0 | +11.25 | 1.56 | 4.00 | 0.268 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | A | 1:1 | 44 | 52.3 | +2.00 | 1.10 | 6.00 | 0.045 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | B | 1:1 | 33 | 69.7 | +13.74 | 2.48 | 2.00 | 0.416 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | B | 1:1.5 | 25 | 64.0 | +13.85 | 2.54 | 2.00 | 0.554 | hits ~50%+ WR and +EV |
| XAU | M15+H1 | C | 1:1 | 49 | 51.0 | +1.37 | 1.06 | 6.00 | 0.028 | hits ~50%+ WR and +EV |
| BTC | M15+H1 | A | 1:1 | 77 | 51.9 | +2.03 | 1.05 | 11.00 | 0.026 | hits ~50%+ WR and +EV |
| BTC | M15+H1 | B | 1:1 | 37 | 59.5 | +7.23 | 1.49 | 5.00 | 0.195 | hits ~50%+ WR and +EV |
| BTC | M15+H1 | B | 1:1.5 | 29 | 51.7 | +7.92 | 1.57 | 5.00 | 0.273 | hits ~50%+ WR and +EV |
| BTC | M15+H1 | C | 1:1 | 90 | 50.0 | -0.97 | 0.98 | 13.00 | -0.011 | hits ~50%+ WR but −EV |
| US100 | M15+H1 | A | 1:1 | 52 | 61.5 | +12.00 | 1.60 | 3.00 | 0.231 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | A | 1:1.5 | 50 | 54.0 | +17.50 | 1.76 | 3.50 | 0.350 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | B | 1:1.5 | 32 | 53.1 | +10.50 | 1.70 | 3.50 | 0.328 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | C | 1:1 | 59 | 57.6 | +9.00 | 1.36 | 5.00 | 0.153 | hits ~50%+ WR and +EV |
| US100 | M15+H1 | C | 1:1.5 | 57 | 50.9 | +15.50 | 1.55 | 5.00 | 0.272 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | A | 1:1 | 45 | 57.8 | +7.00 | 1.37 | 5.00 | 0.156 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | A | 1:1.5 | 43 | 51.2 | +12.00 | 1.57 | 5.50 | 0.279 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | B | 1:1 | 36 | 55.6 | +4.00 | 1.25 | 5.00 | 0.111 | hits ~50%+ WR and +EV |
| US30 | M15+H1 | C | 1:1 | 55 | 54.5 | +5.00 | 1.20 | 10.00 | 0.091 | hits ~50%+ WR and +EV |
| EURUSD | M15+H1 | B | 1:1 | 40 | 55.0 | +4.83 | 1.28 | 4.00 | 0.121 | hits ~50%+ WR and +EV |
| EURUSD | M15+H1 | B | 1:1.5 | 32 | 56.2 | +13.83 | 2.05 | 3.00 | 0.432 | hits ~50%+ WR and +EV |
| XAG | M15+H1 | B | 1:1 | 35 | 54.3 | +2.18 | 1.14 | 6.00 | 0.062 | hits ~50%+ WR and +EV |
| J225 | M15+H1 | B | 1:1 | 43 | 51.2 | +0.01 | 1.00 | 7.00 | 0.000 | marginal +EV |
| USDJPY | M15+H1 | B | 1:1 | 40 | 55.0 | +4.59 | 1.26 | 5.00 | 0.115 | hits ~50%+ WR and +EV |
| XAU | H1+H4 | B | 1:1 | 13 | 53.8 | +1.00 | 1.17 | 3.00 | 0.077 | hits ~50%+ WR and +EV |
| XAU | H1+H4 | B | 1:1.5 | 10 | 50.0 | +1.50 | 1.30 | 3.00 | 0.150 | hits ~50%+ WR and +EV |
| BTC | H1+H4 | B | 1:1 | 14 | 50.0 | +0.00 | 1.00 | 2.00 | 0.000 | hits ~50%+ WR but −EV |
| US100 | H1+H4 | A | 1:1 | 17 | 52.9 | +1.00 | 1.12 | 4.00 | 0.059 | hits ~50%+ WR and +EV |
| US100 | H1+H4 | A | 1:1.5 | 15 | 53.3 | +5.00 | 1.71 | 3.00 | 0.333 | hits ~50%+ WR and +EV |
| US100 | H1+H4 | B | 1:1 | 13 | 53.8 | +0.04 | 1.01 | 3.00 | 0.003 | marginal +EV |
| US100 | H1+H4 | C | 1:1 | 18 | 50.0 | +0.00 | 1.00 | 5.00 | 0.000 | hits ~50%+ WR but −EV |
| US100 | H1+H4 | C | 1:1.5 | 16 | 50.0 | +4.00 | 1.50 | 4.00 | 0.250 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | A | 1:1 | 19 | 52.6 | +1.00 | 1.11 | 2.00 | 0.053 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | B | 1:1 | 13 | 53.8 | +1.00 | 1.17 | 3.00 | 0.077 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | B | 1:1.5 | 8 | 50.0 | +1.16 | 1.29 | 2.00 | 0.145 | hits ~50%+ WR and +EV |
| US500 | H1+H4 | C | 1:1 | 20 | 50.0 | +0.00 | 1.00 | 2.00 | 0.000 | hits ~50%+ WR but −EV |
| US30 | H1+H4 | B | 1:1 | 11 | 72.7 | +4.01 | 2.34 | 1.00 | 0.364 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | A | 1:1 | 10 | 70.0 | +4.00 | 2.33 | 1.00 | 0.400 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | B | 1:1 | 11 | 63.6 | +2.09 | 1.52 | 2.00 | 0.190 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | B | 1:1.5 | 8 | 62.5 | +3.41 | 2.14 | 1.00 | 0.426 | hits ~50%+ WR and +EV |
| GER40 | H1+H4 | C | 1:1 | 12 | 66.7 | +4.00 | 2.00 | 2.00 | 0.333 | hits ~50%+ WR and +EV |
| XAG | H1+H4 | A | 1:1 | 11 | 63.6 | +3.00 | 1.75 | 2.00 | 0.273 | hits ~50%+ WR and +EV |
| XAG | H1+H4 | B | 1:1 | 12 | 58.3 | +1.04 | 1.21 | 4.00 | 0.087 | hits ~50%+ WR and +EV |
| XAG | H1+H4 | C | 1:1 | 13 | 61.5 | +3.00 | 1.60 | 2.00 | 0.231 | hits ~50%+ WR and +EV |
| J225 | H1+H4 | B | 1:1 | 11 | 54.5 | +1.99 | 1.50 | 2.01 | 0.181 | hits ~50%+ WR and +EV |
| USDJPY | H1+H4 | A | 1:1 | 10 | 60.0 | +2.00 | 1.50 | 2.00 | 0.200 | hits ~50%+ WR and +EV |
| USDJPY | H1+H4 | A | 1:1.5 | 10 | 60.0 | +5.00 | 2.25 | 2.00 | 0.500 | hits ~50%+ WR and +EV |
| USDJPY | H1+H4 | B | 1:1 | 8 | 50.0 | +0.00 | 1.00 | 2.00 | 0.000 | hits ~50%+ WR but −EV |
| USDJPY | H1+H4 | C | 1:1 | 11 | 54.5 | +1.00 | 1.20 | 3.00 | 0.091 | hits ~50%+ WR and +EV |
| USDJPY | H1+H4 | C | 1:1.5 | 11 | 54.5 | +4.00 | 1.80 | 3.00 | 0.364 | hits ~50%+ WR and +EV |

## Book totals (summed across loaded symbols)

| stack | entry | RR | n | WR% | sumR | PF | maxDD | avgR | mode |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| M5+M45 | A | 1:1 | 845 | 50.9 | +16.51 | 1.04 | 35.00 | 0.020 | Band-cross + ST bias (ST_V3 entry) |
| M5+M45 | A | 1:1.5 | 763 | 40.8 | +14.38 | 1.03 | 37.50 | 0.019 | Band-cross + ST bias (ST_V3 entry) |
| M5+M45 | B | 1:1 | 474 | 51.1 | +14.05 | 1.06 | 15.00 | 0.030 | Supertrend flip only |
| M5+M45 | B | 1:1.5 | 388 | 45.4 | +53.49 | 1.26 | 13.00 | 0.138 | Supertrend flip only |
| M5+M45 | C | 1:1 | 872 | 50.3 | +7.51 | 1.02 | 44.00 | 0.009 | Band-cross, no ST-agree filter |
| M5+M45 | C | 1:1.5 | 784 | 40.3 | +5.88 | 1.01 | 50.50 | 0.008 | Band-cross, no ST-agree filter |
| M15+H1 | A | 1:1 | 523 | 49.9 | -0.38 | 1.00 | 36.00 | -0.001 | Band-cross + ST bias (ST_V3 entry) |
| M15+H1 | A | 1:1.5 | 494 | 41.1 | +12.62 | 1.04 | 41.50 | 0.026 | Band-cross + ST bias (ST_V3 entry) |
| M15+H1 | B | 1:1 | 386 | 52.3 | +19.07 | 1.11 | 24.00 | 0.049 | Supertrend flip only |
| M15+H1 | B | 1:1.5 | 328 | 43.3 | +27.42 | 1.15 | 20.50 | 0.084 | Supertrend flip only |
| M15+H1 | C | 1:1 | 587 | 47.5 | -28.01 | 0.91 | 51.00 | -0.048 | Band-cross, no ST-agree filter |
| M15+H1 | C | 1:1.5 | 555 | 39.8 | -3.02 | 0.99 | 51.50 | -0.005 | Band-cross, no ST-agree filter |
| H1+H4 | A | 1:1 | 144 | 47.9 | -5.91 | 0.92 | 12.00 | -0.041 | Band-cross + ST bias (ST_V3 entry) |
| H1+H4 | A | 1:1.5 | 137 | 38.0 | -6.96 | 0.92 | 14.76 | -0.051 | Band-cross + ST bias (ST_V3 entry) |
| H1+H4 | B | 1:1 | 119 | 55.5 | +10.97 | 1.21 | 7.00 | 0.092 | Supertrend flip only |
| H1+H4 | B | 1:1.5 | 79 | 46.8 | +10.36 | 1.26 | 8.18 | 0.131 | Supertrend flip only |
| H1+H4 | C | 1:1 | 164 | 45.7 | -13.91 | 0.84 | 18.21 | -0.085 | Band-cross, no ST-agree filter |
| H1+H4 | C | 1:1.5 | 157 | 36.9 | -11.96 | 0.88 | 20.76 | -0.076 | Band-cross, no ST-agree filter |

## Prod-like baseline — half@1:2 + BE + band-cross runner

M15+H1 only. **Required compare rows are XAU and US100.** Other symbols are extra context.

| symbol | stack | mode | n | WR% | sumR | PF | maxDD | avgR | note |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | M15+H1 | prod-like | 26 | 42.3 | +11.85 | 1.79 | 4.00 | 0.456 | required |
| BTC | M15+H1 | prod-like | 49 | 32.7 | -13.58 | 0.59 | 14.12 | -0.277 | extra |
| US100 | M15+H1 | prod-like | 38 | 36.8 | -4.99 | 0.79 | 6.99 | -0.131 | required |
| US500 | M15+H1 | prod-like | 29 | 34.5 | -1.35 | 0.93 | 14.00 | -0.047 | extra |
| US30 | M15+H1 | prod-like | 31 | 35.5 | -1.61 | 0.92 | 9.06 | -0.052 | extra |
| GER40 | M15+H1 | prod-like | 41 | 26.8 | -12.69 | 0.57 | 11.69 | -0.310 | extra |
| EURUSD | M15+H1 | prod-like | 32 | 34.4 | +7.33 | 1.35 | 6.00 | 0.229 | extra |
| XAG | M15+H1 | prod-like | 33 | 27.3 | -4.15 | 0.82 | 10.89 | -0.126 | extra |
| J225 | M15+H1 | prod-like | 36 | 30.6 | -8.67 | 0.65 | 12.74 | -0.241 | extra |
| USDJPY | M15+H1 | prod-like | 34 | 32.4 | -6.24 | 0.72 | 8.00 | -0.183 | extra |

## Required compare — XAU + US100, M15+H1 (same entries, different management)

Prod-like holds the runner, so **n is lower** (flat book blocked). Fixed RR frees the slot at +1R / +1.5R.

| mode | n | WR% | sumR | PF | note |
| --- | ---: | ---: | ---: | ---: | --- |
| prod-like (XAU) | 26 | 42.3 | +11.85 | 1.79 | required |
| prod-like (US100) | 38 | 36.8 | −4.99 | 0.79 | required |
| **prod-like (XAU+US100)** | **64** | **39.1** | **+6.86** | — | required total |
| A 1:1 (XAU) | 44 | 52.3 | +2.00 | 1.10 | |
| A 1:1 (US100) | 52 | 61.5 | +12.00 | 1.60 | |
| **A 1:1 (XAU+US100)** | **96** | **57.3** | **+14.00** | — | beats prod WR and sumR |
| A 1:1.5 (XAU) | 43 | 46.5 | +7.00 | 1.30 | |
| A 1:1.5 (US100) | 50 | 54.0 | +17.50 | 1.76 | |
| **A 1:1.5 (XAU+US100)** | **93** | **50.5** | **+24.50** | — | best of the three on sumR |

Book-wide prod-like (all 10 names, M15+H1) is **−34.1R / WR ~33% / n=349** this quarter — only XAU (+11.9R) and EURUSD (+7.3R) print +EV. The runner is not paying for the occupancy this window.

## Hypothesis check

Pre-registered: *1:1 may lift WR toward ~45–55% but cut expectancy; band-cross+ST bias likely beats ST-flip-only.*

- **M5+M45 A 1:1:** WR 50.9% / +16.5R (n=845); A 1:1.5: WR 40.8% / +14.4R (n=763).
  A vs B at 1:1: band-cross+ST beats ST-flip (B WR 51.1% / +14.0R, n=474).
  A vs C at 1:1: C n=872 WR 50.3% / +7.5R — not identical to A.
- **M15+H1 A 1:1:** WR 49.9% / -0.4R (n=523); A 1:1.5: WR 41.1% / +12.6R (n=494).
  A vs B at 1:1: band-cross+ST does not beat ST-flip (B WR 52.3% / +19.1R, n=386).
  A vs C at 1:1: C n=587 WR 47.5% / -28.0R — not identical to A.
- **H1+H4 A 1:1:** WR 47.9% / -5.9R (n=144); A 1:1.5: WR 38.0% / -7.0R (n=137).
  A vs B at 1:1: band-cross+ST does not beat ST-flip (B WR 55.5% / +11.0R, n=119).
  A vs C at 1:1: C n=164 WR 45.7% / -13.9R — not identical to A.

- **WR lift toward 45–55% at 1:1 (M15+H1 A):** **yes** (book WR 49.9%; XAU+US100 57.3%).
- **Expectancy cut vs prod-like on the required XAU+US100 M15+H1 pair:** **no this quarter.** A 1:1 is +14.0R and A 1:1.5 is +24.5R vs prod-like +6.9R. The pre-registered “cut expectancy” line is true only if you compare **book-wide A 1:1 (−0.4R)** to **two-name prod-like (+6.9R)** — that mix is the wrong apples.
- **Band-cross+ST bias beats ST-flip-only:** **rejected on M15+H1 and H1+H4.** B prints higher book WR and sumR than A on both of those stacks (M15 B 1:1 +19.1R / 52.3% vs A −0.4R / 49.9%; H1 B 1:1 +11.0R / 55.5% vs A −5.9R). A only edges B on M5+M45 1:1 (+16.5R vs +14.1R). XAU M15 B is the standout cell (1:1 +13.7R / 69.7% WR, n=33).
- **C vs A:** not identical. Extra against-bias band-crosses that still have a protective closed-ST line leak through; they **hurt** M15 (C 1:1 −28R vs A −0.4R). Keep the ST agree filter.

## Call

**Do not deploy fixed 1:1 or 1:1.5 as a live Java default.** Keep current ST_V3 management (`half @ 1:2 → BE → band-cross runner`) on prod.

This 90-day window is **friendly to fixed RR** — especially **M15+H1 A 1:1.5 on XAU/US100/US30** and **M15+H1 B (ST-flip) on XAU** — and **hostile to the prod runner** (US100 prod-like −5.0R while A 1:1.5 is +17.5R on the same name; book-wide prod-like −34R). That is one quarter, HistData last print is **2026-09-04** (BTC through 2026-09-13, US30 through 2026-09-11), and the runner’s job is to harvest the tail a 90-day sample can miss. The 12-month IS/OOS that locked ST_V3 (PF 2.10 / 1.65 on M15) was the half+BE+band-cross book.

Practical:

- **Live:** no Java change. `ST_V3_TP1_MULT = 2.0`, band-cross runner, satellite universes stay.
- **If Adam wants a simpler ticket to paper:** M15+H1 **A 1:1.5** on XAU / US100 / US30 (WR 46–54%, +7 / +17.5 / +12R). 1:1 is the WR cosmetic (~50–62% on those three) but gives back expectancy vs 1:1.5.
- **Do not** turn on C (no ST agree). **Do not** flip the live book to ST-flip-only on one quarter, even though B won the M15/H1 books here.
- **H1+H4** stays a thin satellite (n≈10–20 per name); not a deploy surface.

- Do **not** merge this note as a live switch.

## How to rerun

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_fixed_rr_q
```

Flags: `--symbols XAU,US100,BTC` · `--no-extra` · `--no-h1` · `--no-c`.
Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).

