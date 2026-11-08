#!/usr/bin/env python3
"""
nq_ha_backtest.py - NQ (Capital.com US100) 5-min Heikin Ashi + MACD(6,13,5) / RSI(7) / CCI(14) reversal system.

Rules (from the TraderLion NQ chart: 5-min HA candles, MACD 6/13/5, RSI 7, CCI 14, 200-DMA, VWAP):
  long signal  (on the CLOSE of a HA bar): MACD line crosses ABOVE its signal, RSI(7) > 50, CCI(14) > 0
  short signal: mirror image
  an opposite signal closes the position and reverses it
  fills at the OPEN of the next REAL 5-min candle (never at HA prices), slippage per side, commission per side
  trades only inside the NY cash session 9:30-16:00, flat at the end of the day
  variant A : indicator signal only
  variant B : additionally long only above VWAP and SMA200, short only below both
  (signal mode 'cross' = MACD cross is the trigger; 'state' = trigger when all three conditions BECOME true)

usage: python tools/nq_ha_backtest.py [--days 30] [--epic US100] [--csv file.csv] [--pt-value 20]
Data: Capital.com mid prices (creds from bot_mtg_new/.env, never printed), cached by capital_data.py.
"""
from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
NY = "America/New_York"


def load_data(epic: str, days_back: int, csv: str | None) -> pd.DataFrame:
    if csv:
        df = pd.read_csv(csv)
        tcol = next(c for c in df.columns if c.lower() in ("time", "datetime", "date", "timestamp"))
        df = df.rename(columns={tcol: "time"})
        df.columns = [c.lower() for c in df.columns]
        df["time"] = pd.to_datetime(df["time"], utc=True)
    else:
        # reuse the demo creds of the main checkout without echoing them
        for cand in (HERE.parent / ".env", HERE.parent.parent / "bot_mtg_new" / ".env"):
            if cand.exists():
                for line in cand.read_text(encoding="utf-8-sig").splitlines():
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        os.environ.setdefault(k.strip(), v.strip())
                break
        sys.path.insert(0, str(HERE))
        import capital_data as cd
        c = cd.Capital(demo=True)
        end = datetime.now(timezone.utc).replace(second=0, microsecond=0)
        bars = c.candles(epic, "MINUTE_5", end - timedelta(days=days_back), end)
        df = pd.DataFrame(bars)
    df = df[["time", "open", "high", "low", "close", "volume"]].dropna(subset=["open", "close"])
    df = df.drop_duplicates("time").sort_values("time").set_index("time")
    return df


def heikin_ashi(df: pd.DataFrame) -> pd.DataFrame:
    ha = pd.DataFrame(index=df.index)
    ha["close"] = (df.open + df.high + df.low + df.close) / 4.0
    o = np.empty(len(df))
    o[0] = (df.open.iloc[0] + df.close.iloc[0]) / 2.0
    hc = ha.close.to_numpy()
    for i in range(1, len(df)):
        o[i] = (o[i - 1] + hc[i - 1]) / 2.0
    ha["open"] = o
    ha["high"] = np.maximum.reduce([df.high.to_numpy(), ha.open.to_numpy(), hc])
    ha["low"] = np.minimum.reduce([df.low.to_numpy(), ha.open.to_numpy(), hc])
    return ha


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False).mean()


def rsi(s: pd.Series, n: int) -> pd.Series:
    d = s.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    return 100 - 100 / (1 + up / dn.replace(0, np.nan))


def cci(h, l, c, n: int) -> pd.Series:
    tp = (h + l + c) / 3.0
    sma = tp.rolling(n).mean()
    md = tp.rolling(n).apply(lambda x: np.abs(x - x.mean()).mean(), raw=True)
    return (tp - sma) / (0.015 * md)


