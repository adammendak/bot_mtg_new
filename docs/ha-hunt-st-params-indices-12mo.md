# HA-Hunt ST — Supertrend params + US100/US500/US30 (~12 months)

Generated `2026-09-11T17:54:53.490843+00:00`. Simulator: `tools/ha_hunt_st_compare` matching PR #139 / #143 half+BE+HA. **Pyramid OFF** (`pos == 0`). **Do not merge as live logic.** Does not merge PR #142.

## Question

Two research axes on the locked stack:

1. **Part A** — does any classic TradingView Supertrend `(ATR length × factor)` beat the pine default **10 / 2.0** on total R / PF / DD / WR?
2. **Part B** — on the winning ST (or 10/2 if nothing beats it), how do **US500** and **US30** compare to **US100 / NQ**?

Hypothesis (pre-registered): 10/2 remains best; US500 tracks US100 closer than US30.

## Locked rules (identical across every cell)

- Slow RMA **144**, fast **33**, M45 structure gate **ON**, `capReg` **2**, both sides.
- **Flat only:** `canEnter = fills < capReg and pos == 0`. Sequential fills after a close still allowed up to cap 2. **No pyramid.** PR #142 stays closed.
- Trigger = first chart-TF close beyond the fast band (`bandCrossStrict` off).
- Bias + initial SL = closed Supertrend of the stack TF (M45 or H1).
- Management: 50% at 1:2 → stop to **BE (avg entry)** → runner exit on confirmed chart-TF HA body flip against (or BE).
- Conservative same-bar: stop before TP1.

## Data

Capital DEMO mid caches / API keys were **not** used unless present. Prices are real HistData M1 resampled to M5 (XAU, US100=NSXUSD, US500=SPXUSD) and Dukascopy M1 resampled to M5 for US30 (USA30.IDX/USD — HistData has no DJIA pair). **Not Capital mid — do not treat as live fills.** Same window as PR #139 / #143.

Repo mapping (Capital epics in `application.properties`): US100→`US100`, US500→`US500`, US30→`US30` (risk-watch only; SDD comment is "not US30").

