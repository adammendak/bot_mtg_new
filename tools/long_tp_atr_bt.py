#!/usr/bin/env python3
"""
long_tp_atr_bt.py - ladder with a STATIC take-profit vs a take-profit sized from ATR (docs/DAILY-SCALP-LONGONLY.md).

Same model as long_tp_stack_bt.py (long only, no SL, ladder LADDER_0.5, K positions, M5 bars, equity marked at the bar low,
5% margin, 50% close-out, spread deducted per TP, financing on notional). Differences:
  * every position has its own distance D_i: static, or k x ATR14(D1, previous completed days) as % of price, clamped;
  * every entry is sized so one TP earns g % of the account: weight w_i = g / D_i (account multiples of notional).

usage: python tools/long_tp_atr_bt.py <candle cache dir>
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_stack_bt as bt  # noqa: E402

STEP = 0.5            # ladder step, % below the lowest open entry
K_MAX = 10
D_MIN, D_MAX = 0.2, 3.0   # clamp of the ATR distance, % of price
G_LIST = [0.1, 0.2]
# index -> (static D %, ATR multiples to try)
PLAN = {
    "GER40": (0.5, [0.2, 0.3, 0.37, 0.5, 0.7]),
    "SP500": (0.75, [0.3, 0.5, 0.66, 0.9, 1.2]),
}


def atr_pct_per_bar(df: pd.DataFrame) -> np.ndarray:
    d = df.resample("1D").agg({"h": "max", "l": "min", "c": "last"}).dropna()
    d = d[d.index.dayofweek < 5]
    pc = d["c"].shift(1)
    tr = pd.concat([d["h"] - d["l"], (d["h"] - pc).abs(), (d["l"] - pc).abs()], axis=1).max(axis=1)
    pct = (tr.rolling(14).mean() / d["c"] * 100).shift(1)        # known at the open: previous days only
    cal = pct.reindex(pd.date_range(d.index[0], d.index[-1] + pd.Timedelta(days=1), freq="1D", tz=d.index.tz)).ffill()
    day = df.index.normalize()
    return cal.reindex(day).ffill().bfill().to_numpy()


def simulate(df, idx, static_d, k_atr, atr_pct, fin_year_pct=bt.FIN_YEAR_PCT):
    """Returns per-bar unit-g arrays (g = 1 % of the account per TP) and the position log."""
    o = df["o"].tolist(); h = df["h"].tolist(); l = df["l"].tolist()
    n = len(o)
    ev = bt.event_map(df, idx, "DAILY_OPEN")
    fin_bar = fin_year_pct / 100.0 / 365.0 / 288.0
    cost = bt.COST_PCT / 100.0
    unit = 0.01                                             # g = 1 %
    E, TP, DI, WI, ENT = [], [], [], [], []
    sumw = sumwinv = 0.0
    R = F = 0.0
    R_a = np.zeros(n); M_a = np.zeros(n); F_a = np.zeros(n); W_a = np.zeros(n)
    C_a = np.zeros(n, dtype=np.int32)
    log = []
    pid = []
    lowest = math.inf
    s = STEP / 100.0
    for i in range(n):
        price = None
        if i in ev and not E:
            price = ev[i]
        elif E and l[i] <= lowest * (1 - s):
            price = min(o[i], lowest * (1 - s))
        if price is not None and len(E) < K_MAX:
            if k_atr is None:
                d_i = static_d / 100.0
            else:
                d_i = min(max(k_atr * atr_pct[i], D_MIN), D_MAX) / 100.0
            w = unit / d_i
            E.append(price); TP.append(price * (1 + d_i)); DI.append(d_i); WI.append(w); ENT.append(i)
            sumw += w; sumwinv += w / price
            lowest = min(lowest, price)
            log.append([i, price, -1, d_i]); pid.append(len(log) - 1)
        cnt = len(E)
        if cnt:
            M_a[i] = l[i] * sumwinv - sumw
            F += sumw * fin_bar
        R_a[i] = R; F_a[i] = F; W_a[i] = sumw; C_a[i] = cnt
        if cnt:
            hi = h[i]; j = 0; hit = False
            while j < len(E):
                if hi >= TP[j] and ENT[j] <= i:
                    R += WI[j] * (DI[j] - cost)
                    sumw -= WI[j]; sumwinv -= WI[j] / E[j]
                    log[pid[j]][2] = i
                    for a in (E, TP, DI, WI, ENT, pid):
                        a.pop(j)
                    hit = True
                else:
                    j += 1
            if hit:
                lowest = min(E) if E else math.inf
    return dict(R=R_a, M=M_a, F=F_a, W=W_a, C=C_a, log=log, final_R=R, final_F=F)


def evaluate(df, sim, g):
    x = g / 1.0                                              # unit arrays are for g = 1 %
    eq = 1.0 + x * (sim["R"] + sim["M"] - sim["F"])
    margin = bt.MARGIN_RATE * x * sim["W"]
    C = sim["C"]
    stop = np.nonzero((C > 0) & (eq <= bt.CLOSEOUT * margin))[0]
    peak = np.maximum.accumulate(np.maximum(eq, 1.0))
    months = 25.6
    return dict(ret_pm=x * (sim["final_R"] - sim["final_F"]) * 100 / months, min_eq=float(eq.min()),
                max_dd=float(((peak - eq) / peak).max()), max_gross=float((x * sim["W"]).max()),
                stopped=str(df.index[stop[0]].date()) if stop.size else "-")


def log_stats(df, sim):
    t = df.index; lows = df["l"].to_numpy()
    days, mae, ds = [], [], []
    for i, price, j, d in sim["log"]:
        ds.append(d * 100)
        if j < 0:
            mae.append(lows[i:].min() / price - 1)
            continue
        days.append((t[j] - t[i]).total_seconds() / 86400.0)
        mae.append(lows[i:j + 1].min() / price - 1)
    months = (t[-1] - t[0]).days / 30.4
    return dict(entries_pm=len(sim["log"]) / months, avg_tp_pct=float(np.mean(ds)),
                p95_days=float(np.percentile(days, 95)), max_days=float(max(days)),
                unresolved=sum(1 for e in sim["log"] if e[2] < 0), mae_worst=float(min(mae) * 100))


def run_index(args):
    name, cache = args
    idx = bt.INDICES[name]
    df = bt.load(Path(cache), idx)
    atr = atr_pct_per_bar(df)
    static_d, ks = PLAN[name]
    rows = []
    for label, k in [("STATIC", None)] + [(f"ATR x{k}", k) for k in ks]:
        sim = simulate(df, idx, static_d, k, atr)
        ls = log_stats(df, sim)
        for g in G_LIST:
            rows.append(dict(index=name, tp=label, g=g, **evaluate(df, sim, g), **ls))
    return rows


def main():
    import multiprocessing as mp
    cache = sys.argv[1]
    with mp.Pool(len(PLAN)) as pool:
        parts = pool.map(run_index, [(n, cache) for n in PLAN])
    res = pd.DataFrame([r for p in parts for r in p])
    out = Path(__file__).parent / "out" / "long_tp_atr.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
