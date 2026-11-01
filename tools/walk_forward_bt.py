#!/usr/bin/env python3
"""
walk_forward_bt.py - out-of-sample check of the long-ladder candidates on 2010-2026 daily bars.

For each candidate and ordering model (opt / path):
  * g is fitted on one half (largest g with no stop-out, DD <= 25 %, lowest equity >= 0.6) and APPLIED to the other half
    (2010-17 -> 2018-26 and 2018-26 -> 2010-17): the reported return / drawdown / lowest equity are out-of-sample;
  * on the full sample: the distribution of rolling 12-month results at the full-sample g (5th percentile, worst).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402
import long_tp_d1_grid as gr  # noqa: E402

CANDS = {
    "GER40": {"A (D.5 step.5 K10)": dict(d=0.5), "B1 A+sma50": dict(d=0.5, sma=50),
              "B2 step2 D1.0 crash2": dict(d=1.0, step=2.0, crash=2.0),
              "B2b step1.5 K15 D1.0 crash1.5": dict(d=1.0, step=1.5, k=15, crash=1.5),
              "B3 step2 D1.0 sma200": dict(d=1.0, step=2.0, sma=200),
              "B4 step2 D1.0 dd_block8": dict(d=1.0, step=2.0, dd_block=8),
              "B5 step1 D.75 crash2": dict(d=0.75, step=1.0, crash=2.0)},
    "SP500": {"A (D.75 step.5 K10)": dict(d=0.75), "B1 A+sma50": dict(d=0.75, sma=50),
              "B2 step2 D1.5 crash2": dict(d=1.5, step=2.0, crash=2.0),
              "B2b step1.5 K15 D1.5 crash1.5": dict(d=1.5, step=1.5, k=15, crash=1.5),
              "B3 step2 D1.5 sma200": dict(d=1.5, step=2.0, sma=200),
              "B4 step2 D1.5 dd_block8": dict(d=1.5, step=2.0, dd_block=8),
              "B5 step1 D1.0 crash2": dict(d=1.0, step=1.0, crash=2.0)},
}


def sim_for(df, spec, mode):
    cfg = d1.Cfg(mode="opt" if mode == "opt" else "cons", **spec)
    return d1.simulate(df, cfg) if mode == "opt" else d1.simulate_path(df, cfg)


def fit_and_apply(name, spec, mode):
    full = d1.load_d1(name)
    parts = {"2010-17": full[full.index < "2018-01-01"], "2018-26": full[full.index >= "2018-01-01"]}
    out = {}
    fitted = {}
    for lab, df in parts.items():
        sim = sim_for(df, spec, mode)
        X = sim["R"] + sim["M"] - sim["F"]
        fitted[lab] = gr.g_budget(X, sim["W"], sim["C"], 0.25)
    for train, test in (("2010-17", "2018-26"), ("2018-26", "2010-17")):
        g = fitted[train]
        df = parts[test]
        sim = sim_for(df, spec, mode)
        X = sim["R"] + sim["M"] - sim["F"]
        eq = 1 + g * X
        years = (df.index[-1] - df.index[0]).days / 365.25
        stop = bool(((sim["C"] > 0) & (eq <= 0.5 * d1.MARGIN * g * sim["W"])).any())
        out[f"{train}->{test}"] = dict(g=g, ret_yr=g * (sim["R"][-1] - sim["F"][-1]) * 100 / years, min_eq=float(eq.min()),
                                       max_dd=gr.max_dd(eq), stop=stop)
    return out, fitted


def rolling(name, spec, mode, g):
    df = d1.load_d1(name)
    sim = sim_for(df, spec, mode)
    X = sim["R"] + sim["M"] - sim["F"]
    eq = 1 + g * X
    win = 252
    rets = eq[win:] - eq[:-win]
    return float(np.percentile(rets, 5) * 100), float(rets.min() * 100), float((rets < 0).mean() * 100)


def main():
    rows = []
    for name, cands in CANDS.items():
        full = d1.load_d1(name)
        for lab, spec in cands.items():
            for mode in ("opt", "path"):
                oos, fitted = fit_and_apply(name, spec, mode)
                sim = sim_for(full, spec, mode)
                X = sim["R"] + sim["M"] - sim["F"]
                gfull = gr.g_budget(X, sim["W"], sim["C"], 0.25)
                p5, worst, neg = rolling(name, spec, mode, gfull)
                row = dict(index=name, cand=lab, mode=mode, g_full=gfull, g_2010_17=fitted["2010-17"],
                           g_2018_26=fitted["2018-26"], roll12_p5=p5, roll12_worst=worst, roll12_neg_pct=neg)
                for k, v in oos.items():
                    for kk, vv in v.items():
                        row[f"{k}:{kk}"] = vv
                rows.append(row)
    res = pd.DataFrame(rows)
    out = Path(__file__).parent / "out" / "walk_forward.csv"
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
