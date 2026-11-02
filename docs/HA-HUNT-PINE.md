# HA-Hunt Pine overlays

Research / chart overlays only. **Java `HaHuntEngine` / prod HTS is unchanged.** No Heroku flags.

| File | Role | Chart |
| --- | --- | --- |
| `pine/ha_hunt_cloud.pine` | Engine 1:1 overlay. **HA4 default:** H4 Heikin-Ashi hunt, M15 entry, H1 ATR stop | M15 (HA4) |
| `pine/ha_hunt_m15_h1_supertrend.pine` | **RESEARCH** — same M15 entry as HA4, HTF filter = **H1 Supertrend** | **M15** |

Put both on a normal-candle M15 chart (HA is computed inside) to compare the HTF gate.

## What differs

| | HA4 (`ha_hunt_cloud.pine`) | M15 / H1 Supertrend (research) |
| --- | --- | --- |
| HTF filter | Last **closed H4 HA** colour | Last **closed H1 Supertrend** direction |
| Entry (M15) | HA flip + RMA stack (`close > RMA33 > RMA100`); optional band-cross | Same |
| Daily PP | Previous 21:00-UTC session (off for crypto) | Same |
| Stop | H1 ATR(14) × 2.5 | Same (input) |
| Exit | H4 HA flips against **or** stop | H1 Supertrend flips against **or** stop. Optional M15 HA flip-against (default **off**) |
| Shade / HUD | H4 HA regime | H1 Supertrend direction; HUD reads **H1 Supertrend filter** |

HA4 also ANDs a mid-TF “WITH” confirm (H1 HA **or** H1 RMA stack). The research script does **not** — H1 Supertrend *is* the H1 gate.

## Supertrend formula (research script)

Classic TradingView `ta.supertrend(factor, atrPeriod)`:

- **Source:** `hl2` = `(high + low) / 2`
- **ATR:** `ta.atr` = **Wilder RMA** of True Range (not SMA)
- **Bands:** `basicUp = hl2 - factor × ATR`, `basicDn = hl2 + factor × ATR`, then ratchet (`finalUp` / `finalDn`)
- **Direction (TV built-in):** `-1` = bull (price above the line), `+1` = bear
- **Defaults here:** ATR period **10**, factor **2.0** (TV / `Supertrend.java` default factor is 3.0; Java uses `+1` = up — opposite sign, same band math)

Closed H1 only: `request.security(..., expr[1], lookahead_off)`.
