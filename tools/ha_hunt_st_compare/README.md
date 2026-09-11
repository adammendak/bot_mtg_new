# HA-Hunt M45/M5 + Supertrend research compare

Python replay of the locked half+BE+HA stack (`pine/ha_hunt_m5_st_v3.pine` / M15 sibling) for ~12-month research. **Pyramid stays OFF.** Does not merge PR #142.

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_htf_overlap_ab
```

`run_htf_overlap_ab` writes `docs/ha-hunt-htf-overlap-ab-12mo.md` + `.json` (same ~12m window / half+BE+HA lock as PR #144, ST **7/2**, pyramid OFF):

- **Part A** — `blockHtfBandOverlap` OFF vs ON on XAU M5+M45, US100 M5+M45 / M15+H1, US500 M15+H1 and M5+M45.
- **Part B** — entry-TF `bandCrossStrict` OFF vs ON (overlap still OFF) on the same names.

`run_st_indices` (PR #144) is the Supertrend grid + US100/US500/US30 compare. Optional here.

OHLC loader order: local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 (XAU / US100=NSXUSD / US500=SPXUSD / GER40 / EURUSD) → Dukascopy M1 resampled to M5 (US30=`USA30.IDX/USD`; HistData has no DJIA) → Coinbase Exchange 5m (BTC). Never invents prices. Cache is gitignored.

The pyramid A/B runner lives on PR #143 only (`run_pyramid_ab`). This branch does not copy it. Do **not** re-add pyramid.
