# MMS — MastermindZX mean-reversion

Closed-bar port of the [MastermindZX MMS](https://mastermindzx.pl/) rules for BTC, XAU/GOLD and US100/NQ. Bot engine: `MmsEngine` / `HtsVariant.MMS`. Pine overlay: `pine/mms_mean_reversion.pine`.

Site BTCUSDT backtests are monthly-optimised. This repo does not copy win-rate numbers from those runs.

## Rules

Envelope: TMA (SMA-of-SMA) ± ATR × multiplier, or SMA ± ATR × multiplier (Bollinger-style). Inputs: period (default 20), ATR multiplier (default 2.0). Evaluated on **closed** entry-TF bars only — the forming bar is dropped.

| Side | Setup | Trigger | Open |
| --- | --- | --- | --- |
| SHORT | Price reaches the **upper** band, wait for that TF candle to close | First reactive candle **down** (close &lt; open) at the start of a new TF bar | SHORT ×1 |
| LONG | Price reaches the **lower** band, wait for close | First reactive candle **up** | LONG ×1 |

- **TP:** opposite band. Hitting it can close the base (and later flip after a new reaction).
- **SL:** fixed percent of entry price (input; site default **2%**; site backtests often 1–1.9%). **No** trailing stop, **no** instant break-even.
- **Risk:** ×1 ≈ 1% account risk for a ~1% price move. After a full SL sequence the unit drops to **×0.1** until a winning opposite-band TP restores **×1**.
- **Add-on ×1** after one confirming candle, and an H1 Stochastic(14,3,3) extreme filter for adds, exist as flags. **Default off.**

Timeframes: bot default **M15** (scan on `:00/:15/:30/:45`). Pine / constructor inputs allow M10 / M20 / M30 / H1; the Capital adapter already fetches M5 / M15 / H1. Prefer closed bars.

## Universe and epics

| Code | Capital epic (env override) | Notes |
| --- | --- | --- |
| BTC | `BTCUSD` (`SDD_EPIC_BTC`) | Weekend-open. OKX perpetual is already mapped as `BTC-USDT-SWAP` via `OkxSymbol.BTC` — not scanned by this parked Capital variant. |
| XAU | `GOLD` (`SDD_EPIC_XAU`) | Weekdays only (Warsaw weekend filter). |
| US100 | `US100` (`SDD_EPIC_US100`) | NQ proxy on Capital. Weekdays only. |

## How to toggle MMS

`HtsVariant.MMS` is **parked**. It does not scan and cannot place orders.

1. Unpark: remove `this == MMS` from `HtsVariant.parked()`.
2. Scan then follows `HTS_SCAN_ENABLED` / `hts.scan` (existing master switch).
3. Demo fills still require `HTS_EXECUTION_ENABLED` / `hts.execution`. **Do not** turn on `EXECUTION_ENABLED` (SDD) or `HTS_LIVE_EXECUTION_ENABLED` for this variant. `MMS.live()` is false.

Book when unparked: `demo` (“Account m15”), same book as HA4 — unpark only if you accept sharing that sub-account.

## Default params (BTC / XAU / US100)

Same defaults on all three names:

| Input | Default |
| --- | --- |
| Entry TF | M15 |
| Envelope | TMA ± 2.0 × ATR(20) |
| SL | 2% of entry |
| Risk unit | ×1, then ×0.1 after SL until TP |
| Add-on / H1 stoch | off |
| Live / execution | off (parked) |
