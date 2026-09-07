# MMS — MastermindZX mean-reversion

Closed-bar port of the [MastermindZX MMS](https://mastermindzx.pl/) rules for BTC, XAU/GOLD and US100/NQ. Bot engine: `MmsEngine` / `HtsVariant.MMS`. Pine overlay: `pine/mms_mean_reversion.pine`.

Site BTCUSDT backtests are monthly-optimised. This repo does not copy win-rate numbers from those runs.

Written site rules and the MT5 Strategy Tester clips on BTCUSD do **not** fully agree. The bot exposes both as inputs; it does not pick a winner.

## Rules (shared)

Envelope: TMA (SMA-of-SMA) ± ATR × multiplier, or SMA ± ATR × multiplier (Bollinger-style). Inputs: period (default 20), ATR multiplier (default 2.0). Evaluated on **closed** entry-TF bars only — the forming bar is dropped.

| Side | Setup | Trigger | Open |
| --- | --- | --- | --- |
| SHORT | Price reaches the **upper** band, wait for that TF candle to close | First reactive candle **down** (close &lt; open) at the start of a new TF bar | SHORT ×1 |
| LONG | Price reaches the **lower** band, wait for close | First reactive candle **up** | LONG ×1 |

- **SL is mandatory. No trailing. No instant break-even.**
- **Risk:** ×1 ≈ 1% account risk for a ~1% price move. After a **full SL outside the bands** (the ~2% base stop, not an add-on wick) the unit drops to **×0.1** until the first profitable setup restores **×1**. Site backtest discussion also mentions ×0.01 — that micro unit is documented only, not applied automatically.
- Timeframes: bot default **M15**. Prefer **M10–M30** for algo; **H1** for a manual base. **Exclude M5** (noise). H4 = trend/range context; D1 = bias / sizing. Capital already fetches M5 / M15 / H1; Pine notes M10 / M20 / M30.

## TP modes (`MmsEngine.TpMode`)

| Mode | Default? | What it is | Source |
| --- | --- | --- | --- |
| `OPPOSITE_BAND` | **yes** | TP (and a possible flip of the base) when price reaches the **far** envelope | Written site |
| `FIXED_1R` | no | TP = entry ± SL distance (1:1 RR) | MT5 tester clips on BTCUSD |

`FIXED_1R` SL placement (`MmsEngine.SlMode`):

| SL | What it is |
| --- | --- |
| `PCT` | % of entry (site default **2%**; site BTs often 1–1.9%; example BB M15 uses **1.7%**) |
| `WICK_EXTREME` | Stop **beyond the piercing / reaction wick** (high of the upper pierce for shorts, low of the lower pierce for longs). Falls back to `%` if the wick is on the wrong side of entry. |

Tester-clip presets (not live defaults): `MmsEngine.Params.testerFixed1r()` and `testerBbStoch()`.

## Stochastic (optional, default off)

- **H1 Stoch(14,3,3) extreme** — optional for **entries and adds**. Long: %K ≤ 20; short: %K ≥ 80.
- **Entry-TF Stoch cross** — optional, matches a second tester clip: pierce BB + Stoch OB/OS + reversal candle close + %K/%D cross, then enter the next open (bot fires on the **closed** reversal bar so the live fill is that next open). Still 1:1 RR when `FIXED_1R` is on.

Neither filter is required for the written-site path.

## Add-ons (optional, default off)

After **one full confirming candle** / new interval (the immediate next closed bar, same colour as the base), an add-on ×1 may be taken:

- Add-on SL at the **local wick**. Extra move vs the base entry must be **≤ 1%**, typically **&lt; 0.5%**. Reject the add if the wick is wider.
- Stoch-filtered add-ons use a **fixed 1% SL** instead of the wick.
- If the add-on SL hits: **do not retry adds**. Keep the **base SL (~2%)**. Wait for an entirely new setup. An add-on STOP does **not** cut the sequential risk unit — only a full base SL outside the bands does.
- The add window is that single next interval. Later bars are not a second chance.

`MmsEngine.evaluateAdd` encodes the confirming-candle + wick-cap + no-retry book. The execution gate allows **one** add only when `addOnEnabled` is on, exactly one OPEN base exists, and this setup has not already taken or stopped an add. Flag default **off**; MMS is parked.

## Parameterization notes

- Example **BB M15** from the site (not the bot default): period **41**, deviation **3.2**, SL **1.7%** (`Params.siteBbM15Example()`).
- Adaptive talk on the site uses the prior **3 days'** params — documented only, not implemented.
- Bot default remains TMA(20) ± 2.0×ATR, SL 2%, `OPPOSITE_BAND`, `PCT`.

### Config (`app.mms.*`) — the site re-optimises monthly, so these are env-driven

| env | default | meaning |
| --- | --- | --- |
| `MMS_ATR_PERIOD` | `20` | envelope + Wilder-ATR period |
| `MMS_ATR_MULT` | `2.0` | band width in ATRs |
| `MMS_SL_PCT` | `0.02` | `%`-of-price base stop |
| `MMS_MODE` | `TMA_ATR` | `TMA_ATR` or `BB_ATR` (SMA centre) |
| `MMS_TP_MODE` | `OPPOSITE_BAND` | or `FIXED_1R` (1:1 from the stop) |
| `MMS_SL_MODE` | `PCT` | or `WICK_EXTREME` |
| `MMS_ADD_ON_ENABLED` | `false` | one-bar ×1 add-on |
| `MMS_STOCH_FILTER_ENABLED` | `false` | H1 Stoch extreme gate |
| `MMS_STOCH_CROSS_ENABLED` | `false` | entry-TF %K/%D cross gate |
| `MMS_REACTION_WINDOW` | `8` | closed bars after a touch that still accept the first reaction |
| `MMS_SYMBOLS` | *(blank = all)* | CSV subset, e.g. `MMS_SYMBOLS=BTC` for a BTC-only forward test |

Bad enum text falls back to the site default. `MmsEngine` reads these via
`Params.fromConfig` at construction (restart to re-apply).

## Discipline (document only)

Prefer spot / P2P over CFD. Avoid overnight when possible. Monday D1 / W1 is often fake-marking — observation, not a setup hunt.

## Universe and epics

| Code | Capital epic (env override) | Notes |
| --- | --- | --- |
| BTC | `BTCUSD` (`SDD_EPIC_BTC`) | Weekend-open. OKX perpetual is already mapped as `BTC-USDT-SWAP` via `OkxSymbol.BTC` — not scanned by this parked Capital variant. |
| XAU | `GOLD` (`SDD_EPIC_XAU`) | Weekdays only (Warsaw weekend filter). |
| US100 | `US100` (`SDD_EPIC_US100`) | NQ proxy on Capital. Weekdays only. |

## How to toggle MMS

`HtsVariant.MMS` is **parked**. It does not scan and cannot place orders.

1. Unpark: remove `|| this == MMS` from `HtsVariant.parked()` (one line).
2. Scan then follows `HTS_SCAN_ENABLED` / `hts.scan` (existing master switch).
3. Demo fills still require `HTS_EXECUTION_ENABLED` / `hts.execution`. **Do not** turn on `EXECUTION_ENABLED` (SDD) or `HTS_LIVE_EXECUTION_ENABLED` for this variant. `MMS.live()` is false.

Book when unparked: `demo` (“Account m15”), same book as HA4 — unpark only if
you accept sharing that sub-account. For an isolated **BTC-only forward test**
set `MMS_SYMBOLS=BTC` so only BTC is scanned (the 12-month backtest had BTC as
the least-bad symbol; XAU was the worst — see `pr122-mms-review.md`).

## Default params (BTC / XAU / US100)

Same defaults on all three names:

| Input | Default |
| --- | --- |
| Entry TF | M15 (algo: M10–M30; not M5) |
| Envelope | TMA ± 2.0 × ATR(20) |
| TP | `OPPOSITE_BAND` (tester clips: `FIXED_1R`) |
| SL | `PCT` 2% of entry (tester clips: `WICK_EXTREME`) |
| Risk unit | ×1, then ×0.1 after a full base SL until a winning TP (add-on SL does not cut) |
| Add-on / H1 stoch / Stoch cross | off (add-on extra ≤ 1%, typically &lt; 0.5%; one next-interval add, no retry) |
| Live / execution | off (parked) |
