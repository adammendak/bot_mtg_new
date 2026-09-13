# Kei Ichimoku Sniper — TradingView attach

**RESEARCH ONLY.** Public reconstruction of Kei / [@KeiForex](https://www.youtube.com/@KeiForex). **Not official KTS.** Does **not** change Java HTS/MMS or any prod default.

Mechanical rules: [`RULES.md`](RULES.md). Why this is a satellite book, not a HTS replacement: [`ANALYSIS.md`](ANALYSIS.md).

---

## Add to TradingView (Strategy Tester)

1. Open TradingView → chart the symbol (see universe below).
2. Set the chart interval to **M5** (5 minutes). The script reads H1 Ichimoku via `request.security` — leave the chart on M5.
3. Pine Editor → Open → paste the full contents of [`Kei_Ichimoku_Sniper_H1_M5.pine`](Kei_Ichimoku_Sniper_H1_M5.pine) → **Save** → **Add to chart**.
4. Confirm the overlay title is `Kei Ichimoku Sniper H1/M5 (RESEARCH)`.
5. Open **Strategy Tester**. Use a long enough window that H1 Ichimoku can warm up (Tenkan 9 + Kijun 26 + Senkou B 52 + displace 26 ≈ **78 closed hours** before signals are meaningful).

If the corner HUD says `CHART NOT M5`, switch the interval. Fills on any other TF are not this book.

---

## Settings Adam should actually touch

Defaults match [`RULES.md`](RULES.md). Change these first; leave 9/26/52/26 alone unless you are A/B-ing Hosoda lengths.

| Group | Input | Default | Why |
| --- | --- | --- | --- |
| Trigger | **HTF trigger** | `Chikou after TK` | Public Kei sequence: TK cross = alert, Chikou clear = fire. Switch to `Sanyaku` for the triple (TK + Chikou + price beyond Kumo). |
| Risk | **TP = R ×** | `2.0` | Public band is 1:2–1:3. Test `2.0` then `3.0`. |
| Risk | **Risk % of equity** | `1.0` | Public talk ~1–2%. This sizes `qty` from stop distance, not “% of equity as position size”. |
| Risk | **Soft exit: HTF close vs Tenkan against** | off | Turn **on** only after you have a clean RR baseline. |
| Timeframe | **HTF** | `60` | Keep H1 unless you are deliberately testing H4 (`240`) as a research fork. |
| Entry | **M5 swing pivot L/R** | `5` | Mechanical sniper: break of last confirmed pivot, not a pin. |
| Trigger | **Arm timeout (H1 bars)** | `26` | Sticky arm dies after one Ichimoku cycle if M5 never triggers. |

Everything else (flat-Kumo lookback, ATR floor, SL buffer) is in **Filter / Stop** for later sensitivity checks.

---

## What you should see on the chart

- **M5 Ichimoku** — Tenkan, Kijun, Kumo, Chikou (standard displacement).
- **H1 Tenkan / Kijun** — stepline (closed H1).
- **Background** — teal / maroon HTF Tenkan vs Kijun bias.
- Tiny aqua / orange triangles — H1 **arm** (TK cross).
- Tiny diamonds — H1 **ready** (Chikou, and Kumo for Sanyaku).
- **LONG** / **SHORT** arrows + labels — M5 entry signal (fill is the **next** bar open).

---

## Universe (tester)

Run the **same defaults** on each name. Do not retune per symbol on the first pass.

| Desk name | Typical TV symbol | Notes |
| --- | --- | --- |
| XAU | `XAUUSD` / `GOLD` | Session gaps; set commission/spread in Tester if you want honest metals costs. |
| EUR | `EURUSD` | Cleanest Ichimoku tape of the four. |
| NQ | `NQ1!` / `NAS100` / Capital `US100` | Index point value — risk % uses `syminfo.pointvalue`. |
| BTC | `BTCUSD` / `BTCUSDT` | 24/7; expect more flat-Kumo skips and more noise on M5. |

Warm-up: start the Tester date **at least a week** before the period you care about so H1 Senkou B and Chikou exist.

Suggested first pass: **12 months**, `Chikou after TK`, RR **2.0**, risk **1%**, soft exit **off**, all four symbols. Second pass: `Sanyaku` and RR **3.0**. Compare to HTS FAST / HA-Hunt on the **same** window — do not promote this book because one gold week looks pretty.

---

## Disclaimer

- Public YouTube reconstruction. **Not** Kei’s paid KTS curriculum and **not** a signal service.
- M5 entries in the videos are discretionary PA; this script **replaces** that with a swing-break rule so the tester is repeatable.
- `lookahead_off` + last **closed** H1 bar. Forming-hour Ichimoku on a live H1 chart will look slightly ahead of what the tester used.
- Past Strategy Tester equity is not a forecast. Spread, commission, and weekend gaps are on you.
- **Do not** copy these inputs into Java HTS/MMS or turn this on as a live `HtsVariant`.

If a result looks too good, check that the chart was **M5** and that you did not enable lookahead or “use forming H1”.
