# SATS v1.13.1 Default vs ST_V3 prod-like — 12-month research bakeoff

Research only. **Do not merge into prod.** Does **not** change Java / `StV3Engine`.

## Window and data

- **Entries:** 2025-09-13 → 2026-09-13 (365 calendar days). Warmup from 2025-05-16.
- **SATS:** chart TF = entry TF (M15 and M5 independently). TQI ON, asymmetric ON, eff-ATR ON, char-flip ON, Fixed TP 1/2/3R thirds, SL = pivot ± 1.5 ATR then capped at 4.0 ATR, timeout 100 bars, flip-exit on.
- **ST_V3 prod-like:** band-cross + HTF ST bias (ATR 7 / factor 2.0), SL = ST line, half@1:2 → BE → entry-TF band-cross runner, cap 2, flat, structure gate ON. Stacks M15+H1 and M5+M45.
- **Sources:** same loader as `ha_hunt_st_compare` / PR #151/#152 (local M5 cache → Capital DEMO if creds → HistData M1→M5 → Dukascopy M1→M5 for US30 → Coinbase 5m for BTC). No invented prices. No spread/commission.

## Call

**Adopt nothing. Do not replace ST_V3 with SATS. Do not change prod Java.**

SATS Default is a **negative-edge flip mill** on this book: M15 **−155R / PF 0.97 / 14.3k trades / DD 286R**, M5 **−419R / PF 0.98 / 44.8k trades / DD 483R**. ~70% of SATS exits are the next opposite flip. ST_V3 prod-like on the same window is **M15+H1 +96.4R / PF 1.16 / n=952 / DD 39R** and **M5+M45 +108.4R / PF 1.11 / n=1600 / DD 51R** (matches PR #152’s PRIMARY7 prod-like ± a few R).

Grafted SATS pieces:

- **TQI≥0.5 gate — rejected.** M15+H1 +96R → **−22R** (n 952→243). It deletes the XAU star (+45R → +4R). M5+M45 +108R → +60R. Do not filter ST_V3 band-cross with chart-TF TQI.
- **Char-flip instead of the band runner — do not merge.** Book sumR rises (+96→+111 M15, +108→+133 M5) only because earlier flattens free the cap-2 slot and mint extra tickets (n +42% / +66%). PF is flat-to-worse, avgR drops, and the **lock names get worse**: XAU M15 +44.6R / PF 1.73 → +37.7R / PF 1.45; GER40 M5 +26R / PF 1.20 → **−2.9R / PF 0.98**. Char-flip + band is the same shape. Not an OOS-ready runner swap.
- **Asymmetric / TQI-modulated ST width** and **dynamic TP** were not grafted. Dynamic TP already lost the 12m fixed-RR bakeoff (PR #152). Keep ST ATR 7 / factor 2.0 and the half@1:2 → BE → band-cross runner.

**Paper nothing this round.** SATS is a coherent SuperTrend overlay for discretionary TQI reading, not a bot to deploy next to ST_V3.

## Coverage

| symbol | source | n_m5 | first | last | days | note |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| XAU | histdata_m1_resampled_m5 | 94032 | 2025-05-16T04:00:00 | 2026-09-11T20:55:00 | 483.7 | HistData XAUUSD (spot gold). Same series as PR #139/#143. |
| BTC | coinbase_exchange_m5 | 139532 | 2025-05-16T00:00:00 | 2026-09-13T00:00:00 | 485.0 | Coinbase Exchange BTC-USD 5m public candles. HistData has no BTC. |
| US100 | histdata_m1_resampled_m5 | 90710 | 2025-05-16T04:00:00 | 2026-09-11T20:10:00 | 483.7 | HistData NSXUSD = Nasdaq 100 cash (NQ / US100). Capital epic US100. Same series  |
| US500 | histdata_m1_resampled_m5 | 90760 | 2025-05-16T04:00:00 | 2026-09-11T20:10:00 | 483.7 | HistData SPXUSD = S&P 500 cash (ES / US500). Capital epic US500. Analogous vendo |
| US30 | dukascopy_m1_resampled_m5 | 90844 | 2025-05-16T00:00:00 | 2026-09-11T20:10:00 | 483.8 | HistData has no DJIA pair. Dukascopy USA30.IDX/USD = Dow Jones cash (YM / US30). |
| GER40 | histdata_m1_resampled_m5 | 91083 | 2025-05-16T04:00:00 | 2026-09-11T19:55:00 | 483.7 | HistData GRXEUR = DAX cash (DE40 / GER40). Dukascopy DEU.IDX/EUR fallback. |
| EURUSD | histdata_m1_resampled_m5 | 98947 | 2025-05-16T04:00:00 | 2026-09-11T20:55:00 | 483.7 | HistData EURUSD spot. Same vendor as prior FX bakeoffs. |

## PRIMARY7 totals

| symbol | stack | n | WR% | sumR | PF | maxDD_R | avgR | note |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| PRIMARY7 | SATS M15 chart-TF | 14271 | 37.9 | -155.34 | 0.97 | 286.41 | -0.011 |  |
| PRIMARY7 | SATS M5 chart-TF | 44765 | 38.6 | -419.06 | 0.98 | 483.11 | -0.009 |  |
| PRIMARY7 | ST_V3 prod M15+H1 | 952 | 36.9 | +96.40 | 1.16 | 39.11 | 0.101 |  |
| PRIMARY7 | ST_V3 prod M5+M45 | 1600 | 36.4 | +108.36 | 1.11 | 51.31 | 0.068 |  |
| PRIMARY7 | ST_V3 M15+H1 TQI≥0.5 gate | 243 | 31.7 | -21.97 | 0.87 | 37.06 | -0.090 |  |
| PRIMARY7 | ST_V3 M5+M45 TQI≥0.5 gate | 741 | 36.2 | +59.96 | 1.13 | 43.12 | 0.081 |  |
| PRIMARY7 | ST_V3 M15+H1 char-flip instead of band runner | 1352 | 39.3 | +111.08 | 1.15 | 45.29 | 0.082 |  |
| PRIMARY7 | ST_V3 M5+M45 char-flip instead of band runner | 2657 | 44.8 | +132.85 | 1.12 | 56.68 | 0.050 |  |
| PRIMARY7 | ST_V3 M15+H1 char-flip + band runner | 1364 | 39.3 | +110.29 | 1.15 | 45.29 | 0.081 |  |
| PRIMARY7 | ST_V3 M5+M45 char-flip + band runner | 2662 | 44.8 | +126.33 | 1.11 | 56.79 | 0.047 |  |

## Side-by-side — SATS vs ST_V3 prod (same TF ladder)

SATS M15 is chart-TF only (no H1 stack). ST_V3 M15+H1 is the locked prod pairing. Same for M5 vs M5+M45.

| symbol | stack | n | WR% | sumR | PF | maxDD_R | avgR | note |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | SATS M15 chart-TF | 1900 | 37.8 | -2.10 | 1.00 | 40.74 | -0.001 |  |
| XAU | SATS M5 chart-TF | 5760 | 39.4 | +64.06 | 1.03 | 42.14 | 0.011 |  |
| XAU | ST_V3 prod M15+H1 | 102 | 40.2 | +44.62 | 1.73 | 7.80 | 0.437 |  |
| XAU | ST_V3 prod M5+M45 | 203 | 36.0 | +22.55 | 1.17 | 13.00 | 0.111 |  |
| XAU | winner M15-ish | 102 | 40.2 | +44.62 | 1.73 | 7.80 | 0.437 | STV3_prod_m15_st60 |
| XAU | winner M5-ish | 5760 | 39.4 | +64.06 | 1.03 | 42.14 | 0.011 | SATS_Default_M5 |
| BTC | SATS M15 chart-TF | 2862 | 36.5 | -42.34 | 0.96 | 65.51 | -0.015 |  |
| BTC | SATS M5 chart-TF | 9278 | 37.9 | -160.75 | 0.95 | 177.18 | -0.017 |  |
| BTC | ST_V3 prod M15+H1 | 187 | 34.2 | +4.58 | 1.04 | 19.76 | 0.024 |  |
| BTC | ST_V3 prod M5+M45 | 314 | 33.8 | +10.78 | 1.05 | 28.48 | 0.034 |  |
| BTC | winner M15-ish | 187 | 34.2 | +4.58 | 1.04 | 19.76 | 0.024 | STV3_prod_m15_st60 |
| BTC | winner M5-ish | 314 | 33.8 | +10.78 | 1.05 | 28.48 | 0.034 | STV3_prod_m5_st45 |
| US100 | SATS M15 chart-TF | 1838 | 39.2 | +27.17 | 1.04 | 38.17 | 0.015 |  |
| US100 | SATS M5 chart-TF | 5708 | 39.4 | +33.67 | 1.02 | 55.93 | 0.006 |  |
| US100 | ST_V3 prod M15+H1 | 128 | 42.2 | +21.84 | 1.30 | 8.00 | 0.171 |  |
| US100 | ST_V3 prod M5+M45 | 234 | 35.5 | +11.31 | 1.08 | 21.13 | 0.048 |  |
| US100 | winner M15-ish | 1838 | 39.2 | +27.17 | 1.04 | 38.17 | 0.015 | SATS_Default_M15 |
| US100 | winner M5-ish | 5708 | 39.4 | +33.67 | 1.02 | 55.93 | 0.006 | SATS_Default_M5 |
| US500 | SATS M15 chart-TF | 1930 | 35.9 | -97.36 | 0.88 | 119.54 | -0.050 |  |
| US500 | SATS M5 chart-TF | 5792 | 39.4 | -30.69 | 0.99 | 98.94 | -0.005 |  |
| US500 | ST_V3 prod M15+H1 | 129 | 39.5 | +8.76 | 1.11 | 14.07 | 0.068 |  |
| US500 | ST_V3 prod M5+M45 | 190 | 42.1 | +35.69 | 1.33 | 11.76 | 0.188 |  |
| US500 | winner M15-ish | 129 | 39.5 | +8.76 | 1.11 | 14.07 | 0.068 | STV3_prod_m15_st60 |
| US500 | winner M5-ish | 190 | 42.1 | +35.69 | 1.33 | 11.76 | 0.188 | STV3_prod_m5_st45 |
| US30 | SATS M15 chart-TF | 1811 | 38.7 | -23.83 | 0.97 | 67.27 | -0.013 |  |
| US30 | SATS M5 chart-TF | 5710 | 39.5 | +49.14 | 1.02 | 83.05 | 0.009 |  |
| US30 | ST_V3 prod M15+H1 | 130 | 36.2 | +9.61 | 1.12 | 19.88 | 0.074 |  |
| US30 | ST_V3 prod M5+M45 | 223 | 38.6 | +12.46 | 1.09 | 28.42 | 0.056 |  |
| US30 | winner M15-ish | 130 | 36.2 | +9.61 | 1.12 | 19.88 | 0.074 | STV3_prod_m15_st60 |
| US30 | winner M5-ish | 5710 | 39.5 | +49.14 | 1.02 | 83.05 | 0.009 | SATS_Default_M5 |
| GER40 | SATS M15 chart-TF | 1911 | 39.0 | +8.58 | 1.01 | 39.36 | 0.004 |  |
| GER40 | SATS M5 chart-TF | 5874 | 39.1 | +14.87 | 1.01 | 82.90 | 0.003 |  |
| GER40 | ST_V3 prod M15+H1 | 140 | 34.3 | -13.73 | 0.85 | 19.30 | -0.098 |  |
| GER40 | ST_V3 prod M5+M45 | 208 | 38.0 | +26.02 | 1.20 | 16.77 | 0.125 |  |
| GER40 | winner M15-ish | 1911 | 39.0 | +8.58 | 1.01 | 39.36 | 0.004 | SATS_Default_M15 |
| GER40 | winner M5-ish | 208 | 38.0 | +26.02 | 1.20 | 16.77 | 0.125 | STV3_prod_m5_st45 |
| EURUSD | SATS M15 chart-TF | 2019 | 38.6 | -25.46 | 0.97 | 61.55 | -0.013 |  |
| EURUSD | SATS M5 chart-TF | 6643 | 36.3 | -389.37 | 0.85 | 399.91 | -0.059 |  |
| EURUSD | ST_V3 prod M15+H1 | 136 | 33.8 | +20.72 | 1.23 | 20.48 | 0.152 |  |
| EURUSD | ST_V3 prod M5+M45 | 228 | 32.9 | -10.44 | 0.93 | 30.39 | -0.046 |  |
| EURUSD | winner M15-ish | 136 | 33.8 | +20.72 | 1.23 | 20.48 | 0.152 | STV3_prod_m15_st60 |
| EURUSD | winner M5-ish | 228 | 32.9 | -10.44 | 0.93 | 30.39 | -0.046 | STV3_prod_m5_st45 |

## Per-symbol — every cell

| symbol | stack | n | WR% | sumR | PF | maxDD_R | avgR | note |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| XAU | SATS M15 chart-TF | 1900 | 37.8 | -2.10 | 1.00 | 40.74 | -0.001 |  |
| XAU | SATS M5 chart-TF | 5760 | 39.4 | +64.06 | 1.03 | 42.14 | 0.011 |  |
| XAU | ST_V3 prod M15+H1 | 102 | 40.2 | +44.62 | 1.73 | 7.80 | 0.437 |  |
| XAU | ST_V3 prod M5+M45 | 203 | 36.0 | +22.55 | 1.17 | 13.00 | 0.111 |  |
| XAU | ST_V3 M15+H1 TQI≥0.5 gate | 25 | 32.0 | +4.34 | 1.26 | 6.78 | 0.173 |  |
| XAU | ST_V3 M5+M45 TQI≥0.5 gate | 105 | 38.1 | +12.86 | 1.20 | 9.08 | 0.122 |  |
| XAU | ST_V3 M15+H1 char-flip instead of band runner | 164 | 42.7 | +37.72 | 1.45 | 10.44 | 0.230 |  |
| XAU | ST_V3 M5+M45 char-flip instead of band runner | 317 | 47.9 | +37.95 | 1.29 | 12.47 | 0.120 |  |
| XAU | ST_V3 M15+H1 char-flip + band runner | 171 | 41.5 | +32.14 | 1.36 | 10.44 | 0.188 |  |
| XAU | ST_V3 M5+M45 char-flip + band runner | 321 | 47.7 | +35.22 | 1.27 | 12.47 | 0.110 |  |
| BTC | SATS M15 chart-TF | 2862 | 36.5 | -42.34 | 0.96 | 65.51 | -0.015 |  |
| BTC | SATS M5 chart-TF | 9278 | 37.9 | -160.75 | 0.95 | 177.18 | -0.017 |  |
| BTC | ST_V3 prod M15+H1 | 187 | 34.2 | +4.58 | 1.04 | 19.76 | 0.024 |  |
| BTC | ST_V3 prod M5+M45 | 314 | 33.8 | +10.78 | 1.05 | 28.48 | 0.034 |  |
| BTC | ST_V3 M15+H1 TQI≥0.5 gate | 51 | 27.5 | -3.59 | 0.90 | 12.00 | -0.070 |  |
| BTC | ST_V3 M5+M45 TQI≥0.5 gate | 155 | 33.5 | +9.18 | 1.09 | 18.36 | 0.059 |  |
| BTC | ST_V3 M15+H1 char-flip instead of band runner | 260 | 38.8 | +15.70 | 1.11 | 23.63 | 0.060 |  |
| BTC | ST_V3 M5+M45 char-flip instead of band runner | 509 | 46.2 | +33.21 | 1.16 | 36.91 | 0.065 |  |
| BTC | ST_V3 M15+H1 char-flip + band runner | 262 | 38.5 | +15.34 | 1.10 | 24.54 | 0.059 |  |
| BTC | ST_V3 M5+M45 char-flip + band runner | 510 | 46.1 | +30.04 | 1.14 | 36.98 | 0.059 |  |
| US100 | SATS M15 chart-TF | 1838 | 39.2 | +27.17 | 1.04 | 38.17 | 0.015 |  |
| US100 | SATS M5 chart-TF | 5708 | 39.4 | +33.67 | 1.02 | 55.93 | 0.006 |  |
| US100 | ST_V3 prod M15+H1 | 128 | 42.2 | +21.84 | 1.30 | 8.00 | 0.171 |  |
| US100 | ST_V3 prod M5+M45 | 234 | 35.5 | +11.31 | 1.08 | 21.13 | 0.048 |  |
| US100 | ST_V3 M15+H1 TQI≥0.5 gate | 34 | 32.4 | +3.96 | 1.17 | 8.50 | 0.116 |  |
| US100 | ST_V3 M5+M45 TQI≥0.5 gate | 90 | 35.6 | +1.78 | 1.03 | 11.73 | 0.020 |  |
| US100 | ST_V3 M15+H1 char-flip instead of band runner | 174 | 40.2 | +26.17 | 1.28 | 7.50 | 0.150 |  |
| US100 | ST_V3 M5+M45 char-flip instead of band runner | 365 | 46.3 | +34.63 | 1.23 | 15.69 | 0.095 |  |
| US100 | ST_V3 M15+H1 char-flip + band runner | 174 | 40.2 | +26.71 | 1.28 | 7.50 | 0.153 |  |
| US100 | ST_V3 M5+M45 char-flip + band runner | 365 | 46.3 | +34.35 | 1.23 | 15.69 | 0.094 |  |
| US500 | SATS M15 chart-TF | 1930 | 35.9 | -97.36 | 0.88 | 119.54 | -0.050 |  |
| US500 | SATS M5 chart-TF | 5792 | 39.4 | -30.69 | 0.99 | 98.94 | -0.005 |  |
| US500 | ST_V3 prod M15+H1 | 129 | 39.5 | +8.76 | 1.11 | 14.07 | 0.068 |  |
| US500 | ST_V3 prod M5+M45 | 190 | 42.1 | +35.69 | 1.33 | 11.76 | 0.188 |  |
| US500 | ST_V3 M15+H1 TQI≥0.5 gate | 39 | 28.2 | -15.97 | 0.43 | 16.27 | -0.409 |  |
| US500 | ST_V3 M5+M45 TQI≥0.5 gate | 84 | 42.9 | +17.24 | 1.36 | 8.36 | 0.205 |  |
| US500 | ST_V3 M15+H1 char-flip instead of band runner | 187 | 35.8 | -1.06 | 0.99 | 22.12 | -0.006 |  |
| US500 | ST_V3 M5+M45 char-flip instead of band runner | 346 | 45.7 | +33.19 | 1.24 | 11.85 | 0.096 |  |
| US500 | ST_V3 M15+H1 char-flip + band runner | 187 | 36.4 | +3.61 | 1.03 | 22.08 | 0.019 |  |
| US500 | ST_V3 M5+M45 char-flip + band runner | 346 | 46.0 | +33.25 | 1.24 | 10.90 | 0.096 |  |
| US30 | SATS M15 chart-TF | 1811 | 38.7 | -23.83 | 0.97 | 67.27 | -0.013 |  |
| US30 | SATS M5 chart-TF | 5710 | 39.5 | +49.14 | 1.02 | 83.05 | 0.009 |  |
| US30 | ST_V3 prod M15+H1 | 130 | 36.2 | +9.61 | 1.12 | 19.88 | 0.074 |  |
| US30 | ST_V3 prod M5+M45 | 223 | 38.6 | +12.46 | 1.09 | 28.42 | 0.056 |  |
| US30 | ST_V3 M15+H1 TQI≥0.5 gate | 36 | 36.1 | -5.61 | 0.76 | 10.64 | -0.156 |  |
| US30 | ST_V3 M5+M45 TQI≥0.5 gate | 107 | 37.4 | +18.28 | 1.27 | 22.62 | 0.171 |  |
| US30 | ST_V3 M15+H1 char-flip instead of band runner | 180 | 41.1 | +21.01 | 1.22 | 17.11 | 0.117 |  |
| US30 | ST_V3 M5+M45 char-flip instead of band runner | 364 | 44.2 | +13.16 | 1.09 | 18.77 | 0.036 |  |
| US30 | ST_V3 M15+H1 char-flip + band runner | 183 | 41.5 | +19.10 | 1.20 | 20.73 | 0.104 |  |
| US30 | ST_V3 M5+M45 char-flip + band runner | 364 | 44.2 | +12.22 | 1.08 | 18.77 | 0.034 |  |
| GER40 | SATS M15 chart-TF | 1911 | 39.0 | +8.58 | 1.01 | 39.36 | 0.004 |  |
| GER40 | SATS M5 chart-TF | 5874 | 39.1 | +14.87 | 1.01 | 82.90 | 0.003 |  |
| GER40 | ST_V3 prod M15+H1 | 140 | 34.3 | -13.73 | 0.85 | 19.30 | -0.098 |  |
| GER40 | ST_V3 prod M5+M45 | 208 | 38.0 | +26.02 | 1.20 | 16.77 | 0.125 |  |
| GER40 | ST_V3 M15+H1 TQI≥0.5 gate | 30 | 33.3 | -2.57 | 0.87 | 13.44 | -0.086 |  |
| GER40 | ST_V3 M5+M45 TQI≥0.5 gate | 99 | 36.4 | +2.25 | 1.04 | 17.00 | 0.023 |  |
| GER40 | ST_V3 M15+H1 char-flip instead of band runner | 193 | 37.3 | -2.82 | 0.97 | 14.37 | -0.015 |  |
| GER40 | ST_V3 M5+M45 char-flip instead of band runner | 352 | 42.0 | -2.85 | 0.98 | 20.11 | -0.008 |  |
| GER40 | ST_V3 M15+H1 char-flip + band runner | 193 | 37.3 | -1.14 | 0.99 | 14.37 | -0.006 |  |
| GER40 | ST_V3 M5+M45 char-flip + band runner | 352 | 42.0 | -2.85 | 0.98 | 20.11 | -0.008 |  |
| EURUSD | SATS M15 chart-TF | 2019 | 38.6 | -25.46 | 0.97 | 61.55 | -0.013 |  |
| EURUSD | SATS M5 chart-TF | 6643 | 36.3 | -389.37 | 0.85 | 399.91 | -0.059 |  |
| EURUSD | ST_V3 prod M15+H1 | 136 | 33.8 | +20.72 | 1.23 | 20.48 | 0.152 |  |
| EURUSD | ST_V3 prod M5+M45 | 228 | 32.9 | -10.44 | 0.93 | 30.39 | -0.046 |  |
| EURUSD | ST_V3 M15+H1 TQI≥0.5 gate | 28 | 35.7 | -2.52 | 0.86 | 5.74 | -0.090 |  |
| EURUSD | ST_V3 M5+M45 TQI≥0.5 gate | 101 | 31.7 | -1.63 | 0.98 | 13.88 | -0.016 |  |
| EURUSD | ST_V3 M15+H1 char-flip instead of band runner | 194 | 40.2 | +14.35 | 1.14 | 25.43 | 0.074 |  |
| EURUSD | ST_V3 M5+M45 char-flip instead of band runner | 404 | 41.3 | -16.43 | 0.91 | 32.75 | -0.041 |  |
| EURUSD | ST_V3 M15+H1 char-flip + band runner | 194 | 40.2 | +14.54 | 1.14 | 25.24 | 0.075 |  |
| EURUSD | ST_V3 M5+M45 char-flip + band runner | 404 | 41.3 | -15.89 | 0.91 | 32.09 | -0.039 |  |

## Ablation — TQI≥0.5 gate on ST_V3 band-cross

| symbol | stack | n | WR% | sumR | PF | maxDD_R | avgR | note |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| PRIMARY7 | M15+H1 prod | 952 | 36.9 | +96.40 | 1.16 | 39.11 | 0.101 |  |
| PRIMARY7 | M15+H1 TQI≥0.5 | 243 | 31.7 | -21.97 | 0.87 | 37.06 | -0.090 | ΔsumR -118.36; n 952→243 |
| XAU | M15+H1 prod | 102 | 40.2 | +44.62 | 1.73 | 7.80 | 0.437 |  |
| XAU | M15+H1 TQI≥0.5 | 25 | 32.0 | +4.34 | 1.26 | 6.78 | 0.173 | ΔsumR -40.28 |
| BTC | M15+H1 prod | 187 | 34.2 | +4.58 | 1.04 | 19.76 | 0.024 |  |
| BTC | M15+H1 TQI≥0.5 | 51 | 27.5 | -3.59 | 0.90 | 12.00 | -0.070 | ΔsumR -8.17 |
| US100 | M15+H1 prod | 128 | 42.2 | +21.84 | 1.30 | 8.00 | 0.171 |  |
| US100 | M15+H1 TQI≥0.5 | 34 | 32.4 | +3.96 | 1.17 | 8.50 | 0.116 | ΔsumR -17.88 |
| US500 | M15+H1 prod | 129 | 39.5 | +8.76 | 1.11 | 14.07 | 0.068 |  |
| US500 | M15+H1 TQI≥0.5 | 39 | 28.2 | -15.97 | 0.43 | 16.27 | -0.409 | ΔsumR -24.72 |
| US30 | M15+H1 prod | 130 | 36.2 | +9.61 | 1.12 | 19.88 | 0.074 |  |
| US30 | M15+H1 TQI≥0.5 | 36 | 36.1 | -5.61 | 0.76 | 10.64 | -0.156 | ΔsumR -15.22 |
| GER40 | M15+H1 prod | 140 | 34.3 | -13.73 | 0.85 | 19.30 | -0.098 |  |
| GER40 | M15+H1 TQI≥0.5 | 30 | 33.3 | -2.57 | 0.87 | 13.44 | -0.086 | ΔsumR +11.16 |
| EURUSD | M15+H1 prod | 136 | 33.8 | +20.72 | 1.23 | 20.48 | 0.152 |  |
| EURUSD | M15+H1 TQI≥0.5 | 28 | 35.7 | -2.52 | 0.86 | 5.74 | -0.090 | ΔsumR -23.24 |
| PRIMARY7 | M5+M45 prod | 1600 | 36.4 | +108.36 | 1.11 | 51.31 | 0.068 |  |
| PRIMARY7 | M5+M45 TQI≥0.5 | 741 | 36.2 | +59.96 | 1.13 | 43.12 | 0.081 | ΔsumR -48.40; n 1600→741 |
| XAU | M5+M45 prod | 203 | 36.0 | +22.55 | 1.17 | 13.00 | 0.111 |  |
| XAU | M5+M45 TQI≥0.5 | 105 | 38.1 | +12.86 | 1.20 | 9.08 | 0.122 | ΔsumR -9.69 |
| BTC | M5+M45 prod | 314 | 33.8 | +10.78 | 1.05 | 28.48 | 0.034 |  |
| BTC | M5+M45 TQI≥0.5 | 155 | 33.5 | +9.18 | 1.09 | 18.36 | 0.059 | ΔsumR -1.60 |
| US100 | M5+M45 prod | 234 | 35.5 | +11.31 | 1.08 | 21.13 | 0.048 |  |
| US100 | M5+M45 TQI≥0.5 | 90 | 35.6 | +1.78 | 1.03 | 11.73 | 0.020 | ΔsumR -9.53 |
| US500 | M5+M45 prod | 190 | 42.1 | +35.69 | 1.33 | 11.76 | 0.188 |  |
| US500 | M5+M45 TQI≥0.5 | 84 | 42.9 | +17.24 | 1.36 | 8.36 | 0.205 | ΔsumR -18.45 |
| US30 | M5+M45 prod | 223 | 38.6 | +12.46 | 1.09 | 28.42 | 0.056 |  |
| US30 | M5+M45 TQI≥0.5 | 107 | 37.4 | +18.28 | 1.27 | 22.62 | 0.171 | ΔsumR +5.82 |
| GER40 | M5+M45 prod | 208 | 38.0 | +26.02 | 1.20 | 16.77 | 0.125 |  |
| GER40 | M5+M45 TQI≥0.5 | 99 | 36.4 | +2.25 | 1.04 | 17.00 | 0.023 | ΔsumR -23.77 |
| EURUSD | M5+M45 prod | 228 | 32.9 | -10.44 | 0.93 | 30.39 | -0.046 |  |
| EURUSD | M5+M45 TQI≥0.5 | 101 | 31.7 | -1.63 | 0.98 | 13.88 | -0.016 | ΔsumR +8.82 |

## Ablation — char-flip exit vs band-cross runner

| symbol | stack | n | WR% | sumR | PF | maxDD_R | avgR | note |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| PRIMARY7 | M15+H1 prod band-cross | 952 | 36.9 | +96.40 | 1.16 | 39.11 | 0.101 |  |
| PRIMARY7 | M15+H1 char-flip instead | 1352 | 39.3 | +111.08 | 1.15 | 45.29 | 0.082 |  |
| PRIMARY7 | M15+H1 char-flip + band | 1364 | 39.3 | +110.29 | 1.15 | 45.29 | 0.081 |  |
| XAU | M15+H1 prod | 102 | 40.2 | +44.62 | 1.73 | 7.80 | 0.437 |  |
| XAU | M15+H1 cf instead | 164 | 42.7 | +37.72 | 1.45 | 10.44 | 0.230 |  |
| XAU | M15+H1 cf + band | 171 | 41.5 | +32.14 | 1.36 | 10.44 | 0.188 |  |
| BTC | M15+H1 prod | 187 | 34.2 | +4.58 | 1.04 | 19.76 | 0.024 |  |
| BTC | M15+H1 cf instead | 260 | 38.8 | +15.70 | 1.11 | 23.63 | 0.060 |  |
| BTC | M15+H1 cf + band | 262 | 38.5 | +15.34 | 1.10 | 24.54 | 0.059 |  |
| US100 | M15+H1 prod | 128 | 42.2 | +21.84 | 1.30 | 8.00 | 0.171 |  |
| US100 | M15+H1 cf instead | 174 | 40.2 | +26.17 | 1.28 | 7.50 | 0.150 |  |
| US100 | M15+H1 cf + band | 174 | 40.2 | +26.71 | 1.28 | 7.50 | 0.153 |  |
| US500 | M15+H1 prod | 129 | 39.5 | +8.76 | 1.11 | 14.07 | 0.068 |  |
| US500 | M15+H1 cf instead | 187 | 35.8 | -1.06 | 0.99 | 22.12 | -0.006 |  |
| US500 | M15+H1 cf + band | 187 | 36.4 | +3.61 | 1.03 | 22.08 | 0.019 |  |
| US30 | M15+H1 prod | 130 | 36.2 | +9.61 | 1.12 | 19.88 | 0.074 |  |
| US30 | M15+H1 cf instead | 180 | 41.1 | +21.01 | 1.22 | 17.11 | 0.117 |  |
| US30 | M15+H1 cf + band | 183 | 41.5 | +19.10 | 1.20 | 20.73 | 0.104 |  |
| GER40 | M15+H1 prod | 140 | 34.3 | -13.73 | 0.85 | 19.30 | -0.098 |  |
| GER40 | M15+H1 cf instead | 193 | 37.3 | -2.82 | 0.97 | 14.37 | -0.015 |  |
| GER40 | M15+H1 cf + band | 193 | 37.3 | -1.14 | 0.99 | 14.37 | -0.006 |  |
| EURUSD | M15+H1 prod | 136 | 33.8 | +20.72 | 1.23 | 20.48 | 0.152 |  |
| EURUSD | M15+H1 cf instead | 194 | 40.2 | +14.35 | 1.14 | 25.43 | 0.074 |  |
| EURUSD | M15+H1 cf + band | 194 | 40.2 | +14.54 | 1.14 | 25.24 | 0.075 |  |
| PRIMARY7 | M5+M45 prod band-cross | 1600 | 36.4 | +108.36 | 1.11 | 51.31 | 0.068 |  |
| PRIMARY7 | M5+M45 char-flip instead | 2657 | 44.8 | +132.85 | 1.12 | 56.68 | 0.050 |  |
| PRIMARY7 | M5+M45 char-flip + band | 2662 | 44.8 | +126.33 | 1.11 | 56.79 | 0.047 |  |
| XAU | M5+M45 prod | 203 | 36.0 | +22.55 | 1.17 | 13.00 | 0.111 |  |
| XAU | M5+M45 cf instead | 317 | 47.9 | +37.95 | 1.29 | 12.47 | 0.120 |  |
| XAU | M5+M45 cf + band | 321 | 47.7 | +35.22 | 1.27 | 12.47 | 0.110 |  |
| BTC | M5+M45 prod | 314 | 33.8 | +10.78 | 1.05 | 28.48 | 0.034 |  |
| BTC | M5+M45 cf instead | 509 | 46.2 | +33.21 | 1.16 | 36.91 | 0.065 |  |
| BTC | M5+M45 cf + band | 510 | 46.1 | +30.04 | 1.14 | 36.98 | 0.059 |  |
| US100 | M5+M45 prod | 234 | 35.5 | +11.31 | 1.08 | 21.13 | 0.048 |  |
| US100 | M5+M45 cf instead | 365 | 46.3 | +34.63 | 1.23 | 15.69 | 0.095 |  |
| US100 | M5+M45 cf + band | 365 | 46.3 | +34.35 | 1.23 | 15.69 | 0.094 |  |
| US500 | M5+M45 prod | 190 | 42.1 | +35.69 | 1.33 | 11.76 | 0.188 |  |
| US500 | M5+M45 cf instead | 346 | 45.7 | +33.19 | 1.24 | 11.85 | 0.096 |  |
| US500 | M5+M45 cf + band | 346 | 46.0 | +33.25 | 1.24 | 10.90 | 0.096 |  |
| US30 | M5+M45 prod | 223 | 38.6 | +12.46 | 1.09 | 28.42 | 0.056 |  |
| US30 | M5+M45 cf instead | 364 | 44.2 | +13.16 | 1.09 | 18.77 | 0.036 |  |
| US30 | M5+M45 cf + band | 364 | 44.2 | +12.22 | 1.08 | 18.77 | 0.034 |  |
| GER40 | M5+M45 prod | 208 | 38.0 | +26.02 | 1.20 | 16.77 | 0.125 |  |
| GER40 | M5+M45 cf instead | 352 | 42.0 | -2.85 | 0.98 | 20.11 | -0.008 |  |
| GER40 | M5+M45 cf + band | 352 | 42.0 | -2.85 | 0.98 | 20.11 | -0.008 |  |
| EURUSD | M5+M45 prod | 228 | 32.9 | -10.44 | 0.93 | 30.39 | -0.046 |  |
| EURUSD | M5+M45 cf instead | 404 | 41.3 | -16.43 | 0.91 | 32.75 | -0.041 |  |
| EURUSD | M5+M45 cf + band | 404 | 41.3 | -15.89 | 0.91 | 32.09 | -0.039 |  |

## Exit mix (PRIMARY7)

| stack | n | exits |
| --- | ---: | ---: |
| SATS M15 chart-TF | 14271 | flip_exit 10153, stop 2772, tp3 1188, char_flip_flip_exit 151, open_eod 7 |
| SATS M5 chart-TF | 44765 | flip_exit 33380, stop 7599, tp3 3304, char_flip_flip_exit 474, open_eod 7, timeout 1 |
| ST_V3 prod M15+H1 | 952 | stop 601, be 210, band_cross 137, open_eod 4 |
| ST_V3 prod M5+M45 | 1600 | stop 1015, band_cross 362, be 218, open_eod 5 |
| ST_V3 M15+H1 TQI≥0.5 gate | 243 | stop 165, be 43, band_cross 34, open_eod 1 |
| ST_V3 M5+M45 TQI≥0.5 gate | 741 | stop 473, band_cross 151, be 116, open_eod 1 |
| ST_V3 M15+H1 char-flip instead of band runner | 1352 | stop 726, char_flip 452, be 172, open_eod 2 |
| ST_V3 M5+M45 char-flip instead of band runner | 2657 | char_flip 1534, stop 1006, be 112, open_eod 5 |
| ST_V3 M15+H1 char-flip + band runner | 1364 | stop 732, char_flip 446, be 164, band_cross 20, open_eod 2 |
| ST_V3 M5+M45 char-flip + band runner | 2662 | char_flip 1522, stop 1009, be 102, band_cross 24, open_eod 5 |

## Idea harvest vs ST_V3

| idea | what SATS does | grafted A/B here | takeaway |
| --- | --- | --- | --- |
| TQI as soft filter | quality 0..1 from ER+vol+structure+mom; SATS itself does **not** hard-filter entries | ST_V3 band-cross only if chart-TF TQI≥0.5 | **Rejected.** M15 ΔsumR −118R; XAU gutted. |
| Character-flip | TQI window collapse flips the SATS trend (early exit + reverse signal) | ST_V3 runner: char-flip instead of, or in addition to, band-cross | **Do not merge.** Book sumR up, but XAU M15 and GER40 M5 lose; extra n from earlier exits. |
| Asymmetric / TQI-modulated ST width | ATR×baseMult then TQI power-curve + active/passive split | **not** grafted (would replace locked ST 7/2) | Leave 7/2 locked. |
| Dynamic TP scale | TQI+vol scales 1/2/3R (off in Default / this bakeoff) | **not** run; prior 12m fixed-RR already lost to the prod runner (PR #152) | Do not reopen. |
| Pivot SL + ATR cap | SATS stop is a structure pivot, not the ST line | **not** grafted; ST_V3 lock is SL = HTF ST line | Keep ST-line SL. |
| Flip-only entry | SATS enters on ST flip, not band-cross | this SATS Default cell (14k–45k flips) | **Rejected.** Flip entry without HTF stack is the overtrade. |

## Simplifications vs full Pine

- No official Pine in-repo; engine reconstructed from SATS TV docs (through v1.12.0) + ProRealTime port. v1.13.1 patch notes were not independently published.
- Chart TF = entry TF only (M15 or M5). No MTF request.security, no Auto-preset ATR/ER/RSI remap per TF — Default is ATR14 / baseMult 2.0 on both.
- Band source is hl2 (TV SuperTrend). The PRT port uses close.
- No volume series in HistData/Dukascopy/Coinbase loaders used here — TQI volatility factor always uses ATR/ATR-baseline mapped [0.6, 1.8]→[0,1].
- Character-flip is window-based (v1.12 note): max TQI over the prior charFlipWindow=5 bars > 0.55 AND current TQI < 0.25 AND trendAge ≥ 5. Old 1-bar collapse almost never fired.
- Max SL distance defaulted to 4.0× ATR (v1.12 added the cap; Pine default not in public notes). Pivot SL = further of (pivot ± 1.5 ATR, close ± 1.5 ATR), then clipped to ≤ 4 ATR from entry.
- Fixed TP 1/2/3R thirds. Dynamic TP, Auto-calibration, dashboard/regime grid, and score-graded alerts are off / not used as filters.
- Timeout remaining is marked to the close (v1.12 honest P&L). Flip-exit closes remaining at close then opens the new flip. Same-bar SL+TP: SL wins, new TP tags on that bar are ignored.
- Pivots are symmetric 2*3+1 (confirmed 3 bars later). No Pine pivot lookahead.
- One ticket at a time. No spread/commission. Stop-first. Signals only after warmup (ATR baseline 100 + 10).

## Does SATS make sense?

Mechanically yes: TQI is a real 4-factor quality meter; modulating SuperTrend width with a power curve + asymmetry is a coherent answer to 'fixed ATR×mult is too wide in trend / too tight in chop'. Character-flip is the interesting original piece (exit on quality collapse before the band break). The rest is a classic SuperTrend + pivot SL + 1/2/3R scale-out + timeout, with a display-only score that must not be mistaken for an edge filter.

It is **not** a drop-in replacement for ST_V3: different entry (flip vs band-cross), different stop (pivot vs HTF ST line), different management (thirds to fixed R vs half@2R + band runner), and it does not use an HTF stack — the thing that actually carries ST_V3.

## Replay

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.test_sats
python3 -m tools.ha_hunt_st_compare.run_sats_vs_st_v3
```