| symbol | source | first | last | n_m5 | days | note |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| XAU | histdata_m1_resampled_m5 | 2025-09-11T04:00:00 | 2026-09-04T20:55:00 | 69637 | 358.7 | capital_unavailable: Capital DEMO credentials not set; HistData XAUUSD M1 (Eastern stamps  |
| US100 | histdata_m1_resampled_m5 | 2025-09-11T04:00:00 | 2026-09-04T20:10:00 | 67255 | 358.67 | capital_unavailable: Capital DEMO credentials not set; HistData NSXUSD M1 (Eastern stamps  |
| US500 | histdata_m1_resampled_m5 | 2025-09-11T04:00:00 | 2026-09-04T20:10:00 | 67242 | 358.67 | capital_unavailable: Capital DEMO credentials not set; HistData SPXUSD M1 (Eastern stamps  |
| US30 | dukascopy_m1_resampled_m5 | 2025-09-11T00:00:00 | 2026-09-11T00:00:00 | 68385 | 365.0 | capital_unavailable: Capital DEMO credentials not set; Dukascopy USA30.IDX/USD M1 (UTC bid |

### Series used

- **US100 / NQ:** HistData `NSXUSD` (Nasdaq 100 cash). Same series as PR #139/#143.
- **US500 / ES:** HistData `SPXUSD` (S&P 500 cash). Same vendor + M1→M5 method as US100. Not ES futures.
- **US30 / YM:** HistData does **not** list DJIA (UDXUSD is the Dollar Index). Dukascopy `USA30.IDX/USD` cash DJIA, M1 bid → M5. UTC-native stamps (HistData is Eastern→UTC). Prints at DJIA cash (~52–53k in this window), not a YM point-value contract.

## Part A — Supertrend ATR × factor

Grid vs baseline **10 / 2.0**. Already measured on #143: **12 / 3.0** (worse on the star cells). Also: 10/3, 14/2, 14/3, 7/2, 7/3, 12/2, plus cheap extras 10/2.5 and 8/2.

A cell **beats 10/2** only if its **sumR is higher on both required stacks** (XAU M5+M45 and US100 M15+H1). Mixed / one-stack wins stay on 10/2.

### XAU — M5+M45

| ST | n | WR% | sumR | avgR | PF | maxDD(R) | ΔR vs 10/2 | ΔPF | ΔDD | ΔWR | vs 10/2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10/2 | 273 | 37.7 | +31.0 | +0.114 | 1.18 | 13.0 | +0.0 | +0.00 | +0.0 | +0.0 | baseline |
| 12/3 | 168 | 36.3 | +17.7 | +0.105 | 1.17 | 14.5 | -13.3 | -0.02 | +1.5 | -1.4 | worse/tie R |
| 10/3 | 170 | 34.1 | +5.1 | +0.030 | 1.05 | 21.0 | -25.9 | -0.14 | +8.0 | -3.6 | worse/tie R |
| 14/2 | 273 | 38.1 | +36.6 | +0.134 | 1.22 | 9.5 | +5.6 | +0.03 | -3.5 | +0.4 | beats on this stack |
| 14/3 | 172 | 34.9 | +12.9 | +0.075 | 1.12 | 11.1 | -18.0 | -0.07 | -1.9 | -2.8 | worse/tie R |
| 7/2 | 269 | 39.0 | +48.1 | +0.179 | 1.29 | 9.7 | +17.1 | +0.11 | -3.3 | +1.3 | beats on this stack |
| 7/3 | 167 | 32.9 | +0.5 | +0.003 | 1.00 | 16.2 | -30.5 | -0.18 | +3.2 | -4.8 | worse/tie R |
| 12/2 | 276 | 37.3 | +29.5 | +0.107 | 1.17 | 12.3 | -1.5 | -0.01 | -0.7 | -0.4 | worse/tie R |
| 10/2.5 | 226 | 40.7 | +46.0 | +0.204 | 1.34 | 9.9 | +15.0 | +0.16 | -3.1 | +3.0 | beats on this stack |
| 8/2 | 274 | 37.6 | +41.0 | +0.150 | 1.24 | 13.7 | +10.0 | +0.06 | +0.8 | -0.1 | beats on this stack |

### US100 — M15+H1

| ST | n | WR% | sumR | avgR | PF | maxDD(R) | ΔR vs 10/2 | ΔPF | ΔDD | ΔWR | vs 10/2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10/2 | 182 | 41.8 | +48.1 | +0.264 | 1.45 | 7.8 | +0.0 | +0.00 | +0.0 | +0.0 | baseline |
| 12/3 | 133 | 36.1 | +11.7 | +0.088 | 1.14 | 18.6 | -36.4 | -0.32 | +10.9 | -5.7 | worse/tie R |
| 10/3 | 136 | 37.5 | +17.7 | +0.130 | 1.21 | 12.6 | -30.5 | -0.25 | +4.8 | -4.3 | worse/tie R |
| 14/2 | 178 | 40.4 | +40.1 | +0.225 | 1.38 | 10.5 | -8.0 | -0.08 | +2.7 | -1.3 | worse/tie R |
| 14/3 | 134 | 36.6 | +12.7 | +0.095 | 1.15 | 18.0 | -35.5 | -0.30 | +10.3 | -5.2 | worse/tie R |
| 7/2 | 179 | 43.0 | +53.2 | +0.297 | 1.52 | 6.0 | +5.0 | +0.07 | -1.8 | +1.3 | beats on this stack |
| 7/3 | 139 | 38.8 | +23.4 | +0.168 | 1.28 | 9.1 | -24.7 | -0.18 | +1.3 | -2.9 | worse/tie R |
| 12/2 | 174 | 40.8 | +41.4 | +0.238 | 1.40 | 7.8 | -6.7 | -0.05 | +0.1 | -1.0 | worse/tie R |
| 10/2.5 | 154 | 40.9 | +32.8 | +0.213 | 1.36 | 10.3 | -15.4 | -0.09 | +2.6 | -0.8 | worse/tie R |
| 8/2 | 178 | 43.3 | +54.7 | +0.307 | 1.54 | 7.0 | +6.5 | +0.09 | -0.8 | +1.5 | beats on this stack |

### US100 — M5+M45

| ST | n | WR% | sumR | avgR | PF | maxDD(R) | ΔR vs 10/2 | ΔPF | ΔDD | ΔWR | vs 10/2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10/2 | 303 | 34.7 | +9.8 | +0.032 | 1.05 | 28.9 | +0.0 | +0.00 | +0.0 | +0.0 | baseline |
| 12/3 | 177 | 36.7 | +16.2 | +0.092 | 1.15 | 17.3 | +6.4 | +0.10 | -11.6 | +2.1 | beats on this stack |
| 10/3 | 172 | 36.0 | +11.9 | +0.069 | 1.11 | 15.9 | +2.1 | +0.06 | -13.0 | +1.4 | beats on this stack |
| 14/2 | 301 | 35.2 | +16.5 | +0.055 | 1.08 | 29.7 | +6.7 | +0.04 | +0.8 | +0.6 | beats on this stack |
| 14/3 | 179 | 34.6 | +4.7 | +0.026 | 1.04 | 21.2 | -5.1 | -0.01 | -7.7 | -0.0 | worse/tie R |
| 7/2 | 311 | 35.7 | +16.5 | +0.053 | 1.08 | 22.0 | +6.8 | +0.03 | -6.9 | +1.0 | beats on this stack |
| 7/3 | 171 | 36.8 | +16.1 | +0.094 | 1.15 | 13.1 | +6.3 | +0.10 | -15.8 | +2.2 | beats on this stack |
| 12/2 | 303 | 34.7 | +10.8 | +0.036 | 1.05 | 33.5 | +1.0 | +0.01 | +4.6 | +0.0 | beats on this stack |
| 10/2.5 | 234 | 37.6 | +25.7 | +0.110 | 1.18 | 18.1 | +15.9 | +0.13 | -10.8 | +3.0 | beats on this stack |
| 8/2 | 310 | 35.2 | +13.5 | +0.044 | 1.07 | 22.8 | +3.8 | +0.02 | -6.2 | +0.5 | beats on this stack |

### Who beats 10/2 on **both** required stacks?

| ST | XAU M5+M45 ΔR | US100 M15+H1 ΔR | beats both? |
| --- | ---: | ---: | ---: |
| 12/3 | -13.3R ✗ | -36.4R ✗ | no |
| 10/3 | -25.9R ✗ | -30.5R ✗ | no |
| 14/2 | +5.6R ✓ | -8.0R ✗ | no |
| 14/3 | -18.0R ✗ | -35.5R ✗ | no |
| 7/2 | +17.1R ✓ | +5.0R ✓ | YES |
| 7/3 | -30.5R ✗ | -24.7R ✗ | no |
| 12/2 | -1.5R ✗ | -6.7R ✗ | no |
| 10/2.5 | +15.0R ✓ | -15.4R ✗ | no |
| 8/2 | +10.0R ✓ | +6.5R ✓ | YES |

**Part A call:** Supertrend **7 / 2**. 7/2 beat 10/2 on both required stacks; highest combined sumR among beaters.

## Part B — US100 vs US500 vs US30

Same locked stack, pyramid OFF, ST **7 / 2** (7/2 beat 10/2 on both required stacks; highest combined sumR among beaters).

### M5+M45

| symbol | n | WR% | sumR | PF | maxDD(R) | NQ distance | note |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| US100 | 311 | 35.7 | +16.5 | 1.08 | 22.0 | — | NQ baseline |
| US500 | 260 | 36.5 | +22.4 | 1.14 | 11.2 | 2.56 | distance 2.56 (lower=closer to NQ) |
| US30 | 294 | 36.4 | +26.2 | 1.14 | 19.8 | 1.94 | distance 1.94 (lower=closer to NQ) |

### M15+H1

| symbol | n | WR% | sumR | PF | maxDD(R) | NQ distance | note |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| US100 | 179 | 43.0 | +53.2 | 1.52 | 6.0 | — | NQ baseline |
| US500 | 180 | 38.9 | +21.8 | 1.20 | 12.8 | 8.27 | distance 8.27 (lower=closer to NQ) |
| US30 | 176 | 33.5 | +0.1 | 1.00 | 16.4 | 16.36 | distance 16.36 (lower=closer to NQ) |

### One table — every Part B cell

| symbol | stack | n | WR% | sumR | PF | maxDD | vs NQ |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| US100 | M5+M45 | 311 | 35.7 | +16.5 | 1.08 | 22.0 | 35.7% / +16.5R / DD 22.0 |
| US500 | M5+M45 | 260 | 36.5 | +22.4 | 1.14 | 11.2 | 36.5% / +22.4R / DD 11.2  (ΔR +5.9) |
| US30 | M5+M45 | 294 | 36.4 | +26.2 | 1.14 | 19.8 | 36.4% / +26.2R / DD 19.8  (ΔR +9.6) |
| US100 | M15+H1 | 179 | 43.0 | +53.2 | 1.52 | 6.0 | 43.0% / +53.2R / DD 6.0 |
| US500 | M15+H1 | 180 | 38.9 | +21.8 | 1.20 | 12.8 | 38.9% / +21.8R / DD 12.8  (ΔR -31.4) |
| US30 | M15+H1 | 176 | 33.5 | +0.1 | 1.00 | 16.4 | 33.5% / +0.1R / DD 16.4  (ΔR -53.1) |

### Closeness / tradeability

- **M5+M45:** US30 is closer to US100 (US500 distance 2.56, US30 distance 1.94; US100 35.7% / +16.5R / DD 22.0).
- **M15+H1:** US500 is closer to US100 (US500 distance 8.27, US30 distance 16.36; US100 43.0% / +53.2R / DD 6.0).

- **US500 M5+M45:** worth a look (36.5% / +22.4R / DD 11.2, PF 1.14, n=260).
- **US30 M5+M45:** worth a look (36.4% / +26.2R / DD 19.8, PF 1.14, n=294).
- **US500 M15+H1:** worth a look (38.9% / +21.8R / DD 12.8, PF 1.20, n=180).
- **US30 M15+H1:** marginal (33.5% / +0.1R / DD 16.4, PF 1.00, n=176).

## Call

- **Supertrend:** **7 / 2** wins this pre-registered grid. 7/2 beat 10/2 on both required stacks; highest combined sumR among beaters.
- Also beat both required stacks: **8/2**. Shorter ATR at factor 2.0 is the cluster; every factor-3 cell lost.
- **Hypothesis 10/2 remains best:** rejected — at least one grid cell beat 10/2 on both required stacks.
- **Hypothesis US500 tracks US100 closer than US30:** partial — true on M15+H1 (the NQ star), not uniformly on M5+M45.
- **Pyramid:** still OFF. Do not merge PR #142.
- **Practical lock:** keep the #143 A-cells (XAU M5+M45 and US100 M15+H1) flat. ST 7/2 is the measured upgrade on XAU + US100 M15 in this 12m window; do not flip pine until a second window or live paper agrees. 7/2 is **not** free across indices — US30 M15+H1 falls from +21R @ 10/2 to ~0R @ 7/2. US500 is the index to try next to NQ. US30 is only clearly usable on M15 at the 10/2 lock.

**Do not merge this note as live pine.** Re-run after any pine change.

## Appendix — Part B at ST 10/2 (locked pine default)

Same symbols/stacks at the #139/#143 Supertrend so the index call is not confounded by the 7/2 switch.

### M5+M45 @ 10/2

| symbol | n | WR% | sumR | PF | maxDD(R) | NQ distance | call |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| US100 | 303 | 34.7 | +9.8 | 1.05 | 28.9 | — | NQ baseline |
| US500 | 262 | 34.4 | +5.8 | 1.03 | 12.3 | 2.38 | marginal |
| US30 | 304 | 34.2 | +6.3 | 1.03 | 25.0 | 1.20 | marginal |

### M15+H1 @ 10/2

| symbol | n | WR% | sumR | PF | maxDD(R) | NQ distance | call |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| US100 | 182 | 41.8 | +48.1 | 1.45 | 7.8 | — | NQ baseline |
| US500 | 180 | 38.3 | +24.5 | 1.22 | 12.9 | 6.53 | worth a look |
| US30 | 175 | 37.1 | +20.7 | 1.19 | 12.0 | 8.05 | worth a look |

At the pine-default 10/2, **US500** is closer to US100 on the M15+H1 star (US500 distance 6.53, US30 8.05). Both US500 and US30 M15 print +EV here (US500 38.3% / +24.5R / DD 12.9, US30 37.1% / +20.7R / DD 12.0); the 7/2 switch is what knocks US30 M15 to flat.

## How to rerun

```bash
python3 -m pip install pandas numpy histdata-fetcher dukascopy-python
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_st_indices
```

With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5. Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored). US30 always needs Dukascopy unless Capital is set (HistData has no DJIA).

