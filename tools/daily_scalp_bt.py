#!/usr/bin/env python3
"""
daily_scalp_bt.py — research engine for the "daily small-TP scalp" idea.

One entry per day per instrument, a tiny fixed take-profit D (a few tenths of a percent of price),
exit models from "1:1 with a stop" to "TP only with a failsafe". Entry signals: RSI / Stochastic /
WaveTrend (Market-Cipher-B core) on H1 or H4, daily classic pivots, and three no-skill controls.

Everything is simulated on the base bars (M15 crypto, M5 indices). Same-bar TP/stop ambiguity is
resolved against us (stop first). Signals are evaluated on CLOSED H1/H4 bars and entered at the next
base-bar open (no lookahead). Costs are a flat round-trip percentage of price.

Usage:
  python tools/daily_scalp_bt.py --cache C:/.../tools/.candle_cache --out tools/out/daily_scalp
Outputs: <out>_results.csv (every configuration), <out>_report.md (tables used in docs/DAILY-SCALP-STRATEGY.md).
"""
from __future__ import annotations

import argparse
import itertools
import math
import os
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

# ----------------------------------------------------------------------------- instruments
@dataclass
class Inst:
    name: str
    src: str            # okx | capital
    file: str
    base_min: int
    kind: str           # crypto | index
    d_grid: list        # take-profit distances, % of price
    cost_pct: float     # round-trip cost, % of price
    win_start: float    # entry window start, UTC hour (float)
    win_end: float
    eod: float          # force-flat time, UTC hour (24 = next midnight)


INSTS = {
    "ETH": Inst("ETH", "okx", "okx_ETH-USDT-SWAP_15m.csv", 15, "crypto", [0.3, 0.5, 0.75, 1.0, 1.5], 0.10, 0.0, 24.0, 24.0),
    "SOL": Inst("SOL", "okx", "okx_SOL-USDT-SWAP_15m.csv", 15, "crypto", [0.3, 0.5, 0.75, 1.0, 1.5], 0.10, 0.0, 24.0, 24.0),
    "NQ": Inst("NQ", "capital", "US100_MINUTE_5.csv", 5, "index", [0.10, 0.15, 0.20, 0.30, 0.50], 0.012, 13.5, 19.5, 21.0),
    "SP500": Inst("SP500", "capital", "US500_MINUTE_5.csv", 5, "index", [0.10, 0.15, 0.20, 0.30, 0.50], 0.012, 13.5, 19.5, 21.0),
}

SIGNALS = ["RSI_LEVEL", "RSI_CROSS", "STOCH_CROSS", "WT_CROSS", "PIVOT_BOUNCE", "PIVOT_BREAK",
           "COMBO_OSC_PIVOT", "TREND_PULLBACK"]
CONTROLS = ["CTRL_MOMENTUM", "CTRL_FADE", "CTRL_RANDOM"]
TFS = {"H1": "1h", "H4": "4h"}

# exit models: (code, stop multiple of D or None, hold days)
EXITS = [("E1_1to1", 1.0, 0), ("E2_TP_EOD", None, 0), ("E3_FS3", 3.0, 0), ("E3_FS5", 5.0, 0),
         ("E4_TP_3D", None, 3), ("E4_FS8", 8.0, 3)]

IS_SHARE = 0.6   # first 60% of days = in-sample, last 40% = out-of-sample


# ----------------------------------------------------------------------------- data
def load(cache: Path, inst: Inst) -> pd.DataFrame:
    f = cache / inst.file
    if inst.src == "okx":
        df = pd.read_csv(f, header=None, names=["ts", "o", "h", "l", "c"])
        df["t"] = pd.to_datetime(df["ts"], unit="ms", utc=True)
        df = df.drop(columns="ts")
    else:
        df = pd.read_csv(f)
        df["t"] = pd.to_datetime(df["time"], utc=True)
        df = df.rename(columns={"open": "o", "high": "h", "low": "l", "close": "c"})[["t", "o", "h", "l", "c"]]
    df = df.drop_duplicates("t").sort_values("t").set_index("t")
    return df.astype(float)


