#!/usr/bin/env python3
"""
qqq_trend_bt.py - trend overlay: hold 100 % of the account in QQQ while an EMA rule says "up", cash otherwise.

Signals are computed on the daily CLOSE of day t and executed at the OPEN of day t+1 (no look-ahead):
    day t+1 return = pos[t-1] * (open[t+1]/close[t] - 1)   # the overnight gap is earned if we still held at close t
                   + pos[t]   * (close[t+1]/open[t+1] - 1) # the day itself, if the signal says in
Costs: 0.05 % round trip per trade (spread + slippage); financing on the notional while invested: 0 % (shares) or
5.5 %/yr (CFD) - both reported. Cash earns 0 %.

Rule families (all long-only):
  CROSS f/s      in while EMA(f) > EMA(s)
  PRICE n        in while close > EMA(n)
  BAND n b       in when close > EMA(n)*(1+b), out when close < EMA(n)*(1-b)   (hysteresis against whipsaws)
  CROSS+EXIT     enter on EMA(f) > EMA(s), but leave as soon as close < EMA(s)  (fast exit)
  CONFIRM        in only while EMA(f) > EMA(s) AND close > EMA(f)
  SCALED         exposure 100 % when EMA(f) > EMA(s) and close > EMA(s), 50 % when only one holds, else 0
Assets: QQQ (the ETF as quoted by Capital), TQQQ (3x daily-reset, popular), US500 (hold-out), US100.

usage: python tools/qqq_trend_bt.py
"""
from __future__ import annotations

import itertools
from pathlib import Path

import numpy as np
import pandas as pd

CACHE = Path(r"C:\Users\adamm\IdeaProjects\bot_mtg_new\tools\.candle_cache")
FILES = {"QQQ": "QQQ_DAY.csv", "TQQQ": "TQQQ_DAY.csv", "US500": "US500_DAY.csv", "US100": "US100_DAY.csv"}
TRADE_COST = 0.0005          # round trip, fraction of notional
EPISODES = {"2011": ("2011-07-01", "2011-12-31"), "2015-16": ("2015-08-01", "2016-03-31"),
            "2018Q4": ("2018-09-01", "2019-01-31"), "2020": ("2020-02-01", "2020-06-30"),
            "2022": ("2022-01-01", "2022-12-31")}


def load(name: str) -> pd.DataFrame:
    df = pd.read_csv(CACHE / FILES[name])
    df["t"] = pd.to_datetime(df["time"], utc=True)
    df = df.rename(columns={"open": "o", "high": "h", "low": "l", "close": "c"})[["t", "o", "h", "l", "c"]]
    df = df.drop_duplicates("t").sort_values("t").set_index("t").astype(float)
    return df[df.index.dayofweek < 5].dropna()


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False, min_periods=n).mean()


def signal(df: pd.DataFrame, rule: tuple) -> pd.Series:
    """Exposure in [0, 1] decided at the close of each day."""
    c = df["c"]
    kind = rule[0]
    if kind == "CROSS":
        _, f, s = rule
        pos = (ema(c, f) > ema(c, s)).astype(float)
        pos[ema(c, s).isna()] = 0.0
    elif kind == "PRICE":
        _, n = rule
        pos = (c > ema(c, n)).astype(float)
        pos[ema(c, n).isna()] = 0.0
    elif kind == "BAND":
        _, n, b = rule
        e = ema(c, n).to_numpy(); cv = c.to_numpy()
        arr = np.zeros(len(c)); state = 0.0
        for i in range(len(c)):
            if np.isnan(e[i]):
                arr[i] = 0.0
                continue
            if state == 0.0 and cv[i] > e[i] * (1 + b):
                state = 1.0
            elif state == 1.0 and cv[i] < e[i] * (1 - b):
                state = 0.0
            arr[i] = state
        pos = pd.Series(arr, index=c.index)
    elif kind == "CROSS+EXIT":
        _, f, s = rule
        ef, es = ema(c, f).to_numpy(), ema(c, s).to_numpy(); cv = c.to_numpy()
        arr = np.zeros(len(c)); state = 0.0
        for i in range(len(c)):
            if np.isnan(es[i]):
                continue
            if state == 0.0 and ef[i] > es[i] and cv[i] > es[i]:
                state = 1.0
            elif state == 1.0 and cv[i] < es[i]:
                state = 0.0
            arr[i] = state
        pos = pd.Series(arr, index=c.index)
    elif kind == "CONFIRM":
        _, f, s = rule
        pos = ((ema(c, f) > ema(c, s)) & (c > ema(c, f))).astype(float)
        pos[ema(c, s).isna()] = 0.0
    elif kind == "SCALED":
        _, f, s = rule
        a = (ema(c, f) > ema(c, s)).astype(float)
        b = (c > ema(c, s)).astype(float)
        pos = (a + b) / 2.0
        pos[ema(c, s).isna()] = 0.0
    else:
        raise ValueError(kind)
    return pos


