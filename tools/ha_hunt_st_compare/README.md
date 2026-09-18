# HA-Hunt / ST_V3 / SATS research compare

Python replay of the locked ST_V3 stack (`StV3Engine` / `pine/ha_hunt_m5_st_v3.pine` / M15 sibling) plus a SATS v1.13.1 Default approximation. **Pyramid stays OFF.** Does not change prod Java defaults.

```bash
python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.test_sats
python3 -m tools.ha_hunt_st_compare.run_sats_vs_st_v3
```

`run_sats_vs_st_v3` writes `docs/sats-vs-st-v3-12m.md` + `.json` — ~12 months ending 2026-09-13 (entries 2025-09-13 → 2026-09-13):

- **SATS Default** on chart TF = entry TF (M15 and M5): TQI ON, Fixed TP 1/2/3R thirds, SL pivot+1.5ATR capped at 4.0 ATR, timeout 100, flip-exit.
- **ST_V3 prod-like** M15+H1 and M5+M45: band-cross + ST bias, half@1:2 → BE → band-cross runner.
- **A/B:** TQI≥0.5 gate on ST_V3 band-cross; character-flip exit instead of / in addition to the band runner.

OHLC loader order: local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 → Dukascopy M1 resampled to M5 (US30) → Coinbase Exchange 5m (BTC). Never invents prices. Cache is gitignored.

`run_fixed_rr_12m` / `run_st_indices` are kept so prior simulator tests still pass; this note does not rerun those grids.
