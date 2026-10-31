#!/usr/bin/env python3
"""
long_tp_combo_bt.py - several long ladders on ONE account (docs/DAILY-SCALP-LONGONLY.md, section 11).

usage: python tools/long_tp_combo_bt.py <candle cache dir>

share=1.00 stacks every index at the full account; share=0.33 splits the account in thirds.
Index timelines are merged with a forward fill, so the combined equity is an approximation.
"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_stack_bt as bt  # noqa: E402

CACHE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / ".candle_cache"
FIN = 5.5
# (index, rule, D, K, g) - g is the profit per TP as % of the account share of that index
PLANS = {
    "A_zrownowazony": [("GER40", "LADDER_0.5", 0.5, 10, 0.2), ("US100", "LADDER_0.5", 0.75, 10, 0.2),
                       ("SP500", "LADDER_0.5", 0.75, 10, 0.2)],
    "B_ostrozny": [("GER40", "LADDER_0.5", 0.5, 10, 0.1), ("US100", "LADDER_0.5", 0.5, 10, 0.1),
                   ("SP500", "LADDER_0.5", 0.75, 10, 0.1)],
}


def run(plan, share):
    parts = []
    for name, rule, d, k, g in plan:
        idx = bt.INDICES[name]
        df = bt.load(CACHE, idx)
        sim = bt.simulate(df, idx, rule, d, k, FIN)
        nn = (g / 100.0) / (d / 100.0)
        s = pd.DataFrame({"pl": nn * share * (sim["R"] - sim["F"]), "m": nn * share * sim["M"],
                          "marg": bt.MARGIN_RATE * nn * share * sim["C"], "gross": nn * share * sim["C"]},
                         index=df.index)
        parts.append(s)
    ti = parts[0].index
    for p in parts[1:]:
        ti = ti.union(p.index)
    tot = sum(p.reindex(ti).ffill().fillna(0.0) for p in parts)
    eq = 1.0 + tot["pl"] + tot["m"]
    stop = (tot["gross"] > 0) & (eq <= bt.CLOSEOUT * tot["marg"])
    months = (ti[-1] - ti[0]).days / 30.4
    peak = np.maximum.accumulate(np.maximum(eq.values, 1.0))
    return dict(ret_pm=float(tot["pl"].iloc[-1] * 100 / months), min_eq=float(eq.min()), at=str(eq.idxmin().date()),
                max_gross=float(tot["gross"].max()), max_dd=float(((peak - eq.values) / peak).max()),
                stopped=bool(stop.any()), under_share=float((eq < 1).mean()))


if __name__ == "__main__":
    for share in (1.0, 1 / 3.0):
        for nm, plan in PLANS.items():
            r = run(plan, share)
            print(f"share={share:.2f} {nm}: " + ", ".join(
                f"{k}={v:.3f}" if isinstance(v, float) else f"{k}={v}" for k, v in r.items()))