def run(df: pd.DataFrame, pos: pd.Series, fin_year: float = 0.0, cost: float = TRADE_COST) -> pd.Series:
    o, c = df["o"].to_numpy(), df["c"].to_numpy()
    p = pos.to_numpy()
    n = len(df)
    r = np.zeros(n)
    for i in range(1, n):
        held_prev = p[i - 2] if i >= 2 else 0.0           # exposure carried over the night into day i
        gap = o[i] / c[i - 1] - 1.0
        day = c[i] / o[i] - 1.0
        cur = p[i - 1]                                    # signal from the close of day i-1, executed at the open of i
        r[i] = held_prev * gap + cur * day
        r[i] -= cost / 2.0 * abs(cur - held_prev)         # half the round trip on each side of a change
        r[i] -= cur * fin_year / 365.0 * (1 if i < 1 else (df.index[i] - df.index[i - 1]).days)
    return pd.Series(r, index=df.index)


def stats(r: pd.Series, pos: pd.Series, label_fin: str) -> dict:
    eq = (1 + r).cumprod()
    years = (r.index[-1] - r.index[0]).days / 365.25
    cagr = eq.iloc[-1] ** (1 / years) - 1
    dd = (eq / eq.cummax() - 1)
    vol = r.std() * np.sqrt(252)
    ch = pos.diff().abs().fillna(0.0)
    trades = int((ch > 0).sum() / 2)
    yearly = eq.groupby(eq.index.year).last().pct_change()
    yearly.iloc[0] = eq.groupby(eq.index.year).last().iloc[0] - 1
    out = dict(cagr=cagr * 100, maxdd=dd.min() * 100, calmar=cagr / abs(dd.min()) if dd.min() < 0 else np.nan,
               vol=vol * 100, sharpe=(r.mean() * 252) / (r.std() * np.sqrt(252)) if r.std() > 0 else np.nan,
               in_mkt=float((pos > 0).mean() * 100), trades_yr=trades / years, worst_year=float(yearly.min() * 100),
               final_x=float(eq.iloc[-1]))
    for ep, (a, z) in EPISODES.items():
        seg = eq[(eq.index >= a) & (eq.index <= z)]
        if len(seg):
            out[f"dd_{ep}"] = float((seg / seg.cummax() - 1).min() * 100)
    return out


def rules():
    out = [("CROSS", f, s) for f in (20, 30, 50, 75, 100) for s in (100, 150, 200, 250) if f < s]
    out += [("PRICE", n) for n in (50, 100, 150, 200, 250)]
    out += [("BAND", n, b) for n in (100, 150, 200) for b in (0.01, 0.02, 0.03)]
    out += [("CROSS+EXIT", f, s) for f, s in ((50, 200), (100, 200), (50, 100), (20, 50))]
    out += [("CONFIRM", f, s) for f, s in ((50, 200), (100, 200), (50, 100))]
    out += [("SCALED", f, s) for f, s in ((50, 200), (100, 200), (50, 100))]
    return out


def name(rule: tuple) -> str:
    return " ".join(str(x) for x in rule)


def main():
    rows = []
    for asset in FILES:
        df = load(asset)
        years = (df.index[-1] - df.index[0]).days / 365.25
        bh = (df["c"].iloc[-1] / df["o"].iloc[0]) ** (1 / years) - 1
        eqbh = df["c"] / df["o"].iloc[0]
        rows.append(dict(asset=asset, rule="BUY&HOLD", fin="-", cagr=bh * 100, maxdd=float((eqbh / eqbh.cummax() - 1).min() * 100),
                         calmar=bh / abs((eqbh / eqbh.cummax() - 1).min()), in_mkt=100.0, trades_yr=0.0))
        for rule in rules():
            pos = signal(df, rule)
            for fin_name, fin in (("shares", 0.0), ("CFD5.5", 0.055)):
                for period, mask in (("all", slice(None)), ("2010-17", df.index < "2018-01-01"), ("2018-26", df.index >= "2018-01-01")):
                    d2 = df[mask] if period != "all" else df
                    p2 = pos[mask] if period != "all" else pos
                    r = run(d2, p2, fin)
                    rows.append(dict(asset=asset, rule=name(rule), fin=fin_name, period=period, **stats(r, p2, fin_name)))
    res = pd.DataFrame(rows)
    out = Path(__file__).parent / "out" / "qqq_trend.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
