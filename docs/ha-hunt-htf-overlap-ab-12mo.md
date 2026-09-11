# HA-Hunt ST v3 — HTF M45 overlap / squeeze filter A/B (~12m)

Research only. **Do not merge as a default-ON change.** Pine switch stays **OFF** unless
the book clearly improves. Same simulator, window, and locked stack as PR #144.

Locked: half TP1 @ 1:2 → BE → HA flip. Slow 144, fast 33, M45 structure gate ON,
capReg 2, **flat only**, **ST 7/2**, pyramid OFF, entry-TF `bandCrossStrict` OFF
(unless the secondary table).

Window: `2025-09-11` → `2026-09-11` UTC. Not Capital mid unless credentials
were present (then preferred).

## Call

**NO — keep OFF.** The pine switch is useful as a toggle. Do not flip the default.

Hypothesis **verified**: ON usually lifts WR a point and cuts n, but **combined sumR falls −24.1R**. Only US100 M5+M45 improved (+8.5R). The locked stars lost: XAU M5+M45 −1.4R, US100 M15+H1 −7.7R. US500 M5+M45 collapsed (−21.0R, WR down). OFF cells match PR #144 ST 7/2 exactly.

Entry-TF `bandCrossStrict` ON is also a no: it helps the weak US100 M5+M45 book and wrecks XAU / US100 M15+H1 / US500.

## Part A — HTF overlap filter OFF vs ON (ST 7/2)

| symbol | stack | filter | n | WR% | sumR | PF | DD |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| XAU | M5+M45 | OFF (lock) | 269 | 39.0 | +48.1 | 1.29 | 9.7 |
| XAU | M5+M45 | ON | 231 | 39.4 | +46.7 | 1.33 | 12.7 |
| US100 | M5+M45 | OFF (lock) | 311 | 35.7 | +16.5 | 1.08 | 22.0 |
| US100 | M5+M45 | ON | 258 | 37.2 | +25.1 | 1.15 | 18.8 |
| US100 | M15+H1 | OFF (lock) | 179 | 43.0 | +53.2 | 1.52 | 6.0 |
| US100 | M15+H1 | ON | 145 | 44.1 | +45.5 | 1.56 | 7.6 |
| US500 | M15+H1 | OFF (lock) | 180 | 38.9 | +21.8 | 1.20 | 12.8 |
| US500 | M15+H1 | ON | 158 | 39.9 | +19.2 | 1.20 | 11.5 |
| US500 | M5+M45 | OFF (lock) | 260 | 36.5 | +22.4 | 1.14 | 11.2 |
| US500 | M5+M45 | ON | 242 | 33.9 | +1.4 | 1.01 | 12.0 |

### Deltas (ON − OFF)

| symbol | stack | filter | Δn | ΔWR | ΔsumR | ΔPF | ΔDD |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| XAU | M5+M45 | ON − OFF | -38 | +0.4 | -1.4 | +0.04 | +3.0 |
| US100 | M5+M45 | ON − OFF | -53 | +1.5 | +8.5 | +0.07 | -3.2 |
| US100 | M15+H1 | ON − OFF | -34 | +1.1 | -7.7 | +0.04 | +1.6 |
| US500 | M15+H1 | ON − OFF | -22 | +1.0 | -2.6 | +0.00 | -1.3 |
| US500 | M5+M45 | ON − OFF | -18 | -2.7 | -21.0 | -0.13 | +0.8 |

Overlap = last closed M45 fast+slow RMA ribbons intersect (not clear either side).
Long clear = `m45FLo > m45SUp`. Short clear = `m45FUp < m45SLo`. When ON, both
sides are skipped while overlapping. `reqM45Struct` is still stack OR leave —
not this skip.

## Part B — entry-TF `bandCrossStrict` OFF vs ON (overlap OFF, ST 7/2)

Secondary. Entry-TF fast band must be fully clear of slow (M5 or M15). Does not
look at M45.

| symbol | stack | filter | n | WR% | sumR | PF | DD |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| XAU | M5+M45 | loose (lock) | 269 | 39.0 | +48.1 | 1.29 | 9.7 |
| XAU | M5+M45 | strict ON | 211 | 39.3 | +36.3 | 1.28 | 12.6 |
| US100 | M5+M45 | loose (lock) | 311 | 35.7 | +16.5 | 1.08 | 22.0 |
| US100 | M5+M45 | strict ON | 203 | 37.4 | +23.1 | 1.18 | 10.7 |
| US100 | M15+H1 | loose (lock) | 179 | 43.0 | +53.2 | 1.52 | 6.0 |
| US100 | M15+H1 | strict ON | 116 | 39.7 | +25.5 | 1.37 | 8.8 |
| US500 | M15+H1 | loose (lock) | 180 | 38.9 | +21.8 | 1.20 | 12.8 |
| US500 | M15+H1 | strict ON | 121 | 36.4 | +5.2 | 1.07 | 10.8 |
| US500 | M5+M45 | loose (lock) | 260 | 36.5 | +22.4 | 1.14 | 11.2 |
| US500 | M5+M45 | strict ON | 201 | 33.8 | -0.4 | 1.00 | 16.6 |

## Series

- **US100**: histdata_m1_resampled_m5  n_m5=67255  2025-09-11T04:00:00+00:00 → 2026-09-04T20:10:00+00:00  (HistData NSXUSD = Nasdaq 100 cash (NQ / US100). Capital epic US100. Same series as PR #139/#143.)
- **US500**: histdata_m1_resampled_m5  n_m5=67242  2025-09-11T04:00:00+00:00 → 2026-09-04T20:10:00+00:00  (HistData SPXUSD = S&P 500 cash (ES / US500). Capital epic US500. Analogous vendor/method to US100.)
- **XAU**: histdata_m1_resampled_m5  n_m5=69637  2025-09-11T04:00:00+00:00 → 2026-09-04T20:55:00+00:00  (HistData XAUUSD (spot gold). Same series as PR #139/#143.)

Not Capital mid. HistData last bar ~2026-09-04 (vendor lag vs window end 2026-09-11). Same as #144.

Rerun: `python3 -m tools.ha_hunt_st_compare.run_htf_overlap_ab`
