# HA-Hunt / ST_V3 research compare

Python replay of the locked ST_V3 stack (`StV3Engine` / `pine/ha_hunt_m5_st_v3.pine` / M15 sibling). **Pyramid stays OFF.** Does not change prod Java defaults.

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_fixed_rr_q
python3 -m tools.ha_hunt_st_compare.run_st_indices
```

`run_fixed_rr_q` writes `docs/st-v3-fixed-rr-q.md` + `.json` — last ~90 calendar days ending 2026-09-13, ST ATR 7 / factor 2.0, flat only:

- Entry A = band-cross + ST bias, B = ST flip only, C = band-cross without ST agree.
- Full take-profit at 1:1 and 1:1.5 (no half / BE / runner). Stop = Supertrend line.
- Prod-like baseline on M15+H1: half@1:2 + BE + band-cross runner.

`run_st_indices` writes `docs/ha-hunt-st-params-indices-12mo.md` + `.json`:

`run_st_indices` writes `docs/ha-hunt-st-params-indices-12mo.md` + `.json`:

- **Part A** — classic TV Supertrend ATR length × factor grid (baseline 10/2) on XAU M5+M45 and US100 M15+H1 (optional US100 M5+M45).
- **Part B** — US100 vs US500 vs US30 on both stacks, using the Part A winner or 10/2.

OHLC loader order: local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 (XAU / US100=NSXUSD / US500=SPXUSD / GER40 / EURUSD) → Dukascopy M1 resampled to M5 (US30=`USA30.IDX/USD`; HistData has no DJIA) → Coinbase Exchange 5m (BTC). Never invents prices. Cache is gitignored.

The pyramid A/B runner lives on PR #143 only (`run_pyramid_ab`). This branch does not copy it.
