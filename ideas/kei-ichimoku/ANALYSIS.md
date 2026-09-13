# Channel verdict — Kei Ichimoku vs HTS

**Conditional research book. Not a main replacement for HTS (or MMS).**

This folder is a public Kei / @KeiForex Ichimoku reconstruction (H1 signal, mechanized M5 sniper). It belongs under `ideas/`, not under live `HtsVariant` / Java defaults.

---

## Verdict

Keep HTS (HA-Hunt / FAST / ST_V3) as the **primary systematic book**. Treat Kei Ichimoku as a **satellite research sleeve**: same desk names (XAU, EUR, NQ, BTC), different tool, **only** when the H1 cloud is sloped and Chikou is actually free.

Do **not** ship it as “the new HTS”. Do **not** park HTS because a Kumo screenshot is cleaner than RMA bands on one trend day. Promote only if Strategy Tester **plus** a paper month beat the HTS sleeve on *that* name without doubling the same directional bets.

---

## Why it is not the main book

| | HTS (prod / research) | Kei Ichimoku (this idea) |
| --- | --- | --- |
| Engine | RMA bands + HA hunt, already in Java | Hosoda five lines, Pine only |
| Identity | Adam’s channel / bot | Someone else’s public method, mechanized |
| Regime skip | Overlapping / flat bands | Flat Kumo / in-cloud (Sanyaku) |
| Entry | HA flip / band / ST — encoded in prod | Videos = discretionary M5 PA; we **invented** a swing-break so TV can test |
| Risk model | 2.5×ATR / split tickets / MMS bands | 1% / swing+ATR / 2R — different animal |
| Status | Live variants + overlays | RESEARCH, no `HtsEngine` hook |

Two trend-following books on XAU/EUR will **stack** the same longs. A satellite that only pays when HTS is already paying is not diversification.

The M5 rule is a **research proxy**, not Kei’s hand. If the tester wins, you still have to decide whether *that* proxy is what you want to execute — not whether Kei’s pins look good on YouTube.

---

## When a satellite slot is plausible

- H1 Kumo **sloped**, Senkou B not flat, price **outside** the cloud.
- TK cross has happened and **Chikou has cleared** (the public setup). Sanyaku = A+ / rare.
- EUR and XAU first; NQ if point-value sizing looks sane; BTC last (flat clouds + M5 noise).
- HTS bands **overlapping** (HTS would skip) while Ichimoku still shows a clean H1 Kumo — that is the only interesting “extra” trade. If both fire together, count it as **one** risk unit, not two books.

## When to ignore it

- Square / thin Kumo, price in cloud, Chikou tangled in past candles.
- News spikes (not coded here).
- Any urge to take the TK cross alone (Kei’s own public rule: **do not**).
- Using this Pine on M15/H1 “because it plots” — book is M5 execution.

---

## Test bar before anyone calls it a channel series

1. 12 months, four names, defaults in [`README.md`](README.md) (`Chikou after TK`, 2R, 1%).
2. Same window, `Sanyaku` and 3R.
3. Overlay trade dates on HTS FAST / HA-Hunt. If > half the Kei winners are already HTS winners, the book is redundant.
4. Paper the winner **without** changing Java defaults.

Until that exists, the honest line on stream is: *“Public Ichimoku reconstruction, research-only, not KTS, not our live HTS.”*
