#!/usr/bin/env python3
"""
long_tp_target_grid.py - which take-profit rule is best for the long ladder: static D %, or (after a pullback from the
all-time high) a prior daily high - previous day's high, 5/10/20/60-day high, nearest daily swing high, the ATH itself.

Rule under test (user's idea): within `near` of the all-time high (GER40 ~100 pts = 0.38 %, SP500 ~20 pts = 0.26 %) the TP
stays static D; when the entry is deeper below the ATH, the TP is the chosen prior high (never below the static TP).
Position size stays fixed (g/D of the account), so a farther target pays more per win but waits longer.

Everything is compared at the SAME RISK (g scaled to DD <= 25 %, lowest equity >= 0.6, no stop-out) and also at fixed
g = 0.1 / 0.2 %. Models: opt, cons, path (see long_tp_d1_bt.py); 2010-2026 daily bars.

usage: python tools/long_tp_target_grid.py
"""
from __future__ import annotations

import multiprocessing as mp
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402
import long_tp_d1_grid as gr  # noqa: E402

BASE = {"GER40": dict(d=0.5, near=0.38), "SP500": dict(d=0.75, near=0.26)}
TARGETS = ["static", "pdh", "hi5", "hi10", "hi20", "hi60", "swing", "ath"]
CAPS = [0.0, 2.0, 4.0]
VARIANTS = {"A": dict(), "B(sma50)": dict(sma=50)}
MODES = ("opt", "cons", "path")
EPISODES = gr.EPISODES


def simulate(df, cfg, mode):
    return d1.simulate_path(df, cfg) if mode == "path" else d1.simulate(df, d1.Cfg(**{**cfg.__dict__, "mode": mode}))


def stats(df, sim, g_fixed=(0.1, 0.2)):
    t = df.index
    years = (t[-1] - t[0]).days / 365.25
    X = sim["R"] + sim["M"] - sim["F"]
    W, C = sim["W"], sim["C"]
    row = dict(entries_yr=len(sim["log"]) / years)
    g = gr.g_budget(X, W, C, 0.25)
    eq = gr.eq_curve(X, g)
    row.update(g25=g, ret25=float(g * (sim["R"][-1] - sim["F"][-1]) * 100 / years), mineq25=float(eq.min()))
    for gf in g_fixed:
        e = gr.eq_curve(X, gf)
        stop = bool(((C > 0) & (e <= 0.5 * d1.MARGIN * gf * W)).any())
        row[f"ret_g{gf}"] = float(gf * (sim["R"][-1] - sim["F"][-1]) * 100 / years)
        row[f"mineq_g{gf}"] = float(e.min())
        row[f"dd_g{gf}"] = gr.max_dd(e)
        row[f"stop_g{gf}"] = stop
    holds = []
    open_end = 0
    for ent, _, ex in sim["log"]:
        if ex < 0:
            open_end += 1
            holds.append((t[-1] - t[ent]).days)
        else:
            holds.append((t[ex] - t[ent]).days)
    holds = np.array(holds) if holds else np.array([0])
    row.update(hold_med=float(np.median(holds)), hold_p95=float(np.percentile(holds, 95)), hold_max=int(holds.max()),
               open_at_end=open_end, avg_open=float(C.mean()), pct_ge8=float((C >= 8).mean() * 100))
    return row


def run(args):
    name, mode = args
    full = d1.load_d1(name)
    parts = {"all": full, "2010-17": full[full.index < "2018-01-01"], "2018-26": full[full.index >= "2018-01-01"]}
    base = BASE[name]
    rows = []
    for vlab, vkw in VARIANTS.items():
        for target in TARGETS:
            for cap in CAPS:
                if target == "static" and cap:
                    continue
                for near in ((base["near"], 0.0) if target != "static" else (0.0,)):
                    cfg = d1.Cfg(d=base["d"], step=0.5, k=10, target=target, near=near, cap=cap, **vkw)
                    for plab, df in parts.items():
                        sim = simulate(df, cfg, mode)
                        r = dict(index=name, mode=mode, variant=vlab, target=target, near=near, cap=cap, part=plab)
                        r.update(stats(df, sim))
                        if plab == "all":
                            eq = gr.eq_curve(sim["R"] + sim["M"] - sim["F"], r["g25"])
                            for ep, (a, z) in EPISODES.items():
                                m = (df.index >= a) & (df.index <= z)
                                r[f"ep_{ep}"] = float(eq[m].min()) if m.any() else np.nan
                        rows.append(r)
    return rows


def main():
    jobs = [(n, m) for n in BASE for m in MODES]
    with mp.Pool(min(len(jobs), 6)) as pool:
        parts = pool.map(run, jobs)
    res = pd.DataFrame([r for p in parts for r in p])
    out = Path(__file__).parent / "out" / "long_tp_target_grid.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
