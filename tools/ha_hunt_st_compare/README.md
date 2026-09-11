# HA-Hunt M45/M5 + Supertrend research compare

Python replay of the locked half+BE+HA stack (`pine/ha_hunt_m5_st_v3.pine` / M15 sibling) for ~12-month research. **Pyramid stays OFF.** Does not merge PR #142.

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_st_indices
```

`run_st_indices` writes `docs/ha-hunt-st-params-indices-12mo.md` + `.json`:

- **Part A** — classic TV Supertrend ATR length × factor grid (baseline 10/2) on XAU M5+M45 and US100 M15+H1 (optional US100 M5+M45).
- **Part B** — US100 vs US500 vs US30 on both stacks, using the Part A winner or 10/2.

OHLC loader order: local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 (XAU / US100=NSXUSD / US500=SPXUSD / GER40 / EURUSD) → Dukascopy M1 resampled to M5 (US30=`USA30.IDX/USD`; HistData has no DJIA) → Coinbase Exchange 5m (BTC). Never invents prices. Cache is gitignored.

The pyramid A/B runner lives on PR #143 only (`run_pyramid_ab`). This branch does not copy it.
