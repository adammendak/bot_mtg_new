#!/usr/bin/env python3
"""
blend_bt.py - account split between the long ladder (GER40 / SP500, daily model 2010-2026) and a QQQ trend sleeve.

Each sleeve keeps its own equity curve (start 1.0, no rebalancing); the portfolio is  w_q * eq_qqq + (1 - w_q) * eq_ladder
on the dates both markets traded. The ladder curve is marked at the bar low (worst case), the QQQ sleeve is close to close
(shares, no financing, cost 0.05 % round trip per trade). Ladder ordering modes: opt (optimistic) and path (stress).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402
import qqq_trend_bt as qq  # noqa: E402

EP = {"2011": ("2011-07-01", "2011-12-31"), "2018Q4": ("2018-09-01", "2019-01-31"), "2020": ("2020-02-01", "2020-06-30"),
      "2022": ("2022-01-01", "2022-12-31")}

LADDERS = {
    "GER40": {"A g0.2": dict(cfg=dict(d=0.5), g=0.2), "A g0.1": dict(cfg=dict(d=0.5), g=0.1),
              "B1 sma50 g0.2": dict(cfg=dict(d=0.5, sma=50), g=0.2),
              "B2 step2 D1.0 crash2 g0.18": dict(cfg=dict(d=1.0, step=2.0, crash=2.0), g=0.18)},
    "SP500": {"A g0.2": dict(cfg=dict(d=0.75), g=0.2), "A g0.1": dict(cfg=dict(d=0.75), g=0.1),
              "B1 sma50 g0.2": dict(cfg=dict(d=0.75, sma=50), g=0.2),
              "B2 step2 D1.5 crash2 g0.18": dict(cfg=dict(d=1.5, step=2.0, crash=2.0), g=0.18)},
}
QQQ_RULES = [("CROSS", 100, 200), ("CROSS", 50, 200), ("PRICE", 200), ("SCALED", 100, 200)]
WEIGHTS = [0.0, 0.25, 0.5, 0.75, 1.0]


def ladder_eq(name, spec, mode):
    df = d1.load_d1(name)
    cfg = d1.Cfg(mode="opt" if mode == "opt" else "cons", **spec["cfg"])
    sim = d1.simulate(df, cfg) if mode == "opt" else d1.simulate_path(df, cfg)
    eq = 1.0 + spec["g"] * (sim["R"] + sim["M"] - sim["F"])
    return pd.Series(eq, index=df.index)


def qqq_eq(rule, fin=0.0):
    df = qq.load("QQQ")
    pos = qq.signal(df, rule)
    r = qq.run(df, pos, fin)
    return (1 + r).cumprod(), pos


def metrics(eq: pd.Series) -> dict:
    years = (eq.index[-1] - eq.index[0]).days / 365.25
    peak = np.maximum.accumulate(np.maximum(eq.to_numpy(), 1.0))
    dd = float(((peak - eq.to_numpy()) / peak).max())
    cagr = float((eq.iloc[-1] ** (1 / years) - 1) * 100)
    out = dict(cagr=cagr, maxdd=dd * 100, min_eq=float(eq.min()), calmar=cagr / (dd * 100) if dd > 0 else np.nan)
    for k, (a, z) in EP.items():
        seg = eq[(eq.index >= a) & (eq.index <= z)]
        out[f"ep{k}"] = float(seg.min()) if len(seg) else np.nan
    return out


def main():
    rows = []
    for name, cands in LADDERS.items():
        for lab, spec in cands.items():
            for mode in ("opt", "path"):
                le = ladder_eq(name, spec, mode)
                for rule in QQQ_RULES:
                    qe, _ = qqq_eq(rule)
                    idx = le.index.intersection(qe.index)
                    l, q = le.loc[idx], qe.loc[idx]
                    l = l / l.iloc[0]; q = q / q.iloc[0]
                    corr = float(l.diff().corr(q.pct_change()))
                    for w in WEIGHTS:
                        port = (1 - w) * l + w * q
                        rows.append(dict(ladder=name, cand=lab, mode=mode, qqq=" ".join(map(str, rule)), w_qqq=w,
                                         corr=corr, **metrics(port)))
    res = pd.DataFrame(rows)
    out = Path(__file__).parent / "out" / "blend.csv"
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