def session_vwap(df: pd.DataFrame) -> pd.Series:
    """VWAP anchored at 9:30 NY of each day (tick volume; equal weights when the feed has no volume)."""
    ny = df.index.tz_convert(NY)
    day = ny.normalize()
    in_s = (ny.hour * 60 + ny.minute) >= 9 * 60 + 30
    tp = (df.high + df.low + df.close) / 3.0
    w = df.volume.where(df.volume > 0, 1.0)
    w = w.where(in_s, 0.0)
    pv = (tp * w).groupby(day).cumsum()
    vv = w.groupby(day).cumsum()
    return (pv / vv.replace(0, np.nan))


def indicators(df: pd.DataFrame) -> pd.DataFrame:
    ha = heikin_ashi(df)
    out = pd.DataFrame(index=df.index)
    macd = ema(ha.close, 6) - ema(ha.close, 13)
    sig = ema(macd, 5)
    out["macd"], out["sig"] = macd, sig
    out["rsi"] = rsi(ha.close, 7)
    out["cci"] = cci(ha.high, ha.low, ha.close, 14)
    out["vwap"] = session_vwap(df)
    out["sma200"] = df.close.rolling(200).mean()
    out["real_close"] = df.close
    cross_up = (macd > sig) & (macd.shift() <= sig.shift())
    cross_dn = (macd < sig) & (macd.shift() >= sig.shift())
    long_all = (macd > sig) & (out.rsi > 50) & (out.cci > 0)
    short_all = (macd < sig) & (out.rsi < 50) & (out.cci < 0)
    out["long_cross"] = cross_up & (out.rsi > 50) & (out.cci > 0)
    out["short_cross"] = cross_dn & (out.rsi < 50) & (out.cci < 0)
    out["long_state"] = long_all & ~long_all.shift(fill_value=False)
    out["short_state"] = short_all & ~short_all.shift(fill_value=False)
    return out


def backtest(df: pd.DataFrame, ind: pd.DataFrame, start: pd.Timestamp, variant: str, mode: str,
             slip: float, comm_pts: float) -> pd.DataFrame:
    """Returns the trade list. Signal on bar i close -> fill at open of bar i+1 (+/- slippage)."""
    ny = df.index.tz_convert(NY)
    mins = (ny.hour * 60 + ny.minute).to_numpy()
    days = ny.normalize().to_numpy()
    op, cl = df.open.to_numpy(), df.close.to_numpy()
    lg, sh = ind[f"long_{mode}"].to_numpy(), ind[f"short_{mode}"].to_numpy()
    vw, sm, rc = ind.vwap.to_numpy(), ind.sma200.to_numpy(), ind.real_close.to_numpy()
    idx = df.index
    trades = []
    pos = 0; entry = 0.0; e_t = None
    SESS_OPEN, SESS_LAST_ENTRY = 9 * 60 + 30, 15 * 60 + 50   # last bar allowed to open a trade (its close is 15:55)
    n = len(df)

    def close(price_raw, i_exit, side_exit_slip, reason):
        nonlocal pos, entry, e_t
        px = price_raw - pos * slip            # selling a long costs slip, buying back a short costs slip
        pnl = pos * (px - entry) - 2 * comm_pts
        trades.append(dict(entry_time=e_t, exit_time=idx[i_exit], side="L" if pos > 0 else "S",
                           entry=entry, exit=px, pnl_pts=pnl, reason=reason))
        pos = 0

    for i in range(n - 1):
        if idx[i] < start:
            continue
        in_session_i = SESS_OPEN <= mins[i] <= 15 * 60 + 55
        # end of day: flat at the close of the 15:55 bar (16:00)
        if pos != 0 and (mins[i] >= 15 * 60 + 55 or days[i + 1] != days[i]):
            close(cl[i], i, 0, "EOD")
            continue
        if not in_session_i or mins[i] > SESS_LAST_ENTRY:
            continue
        want = 0
        if lg[i]:
            want = 1
        elif sh[i]:
            want = -1
        if want == 0:
            continue
        if variant == "B":
            if want == 1 and not (rc[i] > vw[i] and rc[i] > sm[i]):
                want = 0
            elif want == -1 and not (rc[i] < vw[i] and rc[i] < sm[i]):
                want = 0
        if want == 0 or want == pos:
            continue
        if days[i + 1] != days[i]:
            continue
        if pos != 0:                                    # reverse: exit first at the same open
            close(op[i + 1], i + 1, 0, "REV")
        pos = want
        entry = op[i + 1] + pos * slip
        e_t = idx[i + 1]
    if pos != 0:
        close(cl[n - 1], n - 1, 0, "END")
    return pd.DataFrame(trades)


