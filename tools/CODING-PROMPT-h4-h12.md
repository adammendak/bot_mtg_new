# Coding prompt — H4-HA-hunt and H12-HA-hunt cloud-hold strategies

Two related strategies validated on a rebuilt backtest (`tools/h4_ha_cloud_bt.py`, Capital.com
demo mids). Both = **SDD-style entry + HTF Heikin-Ashi "hunt" regime gate + cloud-hold exit**.
They differ only in the timeframe triple. Implement both as separate strategy variants.

**Backtest evidence (no spread/commission modelled — subtract realistically):**

| Config | window | +%/yr | MaxDD% | PF | trades/yr |
|---|---|---:|---:|---:|---:|
| **H4** (XAU+US100, longs, slow=100) | IS | +40.5 | 7.3 | 1.53 | ~230 |
| **H4** (XAU+US100, longs, slow=100) | OOS | +40.4 | 8.0 | 1.53 | ~225 |
| **H4** (XAU+US100+**USDJPY**, longs, slow=100) | IS | +73.0 | 6.9 | 1.61 | ~360 |
| **H4** (XAU+US100+**USDJPY**, longs, slow=100) | OOS | +67.7 | 8.7 | 1.63 | ~320 |
| **H12** (XAU+US100, longs, slow=144) | IS | +24.4 | 4.8 | 2.02 | ~72 |
| **H12** (XAU+US100, longs, slow=144) | OOS | +13.1 | 5.0 | 1.53 | ~79 |

(slow=133, the SddEngine default, gives H4 +39.0/DD10/PF1.50 and H12 +23.1/DD6.1/PF1.92 —
essentially the same picture; the slow RMA is a second-order knob.)

The two-instrument + longs-only + cloud-exit combo held almost unchanged across a fully
separate year — that is the reason to trust it. Still: **paper/demo first**, re-check quarterly.

Exit comparison (both timeframes, both years): **cloud-hold > fixed 2R > fixed 1.5R > 2.5×ATR
trailing.** 1.5R is strictly dominated — never use it. Trailing on the M15/H4 pairing is the
worst. If a hard TP is mandatory for ops/psychology reasons, use **+2.0R**, not 1.5R.

---

## Shared building blocks (implement once)

**Prices.** OHLC from mid = (bid + ask) / 2.

**Heikin Ashi** (from raw OHLC series):
- `haClose = (o + h + l + c) / 4`
- `haOpen  = i == 0 ? (o + c) / 2 : (prevHaOpen + prevHaClose) / 2`
- `haHigh  = max(h, haOpen, haClose)` ; `haLow = min(l, haOpen, haClose)`
- `bullish = haClose >= haOpen`

**Wilder RMA(src, n):** `out[n-1] = mean(src[0..n-1])`; then `out[i] = (out[i-1]*(n-1) + src[i]) / n`.

**Wilder ATR(bars, n):** `tr[0] = h[0]-l[0]`; `tr[i] = max(h-l, |h - prevClose|, |l - prevClose|)`;
`ATR = RMA(tr, n)`. Use `n = 14`.

**Resample from H1** into UTC buckets: bucket key = `floor(epochSeconds / (H*3600)) * (H*3600)`
(H = 4 or 12). Bucket open = first H1 open, high = max, low = min, close = last H1 close,
timestamp = bucket start. **A bucket is usable only once fully closed**: `bucketStart + H*3600 <= now`.

**Daily Pivot Point.** Session rolls at **21:00 UTC**:
`sessionDay(t) = t.hourUTC >= 21 ? t.dateUTC + 1 : t.dateUTC`.
Aggregate the previous *completed* session's H/L/C. `PP = (sessH + sessL + sessC) / 3`.
Long requires `entryClose > PP`; short requires `entryClose < PP`. Skip this rule entirely for
crypto / instruments with no daily session.

**RMA lengths:** fast = 33 (fixed). **slow = per strategy** (swept 100/133/144 on 1 IS + 1 OOS
year — second-order knob, all values keep both configs at PF 1.4–2.0 / DD < 12%):
- **Strategy A (H4/M15): slow = 100** — best PF and lowest DD on the faster pairing (IS+OOS PF 1.53, DD 7–8%).
- **Strategy B (H12/H1): slow = 144** — best on the slow pairing (IS PF 2.02 / OOS 1.53, DD ~5%).
Keep it configurable. **"stacked" (long):** `close > RMA33 > RMAslow` (short: `close < RMA33 < RMAslow`).

**Hunt regime & cap.** Track the last *closed* HTF (H4 or H12) Heikin-Ashi colour. A "regime" is
a maximal run of one colour. When the colour flips, a new hunt opens in the new direction and
lasts until the next flip. **At most 2 fills per regime per instrument** (a re-entry after a
stop is allowed while the regime is unchanged).

**Risk / sizing.** `1R ≡ stop distance ≡ 2.5 × (last-closed ATR on the stop timeframe)`.
Size the position so a stop-out = −1 R = −1% of the book. No pyramiding: **one open position
per instrument**. No compounding assumed in the backtest.

**Recommended universe & side (from the study):** **long only**, and:
- **Strategy A (H4/M15): XAU + US100 + USDJPY.** USDJPY held PF 1.75-1.95 / DD ~7% across IS and
  a separate OOS year (best single-instrument OOS behaviour in the study); adding it ~doubles
  book return (+40 -> +68-73 R/yr) at similar DD/PF. US100 is the weak leg (OOS PF ~1.0) but
  near-zero, kept as a diversifier.