def resample(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    r = df.resample(rule, label="left", closed="left").agg({"o": "first", "h": "max", "l": "min", "c": "last"})
    return r.dropna()


# ----------------------------------------------------------------------------- indicators
def rsi(c: pd.Series, n: int = 14) -> pd.Series:
    d = c.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    rs = up / dn.replace(0, np.nan)
    return (100 - 100 / (1 + rs)).fillna(50)


def stoch(df: pd.DataFrame, n: int = 14, k_s: int = 3, d_s: int = 3):
    ll = df["l"].rolling(n).min()
    hh = df["h"].rolling(n).max()
    raw = 100 * (df["c"] - ll) / (hh - ll).replace(0, np.nan)
    k = raw.rolling(k_s).mean()
    return k, k.rolling(d_s).mean()


def wavetrend(df: pd.DataFrame, ch: int = 9, avg: int = 12, ma: int = 3):
    ap = (df["h"] + df["l"] + df["c"]) / 3
    esa = ap.ewm(span=ch, adjust=False).mean()
    d = (ap - esa).abs().ewm(span=ch, adjust=False).mean()
    ci = (ap - esa) / (0.015 * d.replace(0, np.nan))
    wt1 = ci.ewm(span=avg, adjust=False).mean()
    return wt1, wt1.rolling(ma).mean()


def daily_pivots(base: pd.DataFrame) -> pd.DataFrame:
    d = resample(base, "1D").shift(1).dropna()          # previous UTC day
    p = (d["h"] + d["l"] + d["c"]) / 3
    out = pd.DataFrame({"P": p, "R1": 2 * p - d["l"], "S1": 2 * p - d["h"], "R2": p + (d["h"] - d["l"]),
                        "S2": p - (d["h"] - d["l"])})
    return out


def crossed_up(a: pd.Series, b) -> pd.Series:
    b = b if isinstance(b, pd.Series) else pd.Series(b, index=a.index)
    return (a > b) & (a.shift(1) <= b.shift(1))


def crossed_dn(a: pd.Series, b) -> pd.Series:
    b = b if isinstance(b, pd.Series) else pd.Series(b, index=a.index)
    return (a < b) & (a.shift(1) >= b.shift(1))


def build_signal(name: str, tfdf: pd.DataFrame, piv: pd.DataFrame, h4ema: pd.Series | None):
    """Return (long_mask, short_mask) indexed by bar OPEN time of the signal timeframe."""
    c = tfdf["c"]
    r = rsi(c)
    k, d = stoch(tfdf)
    wt1, wt2 = wavetrend(tfdf)
    dates = tfdf.index.normalize()
    pv = piv.reindex(dates)
    pv.index = tfdf.index
    L = pd.Series(False, index=tfdf.index)
    S = pd.Series(False, index=tfdf.index)
    if name == "RSI_LEVEL":
        L, S = r <= 30, r >= 70
    elif name == "RSI_CROSS":
        L, S = crossed_up(r, 30), crossed_dn(r, 70)
    elif name == "STOCH_CROSS":
        L = crossed_up(k, d) & (k < 25)
        S = crossed_dn(k, d) & (k > 75)
    elif name == "WT_CROSS":
        L = crossed_up(wt1, wt2) & (wt1 < -45)
        S = crossed_dn(wt1, wt2) & (wt1 > 45)
    elif name == "PIVOT_BOUNCE":
        L = (tfdf["l"] <= pv["S1"]) & (c > pv["S1"])
        S = (tfdf["h"] >= pv["R1"]) & (c < pv["R1"])
    elif name == "PIVOT_BREAK":
        L = crossed_up(c, pv["P"])
        S = crossed_dn(c, pv["P"])
    elif name == "COMBO_OSC_PIVOT":
        osc_l = (r <= 35) | (crossed_up(k, d) & (k < 30)) | (crossed_up(wt1, wt2) & (wt1 < -35))
        osc_s = (r >= 65) | (crossed_dn(k, d) & (k > 70)) | (crossed_dn(wt1, wt2) & (wt1 > 35))
        L, S = osc_l & (c < pv["P"]), osc_s & (c > pv["P"])
    elif name == "TREND_PULLBACK":
        ema = c.ewm(span=50, adjust=False).mean()
        L = (c > ema) & crossed_up(r, 40)
        S = (c < ema) & crossed_dn(r, 60)
    L = L.fillna(False) & ~S.fillna(False)
    S = S.fillna(False) & ~L
    return L, S


# ----------------------------------------------------------------------------- entries
class Series:
    """Base-bar arrays + helpers for one instrument."""

    def __init__(self, inst: Inst, df: pd.DataFrame):
        self.inst = inst
        self.df = df
        self.t = df.index
        self.o, self.h, self.l, self.c = (df[x].to_numpy() for x in ("o", "h", "l", "c"))
        self.dates = self.t.normalize()
        self.bars_per_day = int(24 * 60 / inst.base_min)

    def idx_at_or_after(self, ts: pd.Timestamp) -> int:
        return int(self.t.searchsorted(ts, side="left"))

    def eod_index(self, day: pd.Timestamp, hold_days: int) -> int:
        end = day + pd.Timedelta(days=hold_days) + pd.Timedelta(hours=self.inst.eod) if hold_days else \
            day + pd.Timedelta(hours=self.inst.eod)
        return int(self.t.searchsorted(end, side="left")) - 1


def entries_for(sr: Series, name: str, tf: str, piv: pd.DataFrame, h4ema=None, seed: int = 0):
    """List of (entry_base_index, direction(+1/-1), day) — at most one per day."""
    inst = sr.inst
    out = []
    if name in CONTROLS:
        rng = np.random.default_rng(seed)
        daily = resample(sr.df, "1D")
        prev_ret = np.sign((daily["c"] - daily["o"]).shift(1))
        for day, row in daily.iterrows():
            if inst.kind == "index" and day.weekday() >= 5:
                continue
            t0 = day + pd.Timedelta(hours=inst.win_start)
            i0 = sr.idx_at_or_after(t0)
            if i0 >= len(sr.t) or sr.t[i0] - t0 > pd.Timedelta(minutes=inst.base_min * 2):
                continue
            pr = prev_ret.get(day, 0.0)
            if pd.isna(pr):
                continue
            if name == "CTRL_MOMENTUM":
                dirn = int(pr)
            elif name == "CTRL_FADE":
                dirn = int(-pr)
            else:
                dirn = int(rng.choice([-1, 1]))
            if dirn == 0:
                continue
            out.append((i0, dirn, day))
        return out
    tfdf = resample(sr.df, TFS[tf])
    L, S = build_signal(name, tfdf, piv, h4ema)
    delta = pd.Timedelta(TFS[tf].replace("h", " hours"))
    seen = set()
    for t_open in tfdf.index[(L | S).to_numpy()]:
        tc = t_open + delta
        day = tc.normalize()
        if day in seen:
            continue
        hour = tc.hour + tc.minute / 60
        if inst.kind == "index":
            if tc.weekday() >= 5 or not (inst.win_start <= hour < inst.win_end):
                continue
        i0 = sr.idx_at_or_after(tc)
        if i0 >= len(sr.t) or sr.t[i0] - tc > pd.Timedelta(minutes=inst.base_min * 2):
            continue                              # gap in the data around the signal
        dirn = 1 if L.loc[t_open] else -1
        seen.add(day)
        out.append((i0, dirn, day))
    return out


# ----------------------------------------------------------------------------- simulation
def simulate(sr: Series, entries, d_pct: float, stop_mult, hold_days: int, cost_pct: float):
    """Returns array of trade dicts as tuples: (day, r_net, mae, reason(0=TP,1=SL,2=EOD))."""
    D = d_pct / 100.0
    cost_d = (cost_pct / 100.0) / D
    res = []
    for i0, dirn, day in entries:
        j = sr.eod_index(day, hold_days)
        if j <= i0:
            continue
        j = min(j, len(sr.t) - 1)
        e = sr.o[i0]
        h = sr.h[i0:j + 1]
        l = sr.l[i0:j + 1]
        if dirn > 0:
            tp = e * (1 + D)
            tp_hit = h >= tp
            sl_hit = (l <= e * (1 - D * stop_mult)) if stop_mult else None
            adverse = e - l
        else:
            tp = e * (1 - D)
            tp_hit = l <= tp
            sl_hit = (h >= e * (1 + D * stop_mult)) if stop_mult else None
            adverse = h - e
        ftp = int(tp_hit.argmax()) if tp_hit.any() else 10**9
        fsl = int(sl_hit.argmax()) if (sl_hit is not None and sl_hit.any()) else 10**9
        if fsl <= ftp and fsl < 10**9:
            r, reason, k = -stop_mult, 1, fsl
        elif ftp < 10**9:
            r, reason, k = 1.0, 0, ftp
        else:
            k = len(h) - 1
            r = (sr.c[j] - e) * dirn / (e * D)
            reason = 2
        mae = float(adverse[:k + 1].max() / (e * D))
        res.append((day, r - cost_d, max(0.0, mae), reason, dirn))
    return res


# ----------------------------------------------------------------------------- statistics
def stats_of(r: np.ndarray):
    n = len(r)
    if n == 0:
        return dict(n=0, wr=np.nan, mean=np.nan, t=np.nan, pf=np.nan, worst=np.nan)
    w = r[r > 0].sum()
    lo = -r[r <= 0].sum()
    sd = r.std(ddof=1) if n > 1 else np.nan
    return dict(n=n, wr=float((r > 0).mean()), mean=float(r.mean()),
                t=float(r.mean() / (sd / math.sqrt(n))) if sd and sd > 0 else np.nan,
                pf=float(w / lo) if lo > 0 else np.inf, worst=float(r.min()))


def month_mc(r: np.ndarray, n_month: int, f_pct: float, sims: int = 4000, seed: int = 7):
    """Bootstrap n_month trades; returns dict of monthly-return stats in % of account."""
    if len(r) < 10:
        return None
    rng = np.random.default_rng(seed)
    draws = rng.choice(r, size=(sims, n_month), replace=True)
    path = np.cumsum(draws, axis=1) * f_pct
    final = path[:, -1]
    peak = np.maximum.accumulate(np.concatenate([np.zeros((sims, 1)), path], axis=1), axis=1)[:, 1:]
    dd = (peak - path).max(axis=1)
    return dict(mean=float(final.mean()), p5=float(np.percentile(final, 5)), p50=float(np.percentile(final, 50)),
                p95=float(np.percentile(final, 95)), p_ge6=float((final >= 6).mean()),
                p_le_m5=float((final <= -5).mean()), dd95=float(np.percentile(dd, 95)))


# ----------------------------------------------------------------------------- helpers for the report
def get_trades(cache: Path, inst_name: str, signal: str, tf: str, exit_code: str, d_pct: float,
               cost_mult: float = 1.0):
    """(days, r_net, mae, reason, direction) arrays for one configuration."""
    inst = INSTS[inst_name]
    df = load(cache, inst)
    sr = Series(inst, df)
    piv = daily_pivots(df)
    if signal == "CTRL_RANDOM":
        ent = []
        for seed in range(5):
            ent += entries_for(sr, signal, tf, piv, seed=seed)
    else:
        ent = entries_for(sr, signal, tf, piv)
    stop_mult, hold = next((sm, h) for c, sm, h in EXITS if c == exit_code)
    tr = simulate(sr, ent, d_pct, stop_mult, hold, inst.cost_pct * cost_mult)
    return (np.array([t[0] for t in tr]), np.array([t[1] for t in tr]),
            np.array([t[2] for t in tr]), np.array([t[3] for t in tr]), np.array([t[4] for t in tr]))


def month_mc_breaker(r: np.ndarray, n_month: int, f_pct: float, breaker_pct: float | None,
                     sims: int = 4000, seed: int = 11):
    """Like month_mc but stops trading for the month once the running loss reaches `breaker_pct` (failsafe)."""
    rng = np.random.default_rng(seed)
    finals, dds, worst_single = [], [], []
    for _ in range(sims):
        draw = rng.choice(r, size=n_month, replace=True) * f_pct
        eq, peak, dd = 0.0, 0.0, 0.0
        for x in draw:
            eq += x
            peak = max(peak, eq)
            dd = max(dd, peak - eq)
            if breaker_pct is not None and eq <= -breaker_pct:
                break
        finals.append(eq)
        dds.append(dd)
    f = np.array(finals)
    return dict(mean=float(f.mean()), p5=float(np.percentile(f, 5)), p95=float(np.percentile(f, 95)),
                p_ge6=float((f >= 6).mean()), p_le_m5=float((f <= -5).mean()),
                dd95=float(np.percentile(dds, 95)), worst_single=float(r.min() * f_pct))


# ----------------------------------------------------------------------------- main
def run(cache: Path, out_prefix: Path, only: list[str] | None, cost_mult: float = 1.0):
    rows = []
    trades_keep = {}
    for name, inst in INSTS.items():
        if only and name not in only:
            continue
        df = load(cache, inst)
        sr = Series(inst, df)
        piv = daily_pivots(df)
        days = sorted(set(sr.dates))
        split_day = days[int(len(days) * IS_SHARE)]
        print(f"[{name}] {len(df)} bars {df.index[0].date()} .. {df.index[-1].date()}  IS/OOS split {split_day.date()}",
              flush=True)
        sigs = [(s, tf) for s in SIGNALS for tf in TFS] + [(c, "-") for c in CONTROLS]
        for sname, tf in sigs:
            if sname == "CTRL_RANDOM":
                ent = []
                for seed in range(5):
                    ent += entries_for(sr, sname, tf, piv, seed=seed)
            else:
                ent = entries_for(sr, sname, tf, piv)
            if len(ent) < 20:
                continue
            for d_pct in inst.d_grid:
                for ecode, stop_mult, hold in EXITS:
                    tr = simulate(sr, ent, d_pct, stop_mult, hold, inst.cost_pct * cost_mult)
                    if len(tr) < 20:
                        continue
                    arr = np.array([t[1] for t in tr])
                    mae = np.array([t[2] for t in tr])
                    reason = np.array([t[3] for t in tr])
                    isd = np.array([t[0] < split_day for t in tr])
                    st_all, st_is, st_oos = stats_of(arr), stats_of(arr[isd]), stats_of(arr[~isd])
                    ndays = len(days) if inst.kind == "crypto" else sum(1 for x in days if x.weekday() < 5)
                    rows.append(dict(
                        inst=name, signal=sname, tf=tf, exit=ecode, d_pct=d_pct, n=st_all["n"],
                        trades_per_month=st_all["n"] / (ndays / 21.0 if inst.kind == "index" else ndays / 30.4),
                        wr=st_all["wr"], mean_r=st_all["mean"], t=st_all["t"], pf=st_all["pf"], worst_r=st_all["worst"],
                        mae_p95=float(np.percentile(mae, 95)), tp_share=float((reason == 0).mean()),
                        sl_share=float((reason == 1).mean()), eod_share=float((reason == 2).mean()),
                        is_n=st_is["n"], is_mean=st_is["mean"], is_wr=st_is["wr"],
                        oos_n=st_oos["n"], oos_mean=st_oos["mean"], oos_wr=st_oos["wr"], oos_t=st_oos["t"]))
                    trades_keep[(name, sname, tf, ecode, d_pct)] = arr
    res = pd.DataFrame(rows)
    out_prefix.parent.mkdir(parents=True, exist_ok=True)
    res.to_csv(str(out_prefix) + "_results.csv", index=False)
    print("configs:", len(res), "->", str(out_prefix) + "_results.csv")
    return res, trades_keep


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.environ.get("CANDLE_CACHE", str(Path(__file__).parent / ".candle_cache")))
    ap.add_argument("--out", default=str(Path(__file__).parent / "out" / "daily_scalp"))
    ap.add_argument("--only", default="", help="comma list of ETH,SOL,NQ,SP500")
    ap.add_argument("--cost-mult", type=float, default=1.0, help="multiplier on the round-trip cost (0 = gross)")
    a = ap.parse_args()
    run(Path(a.cache), Path(a.out), [x for x in a.only.split(",") if x], a.cost_mult)