def stats(tr: pd.DataFrame, pt_value: float, extra_comm_usd: float = 0.0) -> dict:
    if tr.empty:
        return dict(trades=0)
    w = tr.pnl_pts[tr.pnl_pts > 0].sum()
    l = -tr.pnl_pts[tr.pnl_pts < 0].sum()
    eq = tr.pnl_pts.cumsum()
    dd = (eq.cummax() - eq).max()
    return dict(trades=len(tr), win_pct=(tr.pnl_pts > 0).mean() * 100, pf=w / l if l > 0 else float("inf"),
                net_pts=tr.pnl_pts.sum(), net_usd=tr.pnl_pts.sum() * pt_value, avg_pts=tr.pnl_pts.mean(),
                max_dd_usd=dd * pt_value, long_pts=tr.pnl_pts[tr.side == "L"].sum(),
                short_pts=tr.pnl_pts[tr.side == "S"].sum())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--warm", type=int, default=15)
    ap.add_argument("--epic", default="US100")
    ap.add_argument("--csv")
    ap.add_argument("--slip", type=float, default=0.25)
    ap.add_argument("--comm-usd", type=float, default=2.0, help="commission per side per NQ contract, USD")
    ap.add_argument("--pt-value", type=float, default=20.0, help="USD per point (NQ=20, MNQ=2)")
    a = ap.parse_args()

    df = load_data(a.epic, a.days + a.warm, a.csv)
    print(f"{a.epic}: {len(df)} 5-min bars {df.index[0]:%Y-%m-%d %H:%M} .. {df.index[-1]:%Y-%m-%d %H:%M} UTC")
    ind = indicators(df)
    start = df.index[-1] - pd.Timedelta(days=a.days)
    comm_pts = a.comm_usd / a.pt_value
    res = {}
    print(f"test window from {start:%Y-%m-%d}; slippage {a.slip} pt/side, commission ${a.comm_usd}/side, ${a.pt_value}/pt\n")
    rows = []
    for mode in ("cross", "state"):
        for v in ("A", "B"):
            tr = backtest(df, ind, start, v, mode, a.slip, comm_pts)
            res[(mode, v)] = tr
            s = stats(tr, a.pt_value); s.update(mode=mode, variant=v)
            rows.append(s)
    # frictionless sanity row for the main variants
    for v in ("A", "B"):
        tr0 = backtest(df, ind, start, v, "cross", 0.0, 0.0)
        s = stats(tr0, a.pt_value); s.update(mode="cross (no costs)", variant=v)
        rows.append(s)
    out = pd.DataFrame(rows).set_index(["mode", "variant"])
    pd.set_option("display.width", 220)
    print(out.round(2).to_string())
    out_dir = HERE / "out"; out_dir.mkdir(exist_ok=True)
    for (mode, v), tr in res.items():
        tr.to_csv(out_dir / f"nq_ha_trades_{mode}_{v}.csv", index=False)

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(10, 5))
        for (mode, v), tr in res.items():
            if mode != "cross" or tr.empty:
                continue
            ax.plot(pd.to_datetime(tr.exit_time), tr.pnl_pts.cumsum() * a.pt_value, label=f"variant {v}")
        ax.axhline(0, color="grey", lw=0.8); ax.set_ylabel("USD per NQ contract (after costs)")
        ax.set_title(f"NQ ({a.epic}) 5-min HA MACD/RSI/CCI reversal - last {a.days} days"); ax.legend(); ax.grid(alpha=.3)
        fig.autofmt_xdate(); fig.tight_layout()
        p = out_dir / "nq_ha_equity.png"; fig.savefig(p, dpi=130)
        print("\nequity curve:", p)
    except ImportError:
        print("\n(matplotlib missing - no chart)")


if __name__ == "__main__":
    main()
