#!/usr/bin/env python3
"""
kalman_trend_bt.py - backtest of "Kalman Trend Levels [BigBeluga]" (pine/kalman_trend_levels_arrows.pine) as a stop-and-reverse
system: long while short Kalman > long Kalman, short otherwise. The Kalman filter is a line-by-line port of the Pine function
(R = 0.01, Q = 0.1, error_meas = R * length, process noise Q / length). Retest signals are not traded.

  signal on the CLOSE of the bar where the lines cross  ->  fill at the OPEN of the next bar, cost per side as a fraction of price
  one unit of notional, returns are summed (no compounding)

Grid over the short / long length, instruments US100, US500, BTCUSD, timeframes M15 and H1, last 12 months, plus halves
(first half vs second half) and a "pick the best on the first half, test on the second" check.

usage: python tools/kalman_trend_bt.py [--months 12]
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".candle_cache"
COST = {"US100": 0.0001, "US500": 0.0001, "BTCUSD": 0.0004}      # per side (spread + slippage), fraction of price
SHORTS = [10, 20, 30, 50, 75]
LONGS = [100, 150, 200, 300, 400]


def load(epic: str, res: str) -> pd.DataFrame:
    df = pd.read_csv(CACHE / f"{epic}_{res}.csv", parse_dates=["time"])
    df["time"] = pd.to_datetime(df["time"], utc=True)
    return df.drop_duplicates("time").set_index("time").sort_index()


def kalman(c: np.ndarray, length: int, R: float = 0.01, Q: float = 0.1) -> np.ndarray:
    """Port of kalman_filter() from the Pine script."""
    out = np.full(len(c), np.nan)
    est = np.nan; err = 1.0; meas = R * length
    for i in range(len(c)):
        if np.isnan(est):
            est = c[i - 1] if i > 0 else np.nan
        pred = est
        gain = err / (err + meas)
        est = pred + gain * (c[i] - pred)
        err = (1 - gain) * err + Q / length
        out[i] = est
    return out


def run(df: pd.DataFrame, k_short: np.ndarray, k_long: np.ndarray, cost: float, t0: pd.Timestamp, t1: pd.Timestamp) -> dict:
    up = k_short > k_long
    op, idx = df.open.to_numpy(), df.index
    n = len(df)
    rets = []; sides = []
    pos = 0; entry = 0.0
    for i in range(300, n - 1):                                  # burn-in for the filters
        if i > 0 and up[i] != up[i - 1]:
            want = 1 if up[i] else -1
            if idx[i + 1] < t0 or idx[i + 1] > t1:
                if idx[i + 1] > t1:
                    break
                pos, entry = want, op[i + 1]                     # keep the position state before the window
                continue
            px = op[i + 1]
            if pos != 0:
                rets.append(pos * (px / entry - 1) - 2 * cost); sides.append(pos)
            pos, entry = want, px
    if pos != 0:                                                 # close at the last bar of the window
        last = df.loc[(df.index >= t0) & (df.index <= t1)].close.iloc[-1]
        rets.append(pos * (last / entry - 1) - cost); sides.append(pos)
    r = np.array(rets); s = np.array(sides)
    if len(r) == 0:
        return dict(trades=0, net=0.0, pf=np.nan, win=np.nan, dd=0.0, long=0.0, short=0.0)
    eq = np.cumsum(r)
    w = r[r > 0].sum(); l = -r[r < 0].sum()
    return dict(trades=len(r), net=r.sum() * 100, pf=w / l if l > 0 else np.inf, win=(r > 0).mean() * 100,
                dd=(np.maximum.accumulate(eq) - eq).max() * 100, long=r[s > 0].sum() * 100, short=r[s < 0].sum() * 100)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", type=int, default=12)
    a = ap.parse_args()
    out_dir = HERE / "out"; out_dir.mkdir(exist_ok=True)
    allrows = []
    for epic in ("US100", "US500", "BTCUSD"):
        for res, tf in (("HOUR", "H1"), ("MINUTE_15", "M15")):
            df = load(epic, res)
            t1 = df.index[-1]; t0 = t1 - pd.DateOffset(months=a.months); tm = t0 + (t1 - t0) / 2
            bh = (df.loc[df.index >= t0].close.iloc[-1] / df.loc[df.index >= t0].open.iloc[0] - 1) * 100
            c = df.close.to_numpy()
            ks = {L: kalman(c, L) for L in sorted(set(SHORTS + LONGS))}
            rows = []
            for s in SHORTS:
                for l in LONGS:
                    if s >= l:
                        continue
                    full = run(df, ks[s], ks[l], COST[epic], t0, t1)
                    h1 = run(df, ks[s], ks[l], COST[epic], t0, tm)
                    h2 = run(df, ks[s], ks[l], COST[epic], tm, t1)
                    free = run(df, ks[s], ks[l], 0.0, t0, t1)
                    rows.append(dict(epic=epic, tf=tf, short=s, long=l, trades=full["trades"], net=full["net"], pf=full["pf"],
                                     win=full["win"], dd=full["dd"], long_pnl=full["long"], short_pnl=full["short"],
                                     net_nocost=free["net"], h1=h1["net"], h2=h2["net"]))
            d = pd.DataFrame(rows); allrows.append(d)
            print(f"\n=== {epic} {tf}  {t0:%Y-%m-%d} .. {t1:%Y-%m-%d}  buy&hold {bh:+.1f}%  cost {COST[epic]*100:.3f}%/side ===")
            piv = d.pivot(index="short", columns="long", values="net").round(1)
            print("net % (sum of trade returns after costs), rows = short length, cols = long length")
            print(piv.to_string())
            piv2 = d.pivot(index="short", columns="long", values="trades")
            print("trades:\n" + piv2.to_string())
            dflt = d[(d.short == 50) & (d.long == 150)].iloc[0]
            print(f"default 50/150: trades {dflt.trades:.0f}, net {dflt.net:+.1f}%, PF {dflt.pf:.2f}, win {dflt.win:.0f}%, "
                  f"long {dflt.long_pnl:+.1f}% short {dflt.short_pnl:+.1f}%, no-cost {dflt.net_nocost:+.1f}%, halves {dflt.h1:+.1f}% / {dflt.h2:+.1f}%")
            print(f"grid: {int((d.net > 0).sum())}/{len(d)} combos positive after costs, median net {d.net.median():+.1f}%, "
                  f"median no-cost {d.net_nocost.median():+.1f}%, median trades {d.trades.median():.0f}")
            best = d.loc[d.h1.idxmax()]
            print(f"selected on 1st half: {best.short:.0f}/{best.long:.0f} (1st half {best.h1:+.1f}%) -> 2nd half {best.h2:+.1f}%")
    res = pd.concat(allrows)
    res.to_csv(out_dir / "kalman_trend_grid.csv", index=False)
    print("\n=== summary per instrument/timeframe ===")
    g = res.groupby(["epic", "tf"]).agg(combos=("net", "size"), pos=("net", lambda x: (x > 0).sum()), med_net=("net", "median"),
                                         med_nocost=("net_nocost", "median"), best=("net", "max"), worst=("net", "min"),
                                         med_trades=("trades", "median"), med_dd=("dd", "median"))
    print(g.round(1).to_string())
    both = res.assign(both_pos=(res.h1 > 0) & (res.h2 > 0)).groupby(["epic", "tf"]).both_pos.mean() * 100
    print("\nshare of combos positive in BOTH halves (%):\n" + both.round(0).to_string())


if __name__ == "__main__":
    main()
