#!/usr/bin/env python3
"""
long_tp_improve_bt.py - improvements of the long ladder (GER40 / SP500, no SL), docs/DAILY-SCALP-LONGONLY.md.

Same model as long_tp_stack_bt.py (M5 bars, equity marked at the bar low, 5% margin, 50% close-out, spread per TP,
financing on notional, ladder step 0.5 %, daily-open entry when flat). Knobs:

  exit STATIC      take profit at entry * (1 + D), as in the bot
       HA          when the static TP level is touched the position is NOT closed; it is closed at the close of the first
                   completed <tf> bar (15/30/60 min) whose Heikin-Ashi candle is bearish
                   (mode bear: HA close < HA open | bear2: two bearish candles in a row | strong: bearish, no upper shadow)
       TRAIL       after the TP level is touched the position rides with a trailing stop <trail> % below the highest high
                   (the bar that touches the TP exits at the TP when it also reaches the trail - conservative)
  split            half of every entry exits STATIC, the other half with the HA / TRAIL rule
  scope            HA/TRAIL for all positions or only for the daily-open ones (ladder positions keep the static TP)
  gate             HA/TRAIL only while the previous daily close is above its SMA(20) at the moment the TP is touched
                   (an uptrend); otherwise the TP is taken at once
  trend filter     no new entries while the previous daily close is below its SMA(N) (all entries or the daily one only)
  K, depth         max open positions; size multiplier 1 / (1 + depth * open_count) (smaller entries deeper in the ladder)

Sizing: an entry carries notional w = g / D_static, so a static TP earns g % of the account.

usage: python tools/long_tp_improve_bt.py <candle cache dir>
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_stack_bt as bt  # noqa: E402

STEP = 0.5
G_LIST = [0.1, 0.2]
MONTHS = 25.6
PLAN = {"GER40": 0.5, "SP500": 0.75}      # index -> static D %


@dataclass(frozen=True)
class Cfg:
    name: str
    exit: str = "STATIC"        # STATIC | HA | TRAIL
    tf: int = 0                 # minutes (HA)
    mode: str = "bear"          # HA mode
    trail: float = 0.0          # % (TRAIL)
    trail_atr: float = 0.0      # TRAIL distance as a multiple of the D1 ATR14 % (alternative to a fixed %)
    scope: str = "all"          # all | daily
    split: bool = False
    gate: bool = False
    sig: str = ""               # key of a precomputed exit signal (EMA family); empty = Heikin-Ashi mode
    k: int = 10
    sma: int = 0
    sma_scope: str = "all"      # all | daily
    depth: float = 0.0
    step: float = 0.0           # ladder step % (0 = module STEP)
    d: float = 0.0              # TP distance % (0 = the index plan)
    crash: float = 0.0          # block ladder entries after a day that fell more than this % (0 = off)


def ha_end_flags(df: pd.DataFrame, tf: int) -> dict[str, np.ndarray]:
    """For every M5 bar: is it the last M5 bar of a <tf>-minute bar whose Heikin-Ashi candle is bearish (3 modes)."""
    key = df.index.floor(f"{tf}min")
    g = pd.DataFrame({"o": df["o"].to_numpy(), "h": df["h"].to_numpy(), "l": df["l"].to_numpy(),
                      "c": df["c"].to_numpy()}, index=df.index).groupby(key).agg(
        {"o": "first", "h": "max", "l": "min", "c": "last"})
    hc = ((g.o + g.h + g.l + g.c) / 4.0).to_numpy()
    ho = np.zeros(len(g))
    ho[0] = (g.o.iloc[0] + g.c.iloc[0]) / 2.0
    for t in range(1, len(g)):
        ho[t] = (ho[t - 1] + hc[t - 1]) / 2.0
    hh = np.maximum.reduce([g.h.to_numpy(), ho, hc])
    bear = hc < ho
    bear2 = bear & np.concatenate([[False], bear[:-1]])
    strong = bear & (ho >= hh - 1e-9 * np.maximum(1.0, np.abs(hh)))
    keys = np.asarray(key)
    last = np.concatenate([keys[1:] != keys[:-1], [True]])
    grp_pos = pd.Series(np.arange(len(g)), index=g.index).reindex(key).to_numpy()
    return {m: last & arr[grp_pos] for m, arr in (("bear", bear), ("bear2", bear2), ("strong", strong))}


def ema_end_flags(df: pd.DataFrame, tf: int, fast: int, slow: int) -> dict[str, np.ndarray]:
    """EMA(fast)/EMA(slow) of the <tf>-minute closes; flags on the last M5 bar of each completed <tf> bar:
    cross = EMA fast below EMA slow, turn = EMA fast below its own previous value (turning down)."""
    key = df.index.floor(f"{tf}min")
    g = df["c"].groupby(key).last()
    ef = g.ewm(span=fast, adjust=False).mean().to_numpy()
    es = g.ewm(span=slow, adjust=False).mean().to_numpy()
    cross = ef < es
    turn = ef < np.concatenate([[ef[0]], ef[:-1]])
    keys = np.asarray(key)
    last = np.concatenate([keys[1:] != keys[:-1], [True]])
    grp_pos = pd.Series(np.arange(len(g)), index=g.index).reindex(key).to_numpy()
    return {"cross": last & cross[grp_pos], "turn": last & turn[grp_pos]}


def crash_per_bar(df: pd.DataFrame, pct: float) -> np.ndarray:
    """True on bars of a day whose PREVIOUS day closed down more than pct % (known at the open)."""
    d = df["c"].resample("1D").last().dropna()
    d = d[d.index.dayofweek < 5]
    prev = (d.pct_change() * 100).shift(1)
    flag = (prev < -pct).astype(float)
    cal = flag.reindex(pd.date_range(d.index[0], d.index[-1] + pd.Timedelta(days=1), freq="1D", tz=d.index.tz)).ffill()
    return cal.reindex(df.index.normalize()).ffill().fillna(0.0).to_numpy() > 0.5


def regime_per_bar(df: pd.DataFrame, n: int) -> np.ndarray:
    d = df["c"].resample("1D").last().dropna()
    d = d[d.index.dayofweek < 5]
    sma = d.rolling(n).mean().shift(1)
    ok = (d.shift(1) > sma).astype(float)
    ok[sma.isna()] = 1.0
    cal = ok.reindex(pd.date_range(d.index[0], d.index[-1] + pd.Timedelta(days=1), freq="1D", tz=d.index.tz)).ffill()
    return cal.reindex(df.index.normalize()).ffill().fillna(1.0).to_numpy() > 0.5


def simulate(df, idx, static_d, cfg: Cfg, ha, regime, gate_reg, sigs=None, atr=None, crash_blk=None, fin_year_pct=bt.FIN_YEAR_PCT):
    o = df["o"].tolist(); h = df["h"].tolist(); l = df["l"].tolist(); c = df["c"].tolist()
    n = len(o)
    ev = bt.event_map(df, idx, "DAILY_OPEN")
    D = (cfg.d or static_d) / 100.0
    fin_bar = fin_year_pct / 100.0 / 365.0 / 288.0
    cost = bt.COST_PCT / 100.0
    s = (cfg.step or STEP) / 100.0
    unit = 0.01
    bear = (sigs[cfg.sig] if cfg.sig else ha[cfg.mode]) if cfg.exit == "HA" else None
    def tr_for(i):
        return cfg.trail / 100.0 if cfg.trail else max(0.001, cfg.trail_atr * atr[i] / 100.0)

    kcap = cfg.k * (2 if cfg.split else 1)
    # per-item state: E entry, TP, W weight, ENT bar, RIDE (item uses the HA/TRAIL rule), ARM, HI (highest high since arm)
    E, TP, W, ENT, RIDE, ARM, HI, TRI, PID = [], [], [], [], [], [], [], [], []
    sumw = sumwinv = 0.0
    R = F = 0.0
    R_a = np.zeros(n); M_a = np.zeros(n); F_a = np.zeros(n); W_a = np.zeros(n)
    C_a = np.zeros(n, dtype=np.int32)
    log = []
    lowest = math.inf
    for i in range(n):
        price = None
        daily = False
        if i in ev and not E:
            price, daily = ev[i], True
        elif E and l[i] <= lowest * (1 - s) and not (cfg.crash and crash_blk is not None and crash_blk[i]):
            price = min(o[i], lowest * (1 - s))
        if price is not None and len(E) < kcap:
            blocked = False
            if cfg.sma and regime is not None and not regime[i]:
                blocked = cfg.sma_scope == "all" or daily
            if not blocked:
                parent_open = len(E) // (2 if cfg.split else 1)
                w_full = unit / D / (1.0 + cfg.depth * parent_open)
                ride_ok = cfg.exit != "STATIC" and (cfg.scope == "all" or daily)
                parts = [(w_full / 2, False), (w_full / 2, ride_ok)] if cfg.split else [(w_full, ride_ok)]
                for w, ride in parts:
                    E.append(price); TP.append(price * (1 + D)); W.append(w); ENT.append(i); RIDE.append(ride)
                    ARM.append(False); HI.append(0.0); TRI.append(0.0)
                    sumw += w; sumwinv += w / price
                    log.append([i, price, -1, 0.0]); PID.append(len(log) - 1)
                lowest = min(lowest, price)
        cnt = len(E)
        if cnt:
            M_a[i] = l[i] * sumwinv - sumw
            F += sumw * fin_bar
        R_a[i] = R; F_a[i] = F; W_a[i] = sumw; C_a[i] = cnt
        if cnt:
            hi = h[i]; j = 0; hit = False
            while j < len(E):
                ret = None
                if ENT[j] <= i:
                    if not RIDE[j]:
                        if hi >= TP[j]:
                            ret = D
                    else:
                        just_armed = False
                        if not ARM[j] and hi >= TP[j]:
                            if cfg.gate and gate_reg is not None and not gate_reg[i]:
                                ret = D                        # no uptrend: take the TP at once
                            else:
                                ARM[j] = True; HI[j] = hi; just_armed = True
                                if cfg.exit == "TRAIL":
                                    TRI[j] = tr_for(i)
                        if ret is None and ARM[j]:
                            if cfg.exit == "HA":
                                if bear[i]:
                                    ret = c[i] / E[j] - 1.0
                            else:                              # TRAIL
                                if just_armed:
                                    if l[i] <= hi * (1 - TRI[j]):
                                        ret = D               # touched the TP and fell back inside one bar: TP only
                                else:
                                    level = HI[j] * (1 - TRI[j])
                                    if l[i] <= level:
                                        ret = min(o[i], level) / E[j] - 1.0
                                    else:
                                        HI[j] = max(HI[j], hi)
                if ret is not None:
                    R += W[j] * (ret - cost)
                    sumw -= W[j]; sumwinv -= W[j] / E[j]
                    log[PID[j]][2] = i; log[PID[j]][3] = ret
                    for a in (E, TP, W, ENT, RIDE, ARM, HI, TRI, PID):
                        a.pop(j)
                    hit = True
                else:
                    j += 1
            if hit:
                lowest = min(E) if E else math.inf
    return dict(R=R_a, M=M_a, F=F_a, W=W_a, C=C_a, log=log, final_R=R, final_F=F)


def evaluate(df, sim, g):
    eq = 1.0 + g * (sim["R"] + sim["M"] - sim["F"])
    margin = bt.MARGIN_RATE * g * sim["W"]
    C = sim["C"]
    stop = np.nonzero((C > 0) & (eq <= bt.CLOSEOUT * margin))[0]
    peak = np.maximum.accumulate(np.maximum(eq, 1.0))
    return dict(ret_pm=g * (sim["final_R"] - sim["final_F"]) * 100 / MONTHS, min_eq=float(eq.min()),
                max_dd=float(((peak - eq) / peak).max()), max_gross=float((g * sim["W"]).max()),
                stopped=str(df.index[stop[0]].date()) if stop.size else "-")


def log_stats(df, sim, static_d, split):
    t = df.index
    done = [e for e in sim["log"] if e[2] >= 0]
    rets = np.array([e[3] for e in done]) * 100 if done else np.array([0.0])
    days = np.array([(t[e[2]] - t[e[0]]).total_seconds() / 86400.0 for e in done]) if done else np.array([0.0])
    months = (t[-1] - t[0]).days / 30.4
    return dict(entries_pm=len(sim["log"]) / (2 if split else 1) / months, avg_ret_pct=float(rets.mean()),
                max_ret_pct=float(rets.max()), below_tp_pct=float((rets < static_d - 1e-9).mean() * 100),
                p95_days=float(np.percentile(days, 95)), max_days=float(days.max()),
                unresolved=sum(1 for e in sim["log"] if e[2] < 0))


def configs() -> list[Cfg]:
    out = [Cfg("STATIC")]
    for tf in (15, 30, 60):
        for mode in ("bear", "bear2", "strong"):
            out.append(Cfg(f"HA{tf} {mode}", "HA", tf, mode))
    for tf in (15, 30, 60):
        out.append(Cfg(f"HA{tf} bear, daily entry only", "HA", tf, "bear", scope="daily"))
    for t in (0.15, 0.25, 0.4):
        out.append(Cfg(f"TRAIL {t}%", "TRAIL", trail=t))
    out += [Cfg("SPLIT 1/2 static + 1/2 HA30 bear", "HA", 30, "bear", split=True),
            Cfg("SPLIT 1/2 static + 1/2 HA60 bear", "HA", 60, "bear", split=True),
            Cfg("SPLIT 1/2 static + 1/2 TRAIL 0.25%", "TRAIL", trail=0.25, split=True),
            Cfg("GATE(uptrend) HA30 bear", "HA", 30, "bear", gate=True),
            Cfg("GATE(uptrend) HA60 bear", "HA", 60, "bear", gate=True),
            Cfg("GATE(uptrend) TRAIL 0.25%", "TRAIL", trail=0.25, gate=True)]
    for tf in (5, 15, 30):
        for fast, slow in ((8, 20), (5, 13)):
            for mode in ("cross", "turn"):
                out.append(Cfg(f"EMA{fast}/{slow} M{tf} {mode}", "HA", tf, mode, sig=f"ema{tf}_{fast}_{slow}_{mode}"))
    out += [Cfg("K=5", k=5), Cfg("K=15", k=15), Cfg("K=20", k=20),
            Cfg("trend filter SMA20 (all entries)", sma=20), Cfg("trend filter SMA50 (all entries)", sma=50),
            Cfg("trend filter SMA20 (daily entry only)", sma=20, sma_scope="daily"),
            Cfg("trend filter SMA50 (daily entry only)", sma=50, sma_scope="daily"),
            Cfg("depth 0.25", depth=0.25), Cfg("depth 0.5", depth=0.5)]
    return out


def configs2() -> list[Cfg]:
    out = []
    for sma in (0, 50):
        tag = " +SMA50" if sma else ""
        out.append(Cfg("STATIC" + tag, sma=sma))
        for tf in (15, 30, 60, 240):
            for mode in ("bear", "strong"):
                out.append(Cfg(f"HA{tf} {mode}{tag}", "HA", tf, mode, sma=sma))
        for tf in (5, 15, 30, 60):
            for fast, slow in ((8, 20), (13, 34)):
                for mode in ("cross", "turn"):
                    out.append(Cfg(f"EMA{fast}/{slow} M{tf} {mode}{tag}", "HA", tf, mode, sig=f"ema{tf}_{fast}_{slow}_{mode}", sma=sma))
        for t in (0.25, 0.4, 0.6):
            out.append(Cfg(f"TRAIL {t}%{tag}", "TRAIL", trail=t, sma=sma))
        for t in (0.15, 0.25, 0.4):
            out.append(Cfg(f"TRAIL {t}xATR{tag}", "TRAIL", trail_atr=t, sma=sma))
        out.append(Cfg(f"SPLIT static+HA60{tag}", "HA", 60, "bear", split=True, sma=sma))
        out.append(Cfg(f"SPLIT static+TRAIL0.25xATR{tag}", "TRAIL", trail_atr=0.25, split=True, sma=sma))
    return out


def configs3(name) -> list[Cfg]:
    ds = {"GER40": (0.5, 0.75, 1.0), "SP500": (0.75, 1.0, 1.5)}[name]
    out = []
    for step in (0.5, 1.0, 1.5, 2.0):
        for d in ds:
            for crash in (0.0, 2.0):
                for sma in (0, 50, 200):
                    tag = f"step{step} D{d} crash{crash:g} sma{sma}"
                    out.append(Cfg(tag, step=step, d=d, crash=crash, sma=sma))
    return out


def run_index(args):
    name, cache, phase = args
    idx = bt.INDICES[name]
    df = bt.load(Path(cache), idx)
    static_d = PLAN[name]
    ha = {tf: ha_end_flags(df, tf) for tf in (15, 30, 60, 240)}
    reg = {n: regime_per_bar(df, n) for n in (20, 50, 100, 200)}
    sigs = {}
    for tf in (5, 15, 30, 60):
        for fast, slow in ((8, 20), (5, 13), (13, 34)):
            for mode, arr in ema_end_flags(df, tf, fast, slow).items():
                sigs[f"ema{tf}_{fast}_{slow}_{mode}"] = arr
    import long_tp_atr_bt as atrmod
    atr = atrmod.atr_pct_per_bar(df)
    rows = []
    crash_blk = crash_per_bar(df, 2.0)
    cfgs = configs3(name) if phase == "p3" else (configs2() if phase == "p2" else configs())
    for cfg in cfgs:
        sim = simulate(df, idx, static_d, cfg, ha.get(cfg.tf), reg.get(cfg.sma), reg[20], sigs, atr, crash_blk)
        ls = log_stats(df, sim, cfg.d or static_d, cfg.split)
        if phase == "p3":
            import long_tp_d1_grid as gr
            X = sim["R"] + sim["M"] - sim["F"]
            for b in (0.25, 0.35):
                gb = gr.g_budget(X, sim["W"], sim["C"], b)
                e = evaluate(df, sim, gb)
                rows.append(dict(index=name, variant=cfg.name, g=round(gb, 4), budget=b, **e, **ls))
            continue
        for g in G_LIST:
            rows.append(dict(index=name, variant=cfg.name, g=g, **evaluate(df, sim, g), **ls))
    return rows


def main():
    import multiprocessing as mp
    cache = sys.argv[1]
    phase = sys.argv[2] if len(sys.argv) > 2 else "p1"
    with mp.Pool(len(PLAN)) as pool:
        parts = pool.map(run_index, [(n, cache, phase) for n in PLAN])
    res = pd.DataFrame([r for p in parts for r in p])
    out = Path(__file__).parent / "out" / {"p2": "long_tp_improve_p2.csv", "p3": "long_tp_improve_p3.csv"}.get(phase, "long_tp_improve.csv")
    out.parent.mkdir(parents=True, exist_ok=True)
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
