# HA-Hunt ST — pyramid A/B × Supertrend 10/2 vs 12/3 (~12 months)

Generated `2026-09-11T17:41:27.541671+00:00`. Simulator: `tools/ha_hunt_st_compare` matching PR #139 half+BE+HA and PR #142 same-direction pyramid. **Do not merge as live logic.**

## Question

Adam does not want PR #142 merged until this is measured: does same-direction pyramiding (add while open, `capReg=2`) improve the locked half+BE+HA books vs the current `pos == 0` gate? Second axis: Supertrend **ATR 10 / factor 2.0** (pine default / prior bakeoffs) vs **ATR 12 / factor 3.0**.

## Locked rules (identical across A–D)

- Slow RMA **144**, fast **33**, M45 structure gate **ON**, `capReg` **2**, both sides.
- Trigger = first chart-TF close beyond the fast band (`bandCrossStrict` off).
- Bias + initial SL = closed Supertrend of the stack TF (M45 or H1).
- Management: 50% at 1:2 → stop to **BE (avg entry)** → runner exit on confirmed chart-TF HA body flip against (or BE).
- Conservative same-bar: stop before TP1.
- **A / C** = no pyramid (`pos == 0`). Sequential fills after a close still allowed up to cap 2.
- **B / D** = PR #142 pyramid: same-direction add while open; opposite ignored; aggregated avg entry; SL stays at first protective stop (or BE after TP1); size-weighted R vs first-fill 1R so a 2-unit stop ≈ −2R.

## Data

Capital DEMO mid caches / API keys were **not** used unless present. Prices are real HistData M1 resampled to M5 (XAU, US100=NSXUSD) and Coinbase Exchange 5m for BTCUSD. **Not Capital mid — do not treat as live fills.** Same window as PR #139.

