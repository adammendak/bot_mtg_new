#!/usr/bin/env python3
"""
nq_ha_study.py - long-history study of the NQ 5-min HA + MACD(6,13,5)/RSI(7)/CCI(14) reversal system (see nq_ha_backtest.py).
Uses ~25 months of Capital.com US100 M5 mid candles (merged caches), reports per-month / per-regime / long-vs-short /
cost-sensitivity / variants. Output: tools/out/nq_ha_study_*.csv

usage: python tools/nq_ha_study.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import nq_ha_backtest as nq  # noqa: E402

CACHES = [HERE.parent.parent / "bot_mtg_new" / "tools" / ".candle_cache" / "US100_MINUTE_5.csv",
          HERE / ".candle_cache" / "US100_MINUTE_5.csv"]
PT = 20.0


def load() -> pd.DataFrame:
    frames = []
    for p in CACHES:
        if p.exists():
            frames.append(nq.load_data("US100", 0, str(p)))
    df = pd.concat(frames)
    df = df[~df.index.duplicated(keep="last")].sort_index()
    return df


def summarize(tr: pd.DataFrame, label: str) -> dict:
    s = nq.stats(tr, PT)
    s["label"] = label
    return s


def main() -> None:
    df = load()
    print(f"US100 M5: {len(df)} bars {df.index[0]:%Y-%m-%d} .. {df.index[-1]:%Y-%m-%d}")
    ind = nq.indicators(df)
    start = df.index[0] + pd.Timedelta(days=5)
    comm = 2.0 / PT
    out = HERE / "out"; out.mkdir(exist_ok=True)

    # daily regime of US100: close vs SMA(100 days), and 20-day return
    d1 = df.close.resample("1D").last().dropna()
    reg = pd.DataFrame({"sma100": d1.rolling(100).mean(), "ret20": d1.pct_change(20)})
    reg["up"] = d1 > reg.sma100
    reg["day"] = reg.index.tz_convert(None).normalize()

    def tag(tr: pd.DataFrame) -> pd.DataFrame:
        tr = tr.copy()
        tr["day"] = pd.to_datetime(tr.entry_time).dt.tz_convert(None).dt.normalize()
        tr = tr.merge(reg[["day", "up", "ret20"]], on="day", how="left")
        tr["month"] = pd.to_datetime(tr.entry_time).dt.strftime("%Y-%m")
        return tr

    res = {}
    for v in ("A", "B"):
        res[v] = tag(nq.backtest(df, ind, start, v, "cross", 0.25, comm))

    print("\n=== whole history (0.25 pt slippage/side, $2/side) ===")
    rows = [summarize(res[v], f"{v} cross") for v in ("A", "B")]
    for v in ("A", "B"):
        rows.append(summarize(nq.backtest(df, ind, start, v, "cross", 0.0, 0.0), f"{v} no costs"))
        rows.append(summarize(nq.backtest(df, ind, start, v, "cross", 0.5, comm), f"{v} slip 0.5"))
        rows.append(summarize(nq.backtest(df, ind, start, v, "cross", 1.0, comm), f"{v} slip 1.0"))
    pd.set_option("display.width", 230)
    t = pd.DataFrame(rows).set_index("label")[["trades", "win_pct", "pf", "net_pts", "net_usd", "avg_pts", "max_dd_usd", "long_pts", "short_pts"]]
    print(t.round(2).to_string())
    t.to_csv(out / "nq_ha_study_overall.csv")

    print("\n=== by regime (daily close above/below its 100-day SMA at entry) ===")
    for v in ("A", "B"):
        tr = res[v]
        g = tr.groupby(["up", "side"]).agg(n=("pnl_pts", "size"), pts=("pnl_pts", "sum"), avg=("pnl_pts", "mean"),
                                           win=("pnl_pts", lambda x: (x > 0).mean() * 100))
        print(f"variant {v}\n{g.round(1).to_string()}")

    print("\n=== by month (points per NQ, after costs) ===")
    m = pd.DataFrame({v: res[v].groupby("month").pnl_pts.sum() for v in ("A", "B")}).fillna(0)
    m["B_trades"] = res["B"].groupby("month").size()
    mkt = df.close.resample("MS").last().pct_change() * 100
    mkt.index = mkt.index.strftime("%Y-%m")
    m["US100 %"] = mkt.reindex(m.index)
    print(m.round(1).to_string())
    m.to_csv(out / "nq_ha_study_monthly.csv")
    print(f"\nB positive months: {(m.B > 0).sum()}/{len(m)}; A positive months: {(m.A > 0).sum()}/{len(m)}")

    print("\n=== halves (B) ===")
    mid = start + (df.index[-1] - start) / 2
    for name, tr in (("first half", res["B"][pd.to_datetime(res["B"].entry_time) < mid]),
                     ("second half", res["B"][pd.to_datetime(res["B"].entry_time) >= mid])):
        print(name, {k: round(v, 2) for k, v in nq.stats(tr, PT).items()})

    print("\n=== B by hour of entry (NY) ===")
    tr = res["B"].copy()
    tr["hh"] = pd.to_datetime(tr.entry_time).dt.tz_convert(nq.NY).dt.hour
    print(tr.groupby("hh").pnl_pts.agg(["size", "sum", "mean"]).round(1).to_string())

    # variant: B + long only / short only, and a time stop-free "B with SL in points" quick check
    print("\n=== B long-only / short-only ===")
    for side in ("L", "S"):
        sub = res["B"][res["B"].side == side]
        print(side, {k: round(v, 2) for k, v in nq.stats(sub, PT).items() if k in ("trades", "win_pct", "pf", "net_usd", "max_dd_usd")})

    print("\n=== equity of B by trade ===")
    eq = res["B"].pnl_pts.cumsum() * PT
    print(f"trades {len(eq)}, final ${eq.iloc[-1]:,.0f}, peak ${eq.max():,.0f}, max DD ${(eq.cummax()-eq).max():,.0f}")

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(2, 1, figsize=(10, 7), sharex=True, gridspec_kw={"height_ratios": [2, 1]})
        for v in ("A", "B"):
            tr = res[v]
            ax[0].plot(pd.to_datetime(tr.exit_time), tr.pnl_pts.cumsum() * PT, label=f"variant {v}")
        ax[0].axhline(0, color="grey", lw=.8); ax[0].legend(); ax[0].grid(alpha=.3)
        ax[0].set_ylabel("USD / NQ contract (after costs)"); ax[0].set_title("NQ 5-min HA MACD/RSI/CCI reversal - Capital.com US100")
        ax[1].plot(d1.index, d1.values, color="#555"); ax[1].set_ylabel("US100 daily close"); ax[1].grid(alpha=.3)
        fig.autofmt_xdate(); fig.tight_layout()
        fig.savefig(out / "nq_ha_study_equity.png", dpi=130)
        print("chart:", out / "nq_ha_study_equity.png")
    except ImportError:
        pass


if __name__ == "__main__":
    main()
