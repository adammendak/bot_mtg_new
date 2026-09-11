# HA-Hunt M45/M5 + M45 ST compare

Python replay of `pine/ha_hunt_m45_m5_st.pine` for a ~12-month research bake-off.

```bash
python3 -m tools.ha_hunt_st_compare.test_simulator
python3 -m tools.ha_hunt_st_compare.run_bakeoff
python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup
python3 -m tools.ha_hunt_st_compare.run_bakeoff --ha-exit
```

Writes `docs/ha-hunt-m45-m5-st-12mo.md` and `.json`.

OHLC loader order: local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → HistData M1 resampled to M5 (XAU / US100 / GER40 / EURUSD) → Coinbase Exchange 5m (BTC). Never invents prices. Cache is gitignored.
