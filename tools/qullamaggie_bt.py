#!/usr/bin/env python3
"""
qullamaggie_bt.py - portfolio backtest of the Qullamaggie-style breakout (pine/qullamaggie_setups.pine) on daily Capital.com
share candles (tools/qullamaggie_fetch.py). Long only, no look-ahead:

  signal on the CLOSE of day t-1  ->  buy at the OPEN of day t (+ slippage); stop = low of the breakout day (t-1)
  gap below the stop at the open  ->  no trade
  stop touched intraday           ->  exit at the stop (or the open when it gaps below)
  after `part_days` bars, if in profit: sell 1/3 at the next open and move the stop to break-even
  close below MA10 (trail)        ->  sell the rest at the next open
  sizing: risk_pct of equity per trade (distance entry-stop), notional capped, total exposure capped, max N positions
  costs: slippage per side + CFD overnight financing (annual %, on notional, per calendar day)

Caveats printed with the results: survivorship bias of the hand-made universe, Capital.com volume is CFD tick volume.

usage: python tools/qullamaggie_bt.py [--risk 0.5]
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, replace
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".candle_cache"


@dataclass(frozen=True)
class Cfg:
    adr_min: float = 5.0
    run_len: int = 63
    run_min: float = 30.0
    cons_len: int = 10
    cons_max: float = 2.5
    pull_max: float = 25.0
    need_hl: bool = True
    need_dry: bool = True
    vol_mult: float = 1.5
    part_days: int = 3
    risk_adr_max: float = 1.0        # skip when (entry-stop)/entry > x * ADR ; 0 = off
    market_filter: bool = False
    risk_pct: float = 0.5            # % of equity risked per trade
    max_pos: int = 8
    max_notional: float = 0.25       # x equity per position
    max_exposure: float = 1.0        # x equity in total (no leverage)
    slip: float = 0.0005             # per side, fraction
    fin_year: float = 0.07           # CFD overnight financing, annual, on notional
    label: str = ""


def load_universe() -> dict[str, pd.DataFrame]:
    out = {}
    for p in sorted(CACHE.glob("*_DAY.csv")):
        t = p.name[: -len("_DAY.csv")]
        if t in ("US100", "DE40", "QQQ", "TQQQ", "GOLD"):
            continue
        df = pd.read_csv(p, parse_dates=["time"])
        df["time"] = pd.to_datetime(df["time"], utc=True).dt.tz_localize(None).dt.normalize()
        df = df.drop_duplicates("time").set_index("time").sort_index()
        df = df[(df.close > 0) & (df.high >= df.low) & (df.low > 0)]
        if len(df) >= 300:
            out[t] = df
    return out


def features(df: pd.DataFrame, c: Cfg) -> pd.DataFrame:
    f = pd.DataFrame(index=df.index)
    f["ma10"], f["ma20"], f["ma50"] = (df.close.rolling(n).mean() for n in (10, 20, 50))
    f["adr"] = 100.0 * (((df.high / df.low).rolling(20).mean()) - 1.0)
    va20, va5 = df.volume.rolling(20).mean(), df.volume.rolling(5).mean()
    f["relvol"] = df.volume / va20.replace(0, np.nan)
    n = c.run_len
    hi = df.high.to_numpy(); lo = df.low.to_numpy()
    run_hi = df.high.rolling(n).max(); run_lo = df.low.rolling(n).min()
    lo_before = np.full(len(df), False)
    if len(df) >= n:
        wh = np.lib.stride_tricks.sliding_window_view(hi, n)
        wl = np.lib.stride_tricks.sliding_window_view(lo, n)
        lo_before[n - 1:] = wl.argmin(axis=1) < wh.argmax(axis=1)
    run_pct = 100.0 * (run_hi / run_lo - 1.0)
    run_ok = pd.Series(lo_before, index=df.index) & (run_pct >= c.run_min)
    trend_ok = (f.ma10 > f.ma20) & (f.ma20 > f.ma50) & (f.ma10 > f.ma10.shift(3)) & (f.ma20 > f.ma20.shift(5)) & (df.close > f.ma50)
    cons_hi = df.high.rolling(c.cons_len).max().shift(1)
    cons_lo = df.low.rolling(c.cons_len).min().shift(1)
    cons_rng = 100.0 * (cons_hi / cons_lo - 1.0)
    tight = cons_rng <= c.cons_max * f.adr
    pull = 100.0 * (1.0 - cons_lo / run_hi.shift(1))
    shallow = pull <= c.pull_max
    hl = (df.low.rolling(5).min().shift(1) > df.low.rolling(5).min().shift(6)) if c.need_hl else True
    dry = (va5.shift(1) < va20.shift(1)) if c.need_dry else True
    ready = (f.adr >= c.adr_min) & run_ok.shift(1, fill_value=False) & trend_ok.shift(1, fill_value=False) & tight & shallow & hl & dry
    vol_ok = (df.volume >= c.vol_mult * va20) if c.vol_mult > 0 else True
    f["breakout"] = ready & (df.close > cons_hi) & vol_ok & (df.close > df.open)
    f["stop_ref"] = df.low
    return f


def run(data: dict[str, pd.DataFrame], mkt_ok: pd.Series | None, c: Cfg, start_eq: float = 100_000.0):
    feats = {t: features(df, c) for t, df in data.items()}
    dates = sorted(set().union(*[set(df.index) for df in data.values()]))
    dates = [d for d in dates if d >= min(df.index[0] for df in data.values()) + pd.Timedelta(days=120)]
    eq = start_eq; cash = start_eq
    pos: dict[str, dict] = {}
    trades = []; curve = []
    pending: list[tuple[float, str, float]] = []          # (rank, ticker, stop) signals from the previous close
    prev_d = None
    for d in dates:
        cal = (d - prev_d).days if prev_d is not None else 1
        # ---- 1. manage open positions with today's bar
        for t in list(pos):
            df = data[t]
            if d not in df.index:
                continue
            p = pos[t]; bar = df.loc[d]; f = feats[t].loc[d]
            p["bars"] += 1
            fin = p["sh"] * p["last"] * c.fin_year / 365.0 * cal
            cash -= fin; p["fin"] += fin
            sold = False
            if p["exit_next"]:
                px = bar.open * (1 - c.slip); _close(trades, p, t, d, px, "TRAIL"); cash += p["sh"] * px; del pos[t]; continue
            if p["part_next"] and not p["part_done"]:
                q = p["sh0"] / 3.0
                px = bar.open * (1 - c.slip); cash += q * px
                p["realized"] += q * (px - p["entry"]); p["sh"] -= q; p["part_done"] = True; p["stop"] = max(p["stop"], p["entry"]); p["part_next"] = False
            if bar.low <= p["stop"]:
                px = min(bar.open, p["stop"]) * (1 - c.slip)
                _close(trades, p, t, d, px, "STOP" if not p["part_done"] else "BE/STOP"); cash += p["sh"] * px; del pos[t]; continue
            p["last"] = bar.close
            if not np.isnan(f.ma10) and bar.close < f.ma10:
                p["exit_next"] = True
            elif p["bars"] >= c.part_days and bar.close > p["entry"] and not p["part_done"]:
                p["part_next"] = True
        # ---- 2. new entries from yesterday's signals
        pending.sort(key=lambda x: -x[0])
        for rank, t, stop, adr in pending:
            if t in pos or len(pos) >= c.max_pos or t not in data or d not in data[t].index:
                continue
            bar = data[t].loc[d]
            entry = bar.open * (1 + c.slip)
            if entry <= stop or (entry - stop) <= 0:
                continue
            risk_frac = (entry - stop) / entry
            if c.risk_adr_max > 0 and risk_frac * 100 > c.risk_adr_max * adr:
                continue
            equity = cash + sum(q["sh"] * q["last"] for q in pos.values())
            sh = (c.risk_pct / 100.0 * equity) / (entry - stop)
            sh = min(sh, c.max_notional * equity / entry)
            exposure = sum(q["sh"] * q["last"] for q in pos.values())
            room = c.max_exposure * equity - exposure
            sh = min(sh, room / entry, cash / entry)
            if sh * entry < 0.01 * equity:
                continue
            cash -= sh * entry
            pos[t] = dict(sh=sh, sh0=sh, entry=entry, stop=stop, last=bar.close, bars=0, exit_next=False, part_next=False,
                          part_done=False, realized=0.0, risk=sh * (entry - stop), fin=0.0, entry_date=d)
        pending = []
        # ---- 3. signals on today's close for tomorrow
        if mkt_ok is None or bool(mkt_ok.get(d, False)):
            for t, f in feats.items():
                if d in f.index and bool(f.at[d, "breakout"]) and t not in pos:
                    pending.append((f.at[d, "adr"], t, float(f.at[d, "stop_ref"]), float(f.at[d, "adr"])))
        equity = cash + sum(q["sh"] * q["last"] for q in pos.values())
        curve.append((d, equity, len(pos)))
        prev_d = d
    for t, p in pos.items():                                   # close what is left at the last close
        _close(trades, p, t, dates[-1], p["last"], "END"); cash += p["sh"] * p["last"]
    return pd.DataFrame(trades), pd.DataFrame(curve, columns=["date", "equity", "npos"]).set_index("date")


def _close(trades, p, t, d, px, reason):
    pnl = p["realized"] + p["sh"] * (px - p["entry"]) - p["fin"]
    trades.append(dict(ticker=t, entry_date=p["entry_date"], exit_date=d, entry=p["entry"], exit=px, pnl=pnl,
                       r=pnl / p["risk"] if p["risk"] > 0 else np.nan, bars=p["bars"], reason=reason))


def stats(tr: pd.DataFrame, curve: pd.DataFrame, start_eq=100_000.0) -> dict:
    if tr.empty:
        return dict(trades=0)
    eq = curve.equity
    yrs = (curve.index[-1] - curve.index[0]).days / 365.25
    w = tr.pnl[tr.pnl > 0]; l = tr.pnl[tr.pnl < 0]
    top5 = tr.pnl.nlargest(5).sum()
    return dict(trades=len(tr), win_pct=(tr.pnl > 0).mean() * 100, avg_R=tr.r.mean(), avg_win_R=tr.r[tr.pnl > 0].mean(),
                avg_loss_R=tr.r[tr.pnl <= 0].mean(), pf=w.sum() / -l.sum() if len(l) else np.inf,
                total_pct=(eq.iloc[-1] / start_eq - 1) * 100, cagr=((eq.iloc[-1] / start_eq) ** (1 / yrs) - 1) * 100,
                max_dd=((eq.cummax() - eq) / eq.cummax()).max() * 100, top5_share=top5 / tr.pnl.sum() * 100 if tr.pnl.sum() > 0 else np.nan,
                avg_bars=tr.bars.mean())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--risk", type=float, default=0.5)
    a = ap.parse_args()
    data = load_universe()
    print(f"universe: {len(data)} shares, {min(d.index[0] for d in data.values()):%Y-%m-%d} .. {max(d.index[-1] for d in data.values()):%Y-%m-%d}")
    us = None
    for p in (CACHE / "US100_DAY.csv", HERE.parent.parent / "bot_mtg_new" / "tools" / ".candle_cache" / "US100_DAY.csv"):
        if p.exists():
            u = pd.read_csv(p, parse_dates=["time"]); u["time"] = pd.to_datetime(u["time"], utc=True).dt.tz_localize(None).dt.normalize()
            us = u.drop_duplicates("time").set_index("time").sort_index().close
            break
    mkt = (us > us.rolling(50).mean()) if us is not None else None
    base = Cfg(risk_pct=a.risk)
    # first runs showed the pine defaults (consolidation <= 2.5 x ADR over 10 bars) are far too tight: 1-6 trades in 6 years.
    # A 10-bar range is normally 3-6 x the average DAILY range, so the grid starts at 3.5.
    base = replace(base, cons_max=5.0)
    variants = [
        replace(base, label="V1 cons5 all rules"),
        replace(base, need_hl=False, need_dry=False, label="V2 cons5 no HL/dry"),
        replace(base, need_hl=False, need_dry=False, vol_mult=1.0, label="V3 cons5 no HL/dry vol1.0"),
        replace(base, cons_max=7.0, need_hl=False, need_dry=False, vol_mult=1.0, label="V4 cons7 no HL/dry vol1.0"),
        replace(base, cons_max=3.5, need_hl=False, need_dry=False, vol_mult=1.0, label="V5 cons3.5 no HL/dry vol1.0"),
        replace(base, need_hl=False, need_dry=False, vol_mult=0.0, label="V6 cons5 no volume rules"),
        replace(base, need_hl=False, need_dry=False, vol_mult=1.0, market_filter=True, label="V7 V3 + US100>SMA50"),
        replace(base, need_hl=False, need_dry=False, vol_mult=1.0, risk_adr_max=0.0, label="V8 V3 no risk<=ADR rule"),
        replace(base, need_hl=False, need_dry=False, vol_mult=1.0, adr_min=3.0, label="V9 V3 ADR>=3"),
    ]
    rows = []; curves = {}; alltr = {}
    for v in variants:
        tr, cv = run(data, mkt if v.market_filter else None, v)
        s = stats(tr, cv); s["label"] = v.label; rows.append(s); curves[v.label] = cv; alltr[v.label] = tr
        print(f"done {v.label}: {s.get('trades')} trades", flush=True)
    out = HERE / "out"; out.mkdir(exist_ok=True)
    t = pd.DataFrame(rows).set_index("label")
    pd.set_option("display.width", 250); pd.set_option("display.max_columns", 30)
    print("\n" + t.round(2).to_string())
    t.to_csv(out / "qullamaggie_variants.csv")
    best = max(alltr, key=lambda k: stats(alltr[k], curves[k]).get("cagr", -1e9)) if alltr else None
    for k, tr in alltr.items():
        tr.to_csv(out / f"qullamaggie_trades_{k.split()[0]}.csv", index=False)

    print("\n--- by exit reason / by year (V1 and V2) ---")
    for k in list(alltr)[:2]:
        tr = alltr[k]
        print(k); print(tr.groupby("reason").agg(n=("pnl", "size"), pnl=("pnl", "sum"), avgR=("r", "mean")).round(2).to_string())
        y = tr.assign(year=pd.to_datetime(tr.exit_date).dt.year).groupby("year").agg(n=("pnl", "size"), pnl=("pnl", "sum"), win=("pnl", lambda x: (x > 0).mean() * 100))
        print(y.round(1).to_string())

    # benchmarks
    eqw = pd.concat([d.close.pct_change() for d in data.values()], axis=1).mean(axis=1).fillna(0)
    ref = curves[variants[0].label].index
    bh = (1 + eqw.reindex(ref).fillna(0)).cumprod()
    print(f"\nequal-weight buy&hold of the universe over the same dates: {bh.iloc[-1]*100-100:.0f}% ; max DD {((bh.cummax()-bh)/bh.cummax()).max()*100:.0f}%")
    if us is not None:
        u = us.reindex(ref).ffill()
        print(f"US100 buy&hold: {u.iloc[-1]/u.iloc[0]*100-100:.0f}% ; max DD {((u.cummax()-u)/u.cummax()).max()*100:.0f}%")
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(10, 5))
        for k, cv in curves.items():
            ax.plot(cv.index, cv.equity / 1000, label=k)
        ax.plot(bh.index, bh.values * 100, color="grey", ls="--", label="universe equal-weight B&H")
        ax.set_ylabel("equity (start 100)"); ax.grid(alpha=.3); ax.legend(fontsize=7); ax.set_title("Qullamaggie-style breakout, daily Capital.com shares")
        fig.tight_layout(); fig.savefig(out / "qullamaggie_equity.png", dpi=130)
        print("chart:", out / "qullamaggie_equity.png")
    except ImportError:
        pass


if __name__ == "__main__":
    main()
