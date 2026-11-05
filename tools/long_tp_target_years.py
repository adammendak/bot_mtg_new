#!/usr/bin/env python3
"""
long_tp_target_years.py - calendar-year view of TP rules for the long ladder (daily model, 2010-2026).

For each index and candidate (static TP vs prior-high TP after a pullback) it prints, per calendar year and ordering model
(opt / path): realised result of the year (% of the account at fixed g), lowest equity inside the year (marked at the daily
low, carried from the start of the year), the worst peak-to-trough inside the year, entries and positions still open at the
year end. The curve is continuous across years (positions carry over), so a year can show unrealised pain from earlier entries.

usage: python tools/long_tp_target_years.py [g]     (default g = 0.1 % per TP)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402
import long_tp_d1_grid as gr  # noqa: E402

BASE = {"GER40": dict(d=0.5, near=0.38), "SP500": dict(d=0.75, near=0.26)}
# label -> kwargs (target, near-mode, cap, variant filter); near: "rule" = BASE near, 0 = always
CANDS = {
    "static": dict(target="static"),
    "pdh": dict(target="pdh", near="rule"),
    "hi5": dict(target="hi5", near="rule"),
    "hi20": dict(target="hi20", near="rule"),
    "swing": dict(target="swing", near="rule"),
    "ath": dict(target="ath", near="rule"),
    "hi20+sma50": dict(target="hi20", near="rule", sma=50),
    "static+sma50": dict(target="static", sma=50),
}


def sim_for(df, cfg, mode):
    return d1.simulate_path(df, cfg) if mode == "path" else d1.simulate(df, d1.Cfg(**{**cfg.__dict__, "mode": mode}))


def yearly(name, label, kw, mode, g, years_from):
    df = d1.load_d1(name)
    kw = dict(kw)
    near = BASE[name]["near"] if kw.pop("near", None) == "rule" else 0.0
    cfg = d1.Cfg(d=BASE[name]["d"], step=0.5, k=10, near=near, **kw)
    sim = sim_for(df, cfg, mode)
    real = g * (sim["R"] - sim["F"])                        # realised, % of account (as fraction)
    eq = 1.0 + g * (sim["R"] + sim["M"] - sim["F"])
    t = df.index
    log = pd.DataFrame(sim["log"], columns=["i", "px", "ex"])
    rows = []
    for y in sorted(set(t.year)):
        if y < years_from:
            continue
        m = np.asarray(t.year == y)
        idx = np.nonzero(m)[0]
        a, z = idx[0], idx[-1]
        start_real = real[a - 1] if a > 0 else 0.0
        seg = eq[a:z + 1]
        base = eq[a - 1] if a > 0 else 1.0                  # equity at the end of the previous year
        peak = np.maximum.accumulate(np.maximum(seg, base))
        rows.append(dict(index=name, rule=label, mode=mode, year=y, realised_pct=(real[z] - start_real) * 100,
                         min_eq=float(seg.min() / base), dd_in_year=float(((peak - seg) / peak).max() * 100),
                         fin_pct=float(g * (sim['F'][z] - (sim['F'][a - 1] if a > 0 else 0.0)) * 100),
                         entries=int(((log.i >= a) & (log.i <= z)).sum()), open_end=int(sim["C"][z])))
    return rows


def main():
    g = float(sys.argv[1]) if len(sys.argv) > 1 else 0.1
    rows = []
    for name in BASE:
        for label, kw in CANDS.items():
            for mode in ("opt", "path"):
                rows += yearly(name, label, kw, mode, g, 2019)
    res = pd.DataFrame(rows)
    out = Path(__file__).parent / "out" / f"long_tp_target_years_g{g}.csv"
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
