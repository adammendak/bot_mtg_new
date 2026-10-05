# ST_V3 forward test — 14-day review and the changes it drove (2026-10-05)

Source: `hts_trades` (prod) 2026-09-21 → 2026-10-05 and a research replay
(`tools/h4_ha_cloud_bt.run_st_v3`, Capital demo mids, no fees; runner = be_haflip, NOT the live band-cross exit).

## Forward result (R, estimated — `pnl` is not recorded)

| book | variant | closed | sum R | avg R |
|---|---|--:|--:|--:|
| hts (m5) | M5_ST_V3 | 60 | -15.8 | -0.26 |
| demo (m15) | M15_ST_V3B | 30 | -14.1 | -0.47 |
| mms | M15_ST_V3 | 54 | -24.1 | -0.45 (31/55 hour-groups duplicate `demo`) |
| swing (h1) | H1_ST_V3 | 5 | +0.8 | +0.16 |

Only EURUSD was net positive (+10.9R on m5+m15). GER40 won 1 of 22 (-17.1R), XAU -12.1R, BTC -9.7R.
~78% of entries never reach TP1 and stop at -1R; TP1 hits (20-22%) mostly run (+2R). Break-even WR ≈ 33-37%, observed ≈ 28%.

## Backtest vs forward, same days

| | backtest 21.09-05.10 | live |
|---|--:|--:|
| M15 / H1-ST (5 satellite tickers) | -10.1R (GER40 0/10, BTC 1/10) | -14.1R |
| M5 / M45-ST | +0.6R | -15.8R |

M15: live ≈ backtest → a bad regime window, not an implementation defect. M5: ~16R worse live than the
research replay → unexplained (candidates: band-cross vs HA-flip runner, M5 spread vs a ~0.12% EURUSD stop,
entry latency). Needs a band-cross backtest runner; not solved here.

The `HtsVariant` javadoc claims PF 2.10 IS / 1.65 OOS on all ten tickers. The research tool in `tools/`
(be_haflip runner) does NOT reproduce that: the pooled M15 book is PF 1.04 IS / 1.10 OOS (12mo each).
The claim depends on the band-cross exit's fat tail (rare +10R..+35R trends); a 2-week forward sample is
expected to look like "many small losses" until one lands.

## FX screen (M15 entry / H1-ST, 12mo IS 2025-10→2026-10 and OOS 2024-10→2025-10, no fees)

| pair | IS PF / ΣR | OOS PF / ΣR | |
|---|--:|--:|---|
| USDCAD | 1.35 / +19.1 | 1.43 / +26.4 | added (live on mms book) |
| NZDUSD | 1.07 / +5.0 | 1.18 / +12.6 | added |
| USDCHF | 1.13 / +8.4 | 1.07 / +4.1 | added, thin — needs spread check |
| AUDUSD | 1.02 / +1.1 | 1.13 / +8.3 | added, thin |
| GBPUSD, USDJPY, EURJPY, GBPJPY | fail one/both windows | | not added |

## Session filter (pooled M15 book, UTC entry hour)

| filter | IS n / ΣR / PF | OOS n / ΣR / PF |
|---|---|---|
| none | 1838 / +26.1 / 1.04 | 1451 / +51.0 / 1.10 |
| drop 14-20 | 1345 / +37.3 / 1.08 | 1050 / +41.4 / 1.11 |
| keep 07-13 | 585 / +28.8 / 1.14 | 452 / +31.2 / 1.19 |

Not consistent enough to apply everywhere (OOS ΣR falls), so it ships only as the A/B arm on `M15_ST_V3` (mms book).

## What changed

1. `applyClose` estimate: a vanished position whose late mark ran beyond the untouched stop is booked AT the stop
   (R -1.0, STOP). The old estimate booked stop-outs at -1.2..-1.6R and labelled them MANUAL (54 rows in 14 days).
   Any outcome ≤ -0.85R is now STOP/TRAIL, never MANUAL. `pnl` (cash) is still null — needs the Capital activity feed.
2. `HTS_OBSERVE_ONLY_SYMBOLS` (default `GER40,XAU,BTC,US500,US30,XAG,J225,USDJPY`): ST_V3 signals on these are
   scanned, persisted and mailed but not executed. Open positions are still managed.
3. `HTS_CLUSTER_CAP` (default 2): max same-direction open trades per correlation cluster (equity / metal / crypto /
   USD side) per book. `0` disables.
4. `HTS_SESSION_FILTERS` (default `M15_ST_V3:7-14`): `M15_ST_V3` (mms book) enters only 07-14 UTC; `M15_ST_V3B`
   (demo) is the unfiltered control on the same tickers.
5. FX pairs USDCAD / NZDUSD / USDCHF / AUDUSD added to the broad `M15_ST_V3` universe (satellites unchanged).
