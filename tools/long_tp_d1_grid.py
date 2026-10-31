#!/usr/bin/env python3
"""
long_tp_d1_grid.py - big permutation grid of the long ladder on 2010-2026 daily bars (tools/long_tp_d1_bt.py).

Every config is compared at the SAME RISK BUDGET: g (profit per TP as % of the account) is scaled up to the largest value
that keeps (a) no broker stop-out, (b) max drawdown <= DD_BUDGET and (c) lowest equity >= MIN_EQ. The objective is the
return per year at that g (a Calmar-like, comparable number). Results for both intraday-ordering modes (opt / cons).

usage: python tools/long_tp_d1_grid.py
"""
from __future__ import annotations

import itertools
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402

DD_BUDGETS = (0.25, 0.35)
MIN_EQ = 0.6
G_CAP = 2.0
EPISODES = {"2011": ("2011-07-01", "2011-12-31"), "2015-16": ("2015-08-01", "2016-03-31"),
            "2018Q4": ("2018-09-01", "2019-01-31"), "2020": ("2020-02-01", "2020-06-30"),
            "2022": ("2022-01-01", "2022-12-31")}

D_GRID = {"GER40": [0.3, 0.4, 0.5, 0.75, 1.0], "SP500": [0.4, 0.5, 0.75, 1.0, 1.5], "US100": [0.4, 0.5, 0.75, 1.0, 1.5]}
STEPS = [0.5, 0.75, 1.0, 1.5, 2.0]
KS = [5, 8, 10, 15]
FILTERS = [dict(), *[dict(sma=n) for n in (20, 50, 100, 200)], *[dict(dd_block=x) for x in (3, 5, 8, 10)],
           *[dict(crash=x) for x in (1.5, 2.0, 3.0)], dict(sma=50, dd_block=5), dict(sma=100, dd_block=8)]
DEPTHS = [0.0, 0.25]


def x_series(sim):
    return sim["R"] + sim["M"] - sim["F"], sim["W"]


def eq_curve(X, g):
    return 1.0 + g * X


def max_dd(eq):
    peak = np.maximum.accumulate(np.maximum(eq, 1.0))
    return float(((peak - eq) / peak).max())


def g_budget(X, W, C, dd_budget):
    """Largest g (in % of account per TP) satisfying stop-free, DD budget and MIN_EQ."""
    need = 0.5 * d1.MARGIN * W - X                      # stop when g * need >= 1 on bars with open positions
    need = np.where(C > 0, need, -1.0)
    hi = min(G_CAP, 1.0 / max(need.max(), 1e-9) * 0.999)
    minx = X.min()
    if minx < 0:
        hi = min(hi, (1.0 - MIN_EQ) / -minx)
    if hi <= 0:
        return 0.0
    if max_dd(eq_curve(X, hi)) <= dd_budget:
        return hi
    lo = 0.0
    for _ in range(40):
        mid = (lo + hi) / 2
        if max_dd(eq_curve(X, mid)) <= dd_budget:
            lo = mid
        else:
            hi = mid
    return lo


def run(args):
    name, mode = args
    df = d1.load_d1(name)
    rows = []
    t = df.index
    years = (t[-1] - t[0]).days / 365.25
    for D, step, K, flt, depth in itertools.product(D_GRID[name], STEPS, KS, FILTERS, DEPTHS):
        if step * K > 25:                  # ladders reaching deeper than 25 % are not interesting
            continue
        cfg = d1.Cfg(d=D, step=step, k=K, mode=mode, depth=depth, **flt)
        sim = d1.simulate(df, cfg)
        X, W = x_series(sim)
        C = sim["C"]
        row = dict(index=name, mode=mode, d=D, step=step, k=K, depth=depth,
                   flt="+".join(f"{k}{v}" for k, v in flt.items()) or "none",
                   entries_yr=len(sim["log"]) / years)
        for b in DD_BUDGETS:
            g = g_budget(X, W, C, b)
            eq = eq_curve(X, g)
            realised = g * (sim["R"] - sim["F"])
            row[f"g{int(b*100)}"] = g
            row[f"ret{int(b*100)}"] = float(realised[-1] * 100 / years)
            row[f"mineq{int(b*100)}"] = float(eq.min())
            if b == 0.25:
                row["gross25"] = float((g * W).max())
                for ep, (a, z) in EPISODES.items():
                    m = (t >= a) & (t <= z)
                    row[f"ep_{ep}"] = float(eq[m].min()) if m.any() else np.nan
        # fixed-g reference
        eq2 = eq_curve(X, 0.2)
        row["ret_g02"] = float(0.2 * (sim["R"][-1] - sim["F"][-1]) * 100 / years)
        row["mineq_g02"] = float(eq2.min())
        row["dd_g02"] = max_dd(eq2)
        rows.append(row)
    return rows


def main():
    import multiprocessing as mp
    jobs = [(n, m) for n in D_GRID for m in ("opt", "cons")]
    with mp.Pool(min(len(jobs), 6)) as pool:
        parts = pool.map(run, jobs)
    res = pd.DataFrame([r for p in parts for r in p])
    out = Path(__file__).parent / "out" / "long_tp_d1_grid.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
