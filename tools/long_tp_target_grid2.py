#!/usr/bin/env python3
"""
long_tp_target_grid2.py - permutation grid around the "TP = prior daily high after a pullback" ladder (daily model 2010-2026).

Axes: TP rule (static, previous-day high, 5/20-day high, swing high, ATH), target cap (none / 2 %), static D, ladder step,
K, entry filter (none, SMA50, SMA100, crash brake 2 %). Each config is evaluated in the three ordering models (opt / cons /
path) at the same risk (g scaled to DD <= 25 %, lowest equity >= 0.6, no stop-out) and at fixed g = 0.1 / 0.2.
Ranking in the report: worst of (cons, path) first, because the real result sits between opt and cons/path.

usage: python tools/long_tp_target_grid2.py
"""
from __future__ import annotations

import itertools
import multiprocessing as mp
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402
import long_tp_d1_grid as gr  # noqa: E402

D_GRID = {"GER40": [0.5, 0.75, 1.0], "SP500": [0.75, 1.0, 1.5]}
NEAR = {"GER40": 0.38, "SP500": 0.26}
TARGETS = ["static", "pdh", "hi5", "hi20", "swing", "ath"]
CAPS = [0.0, 2.0]
STEPS = [0.5, 1.0, 1.5]
KS = [10, 15]
FILTERS = [dict(), dict(sma=50), dict(sma=100), dict(crash=2.0)]
MODES = ("opt", "cons", "path")


def sim_for(df, cfg, mode):
    return d1.simulate_path(df, cfg) if mode == "path" else d1.simulate(df, d1.Cfg(**{**cfg.__dict__, "mode": mode}))


def run(args):
    name, mode = args
    df = d1.load_d1(name)
    t = df.index
    years = (t[-1] - t[0]).days / 365.25
    rows = []
    for D, step, K, flt, target, cap in itertools.product(D_GRID[name], STEPS, KS, FILTERS, TARGETS, CAPS):
        if target == "static" and cap:
            continue
        if step * K > 25:
            continue
        cfg = d1.Cfg(d=D, step=step, k=K, target=target, near=NEAR[name], cap=cap, **flt)
        sim = sim_for(df, cfg, mode)
        X = sim["R"] + sim["M"] - sim["F"]
        W, C = sim["W"], sim["C"]
        g = gr.g_budget(X, W, C, 0.25)
        row = dict(index=name, mode=mode, d=D, step=step, k=K, flt="+".join(f"{k}{v}" for k, v in flt.items()) or "none",
                   target=target, cap=cap, g25=g,
                   ret25=float(g * (sim["R"][-1] - sim["F"][-1]) * 100 / years), entries_yr=len(sim["log"]) / years)
        for gf in (0.1, 0.2):
            e = gr.eq_curve(X, gf)
            row[f"ret_g{gf}"] = float(gf * (sim["R"][-1] - sim["F"][-1]) * 100 / years)
            row[f"mineq_g{gf}"] = float(e.min())
            row[f"stop_g{gf}"] = bool(((C > 0) & (e <= 0.5 * d1.MARGIN * gf * W)).any())
        row["fin_drag_g0.1"] = float(0.1 * sim["F"][-1] * 100 / years)
        rows.append(row)
    return rows


def main():
    jobs = [(n, m) for n in D_GRID for m in MODES]
    with mp.Pool(min(len(jobs), 6)) as pool:
        parts = pool.map(run, jobs)
    res = pd.DataFrame([r for p in parts for r in p])
    out = Path(__file__).parent / "out" / "long_tp_target_grid2.csv"
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
