#!/usr/bin/env python3
"""
daily_scalp_report.py - builds the tables for docs/DAILY-SCALP-STRATEGY.md from daily_scalp_bt.py output.

  python tools/daily_scalp_report.py --cache <candle cache> --results tools/out/daily_scalp
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import daily_scalp_bt as bt  # noqa: E402

NL = chr(10)

# (inst, signal, tf, exit, d_pct, label)
CANDIDATES = [
    ("NQ", "PIVOT_BOUNCE", "H1", "E4_FS8", 0.30, "NQ pivot-bounce H1, TP 0.30%, hold<=3d, failsafe 8D"),
    ("NQ", "PIVOT_BOUNCE", "H1", "E4_TP_3D", 0.30, "NQ pivot-bounce H1, TP 0.30%, hold<=3d, NO failsafe"),
    ("SP500", "RSI_CROSS", "H1", "E4_FS8", 0.15, "SP500 RSI-cross H1, TP 0.15%, hold<=3d, failsafe 8D"),
    ("SP500", "CTRL_FADE", "-", "E4_FS8", 0.30, "SP500 daily fade-yesterday (control), TP 0.30%, failsafe 8D"),
    ("SP500", "CTRL_FADE", "-", "E4_TP_3D", 0.30, "SP500 daily fade-yesterday (control), TP 0.30%, NO failsafe"),
    ("SOL", "PIVOT_BOUNCE", "H1", "E4_TP_3D", 0.75, "SOL pivot-bounce H1, TP 0.75%, hold<=3d, NO failsafe"),
    ("SOL", "PIVOT_BOUNCE", "H1", "E4_FS8", 0.75, "SOL pivot-bounce H1, TP 0.75%, hold<=3d, failsafe 8D"),
    ("ETH", "PIVOT_BOUNCE", "H1", "E3_FS5", 0.75, "ETH pivot-bounce H1, TP 0.75%, EOD, failsafe 5D"),
]
N_MONTH = {"ETH": 30, "SOL": 30, "NQ": 20, "SP500": 20}


def md_table(df: pd.DataFrame, floatfmt: int = 3) -> str:
    cols = list(df.columns)
    out = ["| " + " | ".join(cols) + " |", "|" + "|".join(["---"] * len(cols)) + "|"]
    for _, row in df.iterrows():
        cells = []
        for c in cols:
            v = row[c]
            cells.append(f"{v:.{floatfmt}f}" if isinstance(v, (float, np.floating)) else str(v))
        out.append("| " + " | ".join(cells) + " |")
    return NL.join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True)
    ap.add_argument("--results", default=str(Path(__file__).parent / "out" / "daily_scalp"))
    a = ap.parse_args()
    cache = Path(a.cache)
    res = pd.read_csv(a.results + "_results.csv")
    sig = res[~res.signal.str.startswith("CTRL")]
    parts = []

    parts.append("## A. Mean net R per trade (units of D) by exit model and instrument (all signals, all D)" + NL)
    g = sig.groupby(["inst", "exit"]).agg(configs=("n", "size"), win_rate=("wr", "mean"), mean_R=("mean_r", "mean"),
                                           oos_mean_R=("oos_mean", "mean"), worst_R=("worst_r", "min"),
                                           mae_p95=("mae_p95", "median")).reset_index()
    parts.append(md_table(g))

    parts.append(NL + "## B. Signals vs no-skill controls (all instruments, exits, D)" + NL)
    s2 = res.groupby("signal").agg(configs=("n", "size"), win_rate=("wr", "mean"), mean_R=("mean_r", "mean"),
                                   oos_mean_R=("oos_mean", "mean")).reset_index().sort_values("mean_R", ascending=False)
    parts.append(md_table(s2))

    parts.append(NL + "## C. Timeframe (H1 vs H4), signals only" + NL)
    s3 = sig.groupby(["inst", "tf"]).agg(configs=("n", "size"), mean_R=("mean_r", "mean"),
                                         oos_mean_R=("oos_mean", "mean"),
                                         trades_per_month=("trades_per_month", "mean")).reset_index()
    parts.append(md_table(s3))

    parts.append(NL + "## D. Take-profit distance D (E3_FS5 and E1_1to1, signals only)" + NL)
    s4 = sig[sig.exit.isin(["E1_1to1", "E3_FS5"])].groupby(["inst", "d_pct"]).agg(
        mean_R=("mean_r", "mean"), win_rate=("wr", "mean")).reset_index()
    parts.append(md_table(s4))

    parts.append(NL + "## E. Cost sensitivity (mean net R, signals only, E1 + E3_FS5)" + NL)
    rows = []
    for tag, f in (("gross_0x", a.results + "_cost0_results.csv"), ("base_1x", a.results + "_results.csv"),
                   ("cost_2x", a.results + "_cost2_results.csv")):
        try:
            r = pd.read_csv(f)
        except FileNotFoundError:
            continue
        r = r[(~r.signal.str.startswith("CTRL")) & r.exit.isin(["E1_1to1", "E3_FS5"])]
        rows.append(r.groupby("inst")["mean_r"].mean().rename(tag))
    if rows:
        parts.append(md_table(pd.concat(rows, axis=1).reset_index()))

    parts.append(NL + "## F. Survivors: positive in-sample AND out-of-sample, t>=1.5, n>=60 (incl. controls)" + NL)
    surv = res[(res.is_mean > 0) & (res.oos_mean > 0) & (res.t >= 1.5) & (res.n >= 60) & (res.oos_n >= 25)]
    expected_fp = 0.05 * len(res) * 0.25
    parts.append(f"{len(surv)} of {len(res)} configurations survive; with this many tests roughly "
                 f"{expected_fp:.0f}+ would pass by chance alone." + NL)
    cols = ["inst", "signal", "tf", "exit", "d_pct", "n", "trades_per_month", "wr", "mean_r", "t", "worst_r",
            "oos_n", "oos_mean", "oos_t"]
    parts.append(md_table(surv.sort_values("oos_mean", ascending=False)[cols].head(15)))

    parts.append(NL + "## G. Candidates: stability, direction split and monthly Monte Carlo" + NL)
    for inst, sname, tf, ex, d, label in CANDIDATES:
        days, r, mae, reason, dirs = bt.get_trades(cache, inst, sname, tf, ex, d)
        if len(r) < 20:
            continue
        st = bt.stats_of(r)
        q = pd.Series(r, index=pd.to_datetime(days)).groupby(pd.Grouper(freq="QE")).agg(["count", "mean"])
        long_m = r[dirs > 0].mean() if (dirs > 0).any() else float("nan")
        short_m = r[dirs < 0].mean() if (dirs < 0).any() else float("nan")
        parts.append(NL + f"### {label}" + NL)
        parts.append(f"n={st['n']}, win rate {st['wr']:.0%}, mean {st['mean']:+.3f} R/trade (t={st['t']:.2f}), "
                     f"PF {st['pf']:.2f}, worst trade {st['worst']:.1f} D, MAE p95 {np.percentile(mae, 95):.1f} D; "
                     f"TP share {np.mean(reason == 0):.0%}, stop share {np.mean(reason == 1):.0%}, "
                     f"time-exit share {np.mean(reason == 2):.0%}; long mean {long_m:+.3f}, short mean {short_m:+.3f}; "
                     f"quarters positive {(q['mean'] > 0).sum()}/{len(q)}." + NL)
        mrows = []
        for f in (0.1, 0.2, 0.33, 0.5, 1.0):
            for brk in (None, 3.0):
                m = bt.month_mc_breaker(r, N_MONTH[inst], f, brk, sims=2000)
                mrows.append(dict(f_pct_per_D=f, breaker=("none" if brk is None else f"-{brk:.0f}%"),
                                  mean_month=m["mean"], p5=m["p5"], p95=m["p95"], P_ge_6pct=m["p_ge6"],
                                  P_le_m5pct=m["p_le_m5"], dd95=m["dd95"], worst_trade_pct=m["worst_single"]))
        parts.append(md_table(pd.DataFrame(mrows), 2))

    parts.append(NL + "## H. What a 6% month needs (1:1, n trades, risk f per trade, win rate p)" + NL)
    rows = []
    for n in (20, 30):
        for f in (0.2, 0.33, 0.5, 1.0, 2.0):
            need = (6.0 / (n * f) + 1) / 2          # 6% = n f (2p-1)
            rows.append(dict(trades=n, risk_pct=f,
                             required_win_rate=f"{need:.0%}" if need <= 1 else "impossible (>100%)"))
    parts.append(md_table(pd.DataFrame(rows)))

    out = Path(a.results + "_report.md")
    out.write_text(NL.join(parts), encoding="utf-8")
    print("written", out)


if __name__ == "__main__":
    main()