- **Strategy B (H12/H1): XAU + US100 only.** H12/H1 has no edge on FX (EURUSD+USDJPY H12 was
  +4.9 IS / -0.6 OOS) — do not add USDJPY here.
- **Rejected: EUR/USD** — flat-to-negative OOS in every cut (both-dir +7 IS / -3 OOS; longs
  +9 IS / -0.7 OOS; it ranged, the strategy needs a trend). GER40 / BTC and the short side
  were net-negative-to-flat and added most of the drawdown.
All of this is an empirical restriction, not a law — keep it configurable and re-validate every
quarter. USDJPY's uptrend is a BoJ-policy / rate-differential regime that can reverse.

---

## Strategy A — "H4-HA-hunt cloud" (M15 execution)

| role | timeframe |
|---|---|
| hunt regime | **H4** Heikin Ashi (resampled from H1) |
| entry trigger & stack | **M15** (closed bars) |
| ATR for the stop | **H1** |

**Entry — all of these on a just-closed M15 bar:**
1. M15 Heikin-Ashi colour **flipped** on this bar (this bar's HA colour ≠ previous bar's).
2. `direction` = this bar's M15 HA colour (bull → long, bear → short).
3. `direction` == current **H4** hunt-regime colour.  ← the hunt gate
4. M15 RMA **stacked** with `direction` (close vs RMA33(M15) vs RMA_slow(M15), slow=100).
5. **H1 WITH**: H1 HA colour == `direction` **OR** H1 close stacked with RMA33(H1)/RMA_slow(H1), slow=100.
6. **Daily PP aligned** (long: close > PP, short: close < PP). Skip for crypto.
7. Universe/side filter (default: instrument ∈ {XAU, US100, USDJPY} AND direction == long).

Fill at the signal M15 bar's close.

**Stop:** `entry ∓ 2.5 × ATR14(H1, last closed)`. `1R` = that distance.

**Exit — cloud-hold, whichever comes first:**
- price touches the stop (→ −1R), OR
- the last-closed **H4** Heikin-Ashi colour flips **against** the position → close at the next
  M15 bar's close.
- No take-profit, no break-even, no trailing.

**Optional hard-TP fallback** (only if required): take profit at `entry ± 2.0 × 1R`. Do **not**
use 1.5R. Backtest PF with this fallback ≈ 1.23 (IS) / 1.36 (OOS) vs 1.50 / 1.53 for cloud-hold.

---

## Strategy B — "H12-HA-hunt cloud" (H1 execution) — the calmer sibling

| role | timeframe |
|---|---|
| hunt regime | **H12** Heikin Ashi (resampled from H1) |
| entry trigger & stack | **H1** (closed bars) |
| ATR for the stop | **H4** |

**Entry — all of these on a just-closed H1 bar:**
1. H1 Heikin-Ashi colour **flipped** on this bar.
2. `direction` = this bar's H1 HA colour.
3. `direction` == current **H12** hunt-regime colour.  ← the hunt gate
4. H1 RMA **stacked** with `direction` (close vs RMA33(H1) vs RMA_slow(H1), slow=144).
5. **H4 WITH**: H4 HA colour == `direction` **OR** H4 close on the `direction` side of RMA33(H4).
6. **Daily PP aligned**. Skip for crypto.
7. Universe/side filter (default: instrument ∈ {XAU, US100} AND direction == long).

Fill at the signal H1 bar's close.

**Stop:** `entry ∓ 2.5 × ATR14(H4, last closed)`. `1R` = that distance.

**Exit — cloud-hold, whichever comes first:**
- price touches the stop (→ −1R), OR
- the last-closed **H12** Heikin-Ashi colour flips **against** the position → close at the next
  H1 bar's close.
- No TP, no BE, no trail.

**Optional hard-TP fallback:** `entry ± 2.0 × 1R` (not 1.5R). Backtest PF with fallback ≈
1.55 (IS) / 1.49 (OOS) vs 1.92 / 1.51 for cloud-hold.

---

## Parameter table

| param | value | note |
|---|---|---|
| RMA fast / slow | 33 / **A:100  B:144** | Wilder RMA on close; slow swept, 2nd-order |
| ATR period | 14 | Wilder |
| stop multiple | 2.5 × ATR | defines 1R |
| hunt cap | 2 fills / regime / instrument | |
| PP session roll | 21:00 UTC | skip for crypto |
| risk per trade | 1% of book | stop = −1R = −1% |
| positions per instrument | 1 | no pyramiding |
| A: hunt/entry/ATR TF | H4 / M15 / H1 | |
| B: hunt/entry/ATR TF | H12 / H1 / H4 | |
| default universe | A: XAU, US100, USDJPY  B: XAU, US100 | configurable; re-check quarterly |
| default side | long only | configurable |
| exit | cloud-hold (HTF HA flip or stop) | 2R hard-TP is the only allowed fallback |

## Do NOT implement (tested, worse)

- 1.5R take-profit (strictly dominated by 2R and by cloud-hold).
- 2.5×ATR trailing stop on the M15/H4 pairing (worst exit both years).
- A daily-Stochastic "extreme" gate on the hunt (great over 3 months, net-negative over 12).
- HTS ribbon entry on the full 5-instrument book (GER40 alone was ≈ −56R / year).
- H12-hunt + HTS-ribbon entry (great in-sample, PF ≈ 1.00 out-of-sample).
- HARD / H12-oscillator-trough entry filter (one quarter at PF < 0.7, sample too thin).