| symbol | source | first | last | n_m5 | days | note |
| --- | --- | --- | --- | --- | --- | --- |
| XAU | histdata_m1_resampled_m5 | 2025-09-11T04:00:00 | 2026-09-04T20:55:00 | 69637 | 358.7 | capital_unavailable: Capital DEMO credentials not set; HistData M1 (Eastern stam |
| US100 | histdata_m1_resampled_m5 | 2025-09-11T04:00:00 | 2026-09-04T20:10:00 | 67255 | 358.67 | capital_unavailable: Capital DEMO credentials not set; HistData M1 (Eastern stam |
| BTCUSD | coinbase_exchange_m5 | 2025-09-11T00:00:00 | 2026-09-11T00:00:00 | 104972 | 365.0 | capital_unavailable: Capital DEMO credentials not set; Coinbase Exchange BTC-USD |

US500 / US30 comparison: **not run** (deferred).

## Side-by-side

n = completed books (open→flat). `2nd add` = books that actually received a pyramid fill. WR% = share of books with size-weighted R > 0. sumR / DD are in first-fill R units (2-unit books count ~2×).

### XAU — M5+M45

Primary winner — M5 trigger, M45 ST.

| mode | n | WR% | sumR | avgR | PF | maxDD(R) | fills | 2nd add | TP1% |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 273 | 37.7 | +31.0 | +0.114 | 1.18 | 13.0 | 273 | 0 (0%) | 37.7 |
| B | 205 | 34.1 | +40.0 | +0.195 | 1.15 | 37.7 | 389 | 154 (75%) | 34.1 |
| C | 168 | 36.3 | +17.7 | +0.105 | 1.17 | 14.5 | 168 | 0 (0%) | 36.3 |
| D | 111 | 29.7 | -35.5 | -0.320 | 0.82 | 45.7 | 250 | 94 (85%) | 29.7 |

- Pyramid @ 10/2: **mixed** (B − A = +9.0R; 2nd add on 154/205 books).
- Pyramid @ 12/3: **hurts** (D − C = -53.2R; 2nd add on 94/111 books).
- ST params, flat only (C vs A): **10/2 wins** (-13.3R).
- Best cell: **B** n=205 WR=34.1% sumR=+40.0 PF=1.15 DD=37.7R.

### US100 — M5+M45

NQ / US100 M5 + M45 ST.

| mode | n | WR% | sumR | avgR | PF | maxDD(R) | fills | 2nd add | TP1% |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 303 | 34.7 | +9.8 | +0.032 | 1.05 | 28.9 | 303 | 0 (0%) | 34.7 |
| B | 217 | 29.0 | -45.9 | -0.211 | 0.87 | 93.7 | 409 | 148 (68%) | 28.6 |
| C | 177 | 36.7 | +16.2 | +0.092 | 1.15 | 17.3 | 177 | 0 (0%) | 36.7 |
| D | 121 | 28.1 | -62.4 | -0.516 | 0.74 | 88.3 | 270 | 106 (88%) | 28.1 |

- Pyramid @ 10/2: **hurts** (B − A = -55.6R; 2nd add on 148/217 books).
- Pyramid @ 12/3: **hurts** (D − C = -78.6R; 2nd add on 106/121 books).
- ST params, flat only (C vs A): **12/3 wins** (+6.4R).
- Best cell: **C** n=177 WR=36.7% sumR=+16.2 PF=1.15 DD=17.3R.

### US100 — M15+H1

NQ / US100 M15 + H1 ST (prior ~41.8% / +48R on 10/2 flat).

| mode | n | WR% | sumR | avgR | PF | maxDD(R) | fills | 2nd add | TP1% |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 182 | 41.8 | +48.1 | +0.264 | 1.45 | 7.8 | 182 | 0 (0%) | 41.8 |
| B | 159 | 39.6 | +28.4 | +0.178 | 1.19 | 25.9 | 230 | 68 (43%) | 39.6 |
| C | 133 | 36.1 | +11.7 | +0.088 | 1.14 | 18.6 | 133 | 0 (0%) | 36.1 |
| D | 107 | 32.7 | -23.9 | -0.224 | 0.84 | 55.7 | 186 | 67 (63%) | 32.7 |

- Pyramid @ 10/2: **hurts** (B − A = -19.8R; 2nd add on 68/159 books).
- Pyramid @ 12/3: **hurts** (D − C = -35.6R; 2nd add on 67/107 books).
- ST params, flat only (C vs A): **10/2 wins** (-36.4R).
- Best cell: **A** n=182 WR=41.8% sumR=+48.1 PF=1.45 DD=7.8R.

### BTCUSD — M5+M45

Optional — Coinbase 5m, same method.

| mode | n | WR% | sumR | avgR | PF | maxDD(R) | fills | 2nd add | TP1% |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 417 | 35.5 | +27.2 | +0.065 | 1.10 | 25.4 | 417 | 0 (0%) | 35.5 |
| B | 315 | 34.0 | +47.1 | +0.150 | 1.12 | 32.5 | 588 | 241 (77%) | 34.0 |
| C | 215 | 36.3 | +17.7 | +0.082 | 1.13 | 13.7 | 215 | 0 (0%) | 35.8 |
| D | 160 | 31.2 | -21.3 | -0.133 | 0.93 | 92.1 | 373 | 139 (87%) | 30.6 |

- Pyramid @ 10/2: **mixed** (B − A = +19.9R; 2nd add on 241/315 books).
- Pyramid @ 12/3: **hurts** (D − C = -39.0R; 2nd add on 139/160 books).
- ST params, flat only (C vs A): **10/2 wins** (-9.5R).
- Best cell: **B** n=315 WR=34.0% sumR=+47.1 PF=1.12 DD=32.5R.

## One table — every required cell

| symbol | stack | A 10/2 flat | B 10/2 pyr | C 12/3 flat | D 12/3 pyr | pyr 10/2 | pyr 12/3 | ST winner |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| XAU | M5+M45 | 37.7% / +31.0R / DD 13.0 | 34.1% / +40.0R / DD 37.7 | 36.3% / +17.7R / DD 14.5 | 29.7% / -35.5R / DD 45.7 | mixed | hurts | 10/2 wins |
| US100 | M5+M45 | 34.7% / +9.8R / DD 28.9 | 29.0% / -45.9R / DD 93.7 | 36.7% / +16.2R / DD 17.3 | 28.1% / -62.4R / DD 88.3 | hurts | hurts | 12/3 wins |
| US100 | M15+H1 | 41.8% / +48.1R / DD 7.8 | 39.6% / +28.4R / DD 25.9 | 36.1% / +11.7R / DD 18.6 | 32.7% / -23.9R / DD 55.7 | hurts | hurts | 10/2 wins |
| BTCUSD | M5+M45 | 35.5% / +27.2R / DD 25.4 | 34.0% / +47.1R / DD 32.5 | 36.3% / +17.7R / DD 13.7 | 31.2% / -21.3R / DD 92.1 | mixed | hurts | 10/2 wins |

## Call

| symbol | stack | pyramid @ 10/2 | pyramid @ 12/3 | 10/2 vs 12/3 (flat) | best cell |
| --- | --- | --- | --- | --- | --- |
| XAU | M5+M45 | mixed | hurts | 10/2 wins | B +40.0R / 34.1% |
| US100 | M5+M45 | hurts | hurts | 12/3 wins | C +16.2R / 36.7% |
| US100 | M15+H1 | hurts | hurts | 10/2 wins | A +48.1R / 41.8% |

XAU M5+M45 stays the quality name (A flat 10/2 = +31.0R / 37.7% WR). Best XAU cell **by raw sumR** is B at +40.0R — that is a trap: 2nd unit added on **75%** of books and max DD triples (13R → 38R). Pyramid **does not earn a merge**: it hurts more cells than it helps (5 hurt / 0 help across the three required stacks × two ST settings). The 2nd add is common (43–88%), not rare. Keep Supertrend **10 / 2.0** (pine default). 12/3 only wins US100 M5+M45 flat (+16R vs +10R, better DD); it loses on XAU (−13R) and on the US100 M15+H1 star cell (−36R). US100 M15+H1 A (10/2 flat) printed 41.8% / +48.1R (prior published cell was ~41.8% / +48R on the same method). US100 M5+M45 A (10/2 flat) printed 34.7% / +9.8R (prior published cell was ~35.0% / +13R; this pull is ~50 M5 bars shorter).

**Practical lock:** leave `#142` closed. Trade A (flat, ST 10/2) on XAU M5+M45 and US100 M15+H1. Do not switch the pine default to 12/3. US500/US30 still deferred.

**Do not merge PR #142** from this note. Re-run after any pine change.

## How to rerun

```bash
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_pyramid_ab
```

With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5. Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).
