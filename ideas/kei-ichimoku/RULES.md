# Kei Ichimoku Sniper (H1 → M5) — mechanical rules

**RESEARCH ONLY.** Public YouTube reconstruction of Kei / [@KeiForex](https://www.youtube.com/@KeiForex) (Japanese Ichimoku Trader Kei). This is **not** official KTS, **not** a paywalled mentorship dump, and **not** a replacement for production HTS or MMS.

Java `HtsEngine` / `MmsEngine` and live defaults stay untouched. Tester companion: [`Kei_Ichimoku_Sniper_H1_M5.pine`](Kei_Ichimoku_Sniper_H1_M5.pine). How to attach: [`README.md`](README.md). Channel verdict: [`ANALYSIS.md`](ANALYSIS.md).

---

## 1. What this book is

Kei’s public lesson (especially *The Only Ichimoku Setup I Trade*) is:

1. Identify the prior trend.
2. Watch a **Tenkan × Kijun cross** the other way — this is a **heads-up, not an entry**.
3. **Wait.** Do not chase the cross.
4. **Fire** when **Chikou** breaks through past price (close vs the candle 26 periods back).
5. Stop at **Kijun or the swing**. Ride while the mid-term line is respected.
6. Prefer **higher-timeframe alignment**. Quality over quantity. Skip a **flat Kumo**.

Public “sniper” clips use discretionary M5 price action under that H1 context. This folder **mechanizes** the sniper so Strategy Tester can run it: after the HTF signal is armed and ready, enter on an M5 **prior-swing break** plus **close vs Tenkan**. No pin-bar judgement.

Two HTF trigger variants are encoded (input):

| Variant | HTF ready condition |
| --- | --- |
| **Chikou after TK** (default) | Arm on H1 Tenkan×Kijun cross; fire when H1 Chikou is clear of past closes. Skip flat Kumo. |
| **Sanyaku** | Same arm, then require Chikou clear **and** H1 price beyond the Kumo (beyond the cloud / Senkou B edge). |

The HTF arm is **sticky**. Chikou may confirm on a later H1 bar than the cross, and the M5 swing break may come later still. Same-bar alignment is **not** required.

---

## 2. Sources (public only)

Reconstruction from public Kei / @KeiForex videos, plus standard Hosoda definitions. **Not KTS course material.**

| Video | URL | What we took |
| --- | --- | --- |
| *The Only Ichimoku Setup I Trade* | https://www.youtube.com/watch?v=EpFv0L0sz9c | TK cross = alert; Chikou break = timing; SL at Kijun or swing; do not chase the cross; HTF alignment |
| Public Ichimoku basics / cloud + Chikou | https://www.youtube.com/watch?v=CA7OXjZvp2c | Five-line reading; Kumo as filter |
| Public follow-ups (sniper / examples) | https://www.youtube.com/watch?v=awbnjB5CLfE | H1 context + lower-TF execution flavour |
| | https://www.youtube.com/watch?v=RbiR8wJhhtA | Same family — structure / wait-for-confirm |
| | https://www.youtube.com/watch?v=A-7l31__-P0 | Same family — examples |

Sanyaku (三役) is the textbook triple: TK cross + price beyond Kumo + Chikou clear. Kei teaches it on the public channel and on forex-kei.com as *Sanyaku Kouten / Gyakuten*. We use the public definition only.

---

## 3. Timeframes

| Role | TF | Notes |
| --- | --- | --- |
| Chart / execution | **M5** | Script must run on M5. Fill = **next M5 open** after a closed signal bar. |
| Context / Ichimoku signal | **H1** | `request.security(..., "60", ..., lookahead=barmerge.lookahead_off)` on the **last closed** H1 bar (no lookahead). |
| Optional higher bias | H4 / D1 | Discussed in videos; **not** an extra AND-gate in the Pine (keeps the tester honest). |

Do not run the strategy on H1 as if it were the entry TF. HTF math is computed *inside* the security call so `close[26]` means **26 H1 bars**, not 26 M5 bars.

---

## 4. Ichimoku definition (standard / TradingView)

Donchian midpoints. Defaults: Tenkan **9**, Kijun **26**, Senkou B **52**, displacement **26**.

| Line | Formula | Plot |
| --- | --- | --- |
| Tenkan-sen | `(highest(high,9) + lowest(low,9)) / 2` | Current bar |
| Kijun-sen | `(highest(high,26) + lowest(low,26)) / 2` | Current bar |
| Senkou Span A | `(Tenkan + Kijun) / 2` | Forward **26** |
| Senkou Span B | `(highest(high,52) + lowest(low,52)) / 2` | Forward **26** |
| Chikou Span | `close` | Backward **26** |

**Kumo at the current bar** is Span A and Span B **calculated 26 periods ago** (the values that have been displaced onto this bar).

- Cloud top = `max(spanA[26], spanB[26])`
- Cloud bottom = `min(spanA[26], spanB[26])`
- **Above Kumo:** `close > cloud top`
- **Below Kumo:** `close < cloud bottom`
- **In cloud:** skip for Sanyaku; conceptual HTF filter also prefers outside Kumo

**Chikou clear** (this reconstruction): `close > close[26]` (long) / `close < close[26]` (short). That is Chikou vs the past **close**, as specified for the tester. A stricter “clears the whole candle” (`close` vs `high[26]` / `low[26]`) is **not** the default.

**Flat Kumo** (skip): Senkou B unchanged over `flatLook` H1 bars (within one mintick) **or** cloud thickness `|spanA − spanB| < flatThinAtr × ATR(14)` on H1. Flat / square cloud = range. Do not arm-fire through it.

**TK aligned:** Tenkan > Kijun (bull) / Tenkan < Kijun (bear).

---

## 5. HTF filter (H1, last closed bar)

Conceptual gate before any LTF order:

| Check | Long | Short |
| --- | --- | --- |
| TK | Tenkan ≥ Kijun after a bullish cross (arm) | Tenkan ≤ Kijun after a bearish cross |
| Chikou | `close > close[26]` | `close < close[26]` |
| Kumo | **Chikou after TK:** skip if flat. **Sanyaku:** price beyond the cloud (beyond Senkou B / far span). | Mirror |
| In-cloud | Not required to exit the Chikou-after-TK path; Sanyaku **rejects** in-cloud | Mirror |

H1 values use `lookahead_off` and the prior completed H1 bar so the tester does not peek into a forming hour.

---

## 6. Triggers

### 6.1 Arm (both variants)

On a **closed H1** Tenkan×Kijun **cross**:

- Bullish `ta.crossover(Tenkan, Kijun)` → `armDir = +1`, `ready = false`, age = 0
- Bearish `ta.crossunder` → `armDir = −1`, `ready = false`, age = 0

The cross is an **alert**. It does not place an order.

The arm stays on until the first of:

1. An M5 entry is taken
2. An **opposite** H1 TK cross (re-arms the other way)
3. **Timeout** — default **26 H1 bars** (one displacement / Kijun cycle), measured as `26 × (H1 seconds / chart seconds)` M5 bars

Do **not** require Chikou and the M5 break on the same bar as the cross.

### 6.2 Variant A — Chikou after TK (default)

While `armDir = +1` and not ready: set **ready** on the first H1 snapshot where Chikou is clear **and** Kumo is **not** flat.

While `armDir = −1`: Chikou clear down, Kumo not flat.

If Chikou is already clear on the cross bar and Kumo is not flat, ready can latch **on that same closed H1**. If not, it waits for a later hour. That is the public sequence: *cross → wait → Chikou break*.

### 6.3 Variant B — Sanyaku

Same arm. Ready only when **all three** are true (not necessarily the same H1 bar):

1. TK cross already happened (we are armed that way)
2. Chikou clear in that direction
3. H1 close **beyond the Kumo** (above cloud top / below cloud bottom — the far edge, i.e. beyond Senkou B when that span is the outer wall)

Still skip flat Kumo. In-cloud never readies Sanyaku.

---

## 7. LTF entry (M5, mechanical — no discretionary pin)

Public sniper videos pick M5 candles by eye (pin, engulfing, structure). **This book does not.** After HTF `ready`:

**Long**

1. Last **confirmed** M5 swing high (pivot high with `swingLen` bars left and right; default 5) is broken: `close > lastSwingHigh` and `close[1] ≤ lastSwingHigh`
2. Signal-bar `close >` M5 Tenkan
3. Account is flat
4. Stop distance is valid (see §8)

**Short** — break of last confirmed M5 swing low + `close <` M5 Tenkan.

**Fill:** market entry at the **next M5 open** (`process_orders_on_close=false`). Size and stop are computed on the closed signal bar; TP is re-anchored to the actual fill for a true R multiple.

No second add, no pyramid (`pyramiding=0`). One position at a time.

If price is already above the prior swing when HTF becomes ready, there is **no chase**: wait for a later **cross** of that swing (dip and re-break, or a new pivot then a break).

---

## 8. Stop

**Default: beyond the protective M5 swing, with an ATR floor.**

- Long: raw stop = last confirmed swing **low** − `slBufAtr × ATR(14)` (M5)
- Short: last confirmed swing **high** + buffer
- Distance from signal close = `max(swing distance, slAtrFloor × ATR(14))`  
  Default floor **1.0 × ATR**. Tight swings are widened; wide swings are kept.
- If there is no usable swing, ATR floor alone.

Conceptual Kei stop is **Kijun or swing**. Kijun is the video default (“below Kijun when buying”). The Pine default is swing+ATR so the sniper has a local structure stop; optional research: measure H1 Kijun as the stop in a later A/B (not required for v1).

---

## 9. Exit

| Exit | Rule | Default |
| --- | --- | --- |
| Hard TP | `TP = fill ± tpR × (fill − SL)` | **2.0 R** (test 2–3) |
| Hard SL | Level from §8 | On |
| Soft exit | Optional: flatten if **H1 close crosses against H1 Tenkan** | **Off** (so RR stats stay clean). Videos also “ride while Kijun is respected” — that is a later toggle, not default. |
| Time | No time stop beyond the **arm** timeout (arm dies before entry; open trades use SL/TP only) | — |

No break-even, no trail in v1.

---

## 10. Risk

- Input **risk % of equity** per trade (default **1.0**; public talk is ~1–2%).
- `qty = (equity × riskPct / 100) / (stopDistance × pointvalue)`
- Tester `initial_capital = 10000`. Commission 0 in the script (set your broker in TV if you care).

This is **not** the Java HTS 2.5×ATR / 1R ticket model and **not** MMS 2% band stops.

---

## 11. Hard skips

Do **not** take a new entry when:

- Chart is not M5 (script still plots; treat fills as invalid research)
- HTF Kumo is flat at the moment we would latch `ready`
- Sanyaku and price is inside the H1 cloud
- Arm timed out or opposite TK cross killed the setup
- Already in a position
- Stop distance ≤ 0 or qty invalid

News blackout, session filter, and spread cap are **not** encoded (manual / later).

---

## 12. Explicitly out of scope

- **Not official KTS.** No paywalled rules, no member-only Kyushu / time-cycle stack.
- **Not a replacement for HTS or MMS** in this repo. No Java, no Heroku flags, no `HtsVariant`.
- **Not discretionary PA.** Pins, wicks-through-Kijun, and “feels like a sniper” are replaced by the swing-break rule.
- **Not** a claim that Kei enters on M5 Tenkan crosses. Tenkan on M5 is only a **close-side filter** after the swing break.

---

## 13. Parameter defaults (tester)

| Input | Default |
| --- | --- |
| Chart / HTF | M5 / `60` |
| Tenkan / Kijun / Senkou B / displace | 9 / 26 / 52 / 26 |
| Trigger | Chikou after TK |
| Swing pivot L/R | 5 |
| Arm timeout | 26 H1 bars |
| Flat Kumo lookback | 8 H1 bars |
| Thin-cloud ATR multiple | 0.15 |
| SL ATR floor / buffer | 1.0 / 0.10 |
| TP R | 2.0 |
| Risk % | 1.0 |
| Soft HTF Tenkan exit | off |
