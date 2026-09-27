# HA-Hunt / ST_V3 research compare

Python replay of the locked ST_V3 stack (`StV3Engine` / `pine/ha_hunt_m5_st_v3.pine` / M15 sibling). **Pyramid stays OFF.** Does not change prod Java defaults.

Copied from PR #151 so this 12-month note uses the same loader + simulator.

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_fixed_rr_12m
```

`run_fixed_rr_12m` writes `docs/st-v3-fixed-rr-12m.md` + `.json` — ~12 months ending 2026-09-13 (entries 2025-09-13 → 2026-09-13), ST ATR 7 / factor 2.0, flat only:

- **A** = band-cross + ST bias, full take-profit at 1:1.5, SL = Supertrend line.
- **Prod-like** = same entry, half@1:2 → BE → band-cross runner (current ST_V3 management).
- Stacks: **M15+H1** (required) and **M5+M45** (cheap extra).

OHLC loader order: local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 (XAU / US100=NSXUSD / US500=SPXUSD / GER40 / EURUSD / extras) → Dukascopy M1 resampled to M5 (US30=`USA30.IDX/USD`; HistData has no DJIA) → Coinbase Exchange 5m (BTC). Never invents prices. Cache is gitignored.

`run_st_indices` is kept so the PR #151 simulator tests pass; this note does not rerun the ST-param grid. The quarterly writer (`run_fixed_rr_q`) stays on PR #151.
