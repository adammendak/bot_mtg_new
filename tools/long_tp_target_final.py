#!/usr/bin/env python3
"""
long_tp_target_final.py - validation of the finalists of long_tp_target_grid2.py (TP = max(static, prior high) ladder).

For each finalist and for the current bots (static TP, with / without SMA50) it prints/writes:
  * full-sample result at DD <= 25 % in the three ordering models,
  * walk-forward: g fitted on 2010-17 applied to 2018-26 and vice versa (opt / path),
  * the 25 months of REAL M5 data (09.2024-10.2026) at g = 0.1 / 0.2 and at the DD <= 25 % budget,
  * calendar years 2019-2026 at fixed g (realised %, lowest equity inside the year, financing drag).
Output: tools/out/long_tp_target_final_*.csv

usage: python tools/long_tp_target_final.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402
import long_tp_d1_grid as gr  # noqa: E402
import long_tp_target_m5 as tm  # noqa: E402

NEAR = {"GER40": 0.38, "SP500": 0.26}
# name -> (index, cfg kwargs)
CANDS = {
    ("GER40", "A  static D.5 step.5 K10"): dict(d=0.5, step=0.5, k=10, target="static"),
    ("GER40", "B  static D.5 sma50"): dict(d=0.5, step=0.5, k=10, target="static", sma=50),
    ("GER40", "F1 ATH D1.0 step1.5 K15 crash2"): dict(d=1.0, step=1.5, k=15, target="ath", crash=2.0),
    ("GER40", "F2 hi20 D1.0 step1.5 K15 crash2"): dict(d=1.0, step=1.5, k=15, target="hi20", crash=2.0),
    ("GER40", "F3 ATH D.5 step.5 K10 (rule only)"): dict(d=0.5, step=0.5, k=10, target="ath"),
    ("GER40", "F4 ATH D.5 step.5 K10 sma50"): dict(d=0.5, step=0.5, k=10, target="ath", sma=50),
    ("GER40", "F5 ATH D1.0 step1.0 K10 crash2"): dict(d=1.0, step=1.0, k=10, target="ath", crash=2.0),
    ("SP500", "A  static D.75 step.5 K10"): dict(d=0.75, step=0.5, k=10, target="static"),
    ("SP500", "B  static D.75 sma50"): dict(d=0.75, step=0.5, k=10, target="static", sma=50),
    ("SP500", "F1 ATH D1.5 step1.0 K10 sma50"): dict(d=1.5, step=1.0, k=10, target="ath", sma=50),
    ("SP500", "F2 ATH D1.0 step1.0 K10 sma50"): dict(d=1.0, step=1.0, k=10, target="ath", sma=50),
    ("SP500", "F3 ATH D.75 step.5 K10 (rule only)"): dict(d=0.75, step=0.5, k=10, target="ath"),
    ("SP500", "F4 ATH D.75 step.5 K10 sma50"): dict(d=0.75, step=0.5, k=10, target="ath", sma=50),
    ("SP500", "F5 hi20 D1.5 step1.0 K10 sma50"): dict(d=1.5, step=1.0, k=10, target="hi20", sma=50),
}


def sim_for(df, cfg, mode):
    return d1.simulate_path(df, cfg) if mode == "path" else d1.simulate(df, d1.Cfg(**{**cfg.__dict__, "mode": mode}))


def mk(name, kw):
    return d1.Cfg(near=NEAR[name], **kw)


def xw(sim):
    return sim["R"] + sim["M"] - sim["F"], sim["W"], sim["C"]


def full_and_wf(name, label, kw):
    df = d1.load_d1(name)
    cfg = mk(name, kw)
    years = (df.index[-1] - df.index[0]).days / 365.25
    out = dict(index=name, cand=label)
    parts = {"2010-17": df[df.index < "2018-01-01"], "2018-26": df[df.index >= "2018-01-01"]}
    for mode in ("opt", "cons", "path"):
        sim = sim_for(df, cfg, mode)
        X, W, C = xw(sim)
        g = gr.g_budget(X, W, C, 0.25)
        out[f"g25_{mode}"] = g
        out[f"ret25_{mode}"] = float(g * (sim["R"][-1] - sim["F"][-1]) * 100 / years)
        if mode in ("opt", "path"):
            fitted = {}
            for lab, p in parts.items():
                s2 = sim_for(p, cfg, mode)
                fitted[lab] = gr.g_budget(*xw(s2), 0.25)
            for train, test in (("2010-17", "2018-26"), ("2018-26", "2010-17")):
                p = parts[test]
                s3 = sim_for(p, cfg, mode)
                X3, W3, C3 = xw(s3)
                gt = fitted[train]
                eq = gr.eq_curve(X3, gt)
                yrs = (p.index[-1] - p.index[0]).days / 365.25
                out[f"wf_{mode}_{train}>{test}_ret"] = float(gt * (s3["R"][-1] - s3["F"][-1]) * 100 / yrs)
                out[f"wf_{mode}_{train}>{test}_dd"] = gr.max_dd(eq)
    return out


def m5_row(name, label, kw):
    kw = dict(kw)
    d = kw.pop("d"); step = kw.pop("step"); k = kw.pop("k"); target = kw.pop("target")
    sma = kw.pop("sma", 0); crash = kw.pop("crash", 0.0)
    df, sim = tm.simulate_m5(name, target, NEAR[name], sma, 0.0, step, k, crash, d)
    t = df.index
    years = (t[-1] - t[0]).days / 365.25
    X, W, C = xw(sim)
    g25 = gr.g_budget(X, W, C, 0.25)
    row = dict(index=name, cand=label, m5_g25=g25, m5_ret25=float(g25 * (sim["R"][-1] - sim["F"][-1]) * 100 / years),
               m5_entries_yr=len(sim["log"]) / years)
    for gf in (0.1, 0.2):
        e = gr.eq_curve(X, gf)
        row[f"m5_ret_g{gf}"] = float(gf * (sim["R"][-1] - sim["F"][-1]) * 100 / years)
        row[f"m5_mineq_g{gf}"] = float(e.min())
        row[f"m5_dd_g{gf}"] = gr.max_dd(e)
    return row


def yearly(name, label, kw, mode, g):
    df = d1.load_d1(name)
    cfg = mk(name, kw)
    sim = sim_for(df, cfg, mode)
    real = g * (sim["R"] - sim["F"])
    eq = 1.0 + g * (sim["R"] + sim["M"] - sim["F"])
    t = df.index
    rows = []
    for y in sorted(set(t.year)):
        if y < 2019:
            continue
        idx = np.nonzero(np.asarray(t.year == y))[0]
        a, z = idx[0], idx[-1]
        base = eq[a - 1] if a > 0 else 1.0
        rows.append(dict(index=name, cand=label, mode=mode, g=g, year=y,
                         realised_pct=(real[z] - (real[a - 1] if a > 0 else 0.0)) * 100,
                         min_eq=float(eq[a:z + 1].min() / base),
                         fin_pct=float(g * (sim["F"][z] - (sim["F"][a - 1] if a > 0 else 0.0)) * 100),
                         open_end=int(sim["C"][z])))
    return rows


def main():
    out = Path(__file__).parent / "out"
    a, b, c = [], [], []
    for (name, label), kw in CANDS.items():
        print(name, label, flush=True)
        a.append(full_and_wf(name, label, kw))
        b.append(m5_row(name, label, kw))
        for mode in ("opt", "path"):
            for g in (0.1, 0.2):
                c += yearly(name, label, kw, mode, g)
    pd.DataFrame(a).to_csv(out / "long_tp_target_final_wf.csv", index=False)
    pd.DataFrame(b).to_csv(out / "long_tp_target_final_m5.csv", index=False)
    pd.DataFrame(c).to_csv(out / "long_tp_target_final_years.csv", index=False)
    print("done")


if __name__ == "__main__":
    main()
