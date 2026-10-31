#!/usr/bin/env python3
"""
long_tp_d1_bt.py - the long ladder on DAILY bars (2010-2026) to see how a no-SL strategy behaves through 2011, 2015-16,
2018, 2020 and 2022 (the M5 cache only has 25 months with one crash).

Daily bars cannot order events inside a day, so the model is explicit about it:
  * daily-open entry at the open when flat; ladder entries at lowest*(1-step) (or the open on a gap down) while low reaches
    them, up to K positions;
  * equity is marked at the bar LOW with every open position (incl. those that exit later the same day) - pessimistic;
  * TP when high >= entry*(1+D). Positions bought in the bar can exit in the same bar only in mode 'opt'; in 'cons' they
    wait for the next bar. The daily-open entry can always exit the same day.
  * spread 0.012 % per TP, financing 5.5 %/yr on notional per calendar day, 5 % margin, 50 % close-out.
Sizing is fixed on the INITIAL equity (no compounding): one TP earns g % of the initial account.

Calibrated against the M5 engine on the overlapping 25 months (see calibrate()).
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

CACHE = Path(r"C:\Users\adamm\IdeaProjects\bot_mtg_new\tools\.candle_cache")
FILES = {"SP500": "US500_DAY.csv", "US100": "US100_DAY.csv", "GER40": "DE40_DAY.csv"}
M5_FILES = {"SP500": "US500_MINUTE_5.csv", "US100": "US100_MINUTE_5.csv", "GER40": "DE40_MINUTE_5.csv"}
COST = 0.012 / 100.0
FIN_YEAR = 5.5 / 100.0
MARGIN = 0.05
CLOSEOUT = 0.5


def load_d1(name: str) -> pd.DataFrame:
    df = pd.read_csv(CACHE / FILES[name])
    df["t"] = pd.to_datetime(df["time"], utc=True)
    df = df.rename(columns={"open": "o", "high": "h", "low": "l", "close": "c"})[["t", "o", "h", "l", "c"]]
    df = df.drop_duplicates("t").sort_values("t").set_index("t").astype(float)
    return df[df.index.dayofweek < 5]


def m5_start(name: str) -> pd.Timestamp:
    first = pd.read_csv(CACHE / M5_FILES[name], nrows=1)["time"].iloc[0]
    return pd.to_datetime(first, utc=True).normalize()


@dataclass(frozen=True)
class Cfg:
    d: float                 # TP distance, % of price
    step: float = 0.5        # ladder step, %
    k: int = 10
    mode: str = "cons"       # opt | cons
    sma: int = 0             # trend filter (0 = off): previous close above SMA(sma) of closes
    sma_scope: str = "all"
    crash: float = 0.0       # block ladder entries when the previous day fell more than this % (0 = off)
    dd_block: float = 0.0    # block NEW entries while the index is more than this % below its 60-day high (0 = off)
    depth: float = 0.0       # size multiplier 1/(1+depth*open_count)


def regime(df: pd.DataFrame, n: int) -> np.ndarray:
    c = df["c"]
    sma = c.rolling(n).mean().shift(1)
    ok = np.array((c.shift(1) > sma).to_numpy())
    ok[np.isnan(sma.to_numpy())] = True
    return ok


def simulate(df: pd.DataFrame, cfg: Cfg):
    o = df["o"].to_numpy(); h = df["h"].to_numpy(); l = df["l"].to_numpy(); c = df["c"].to_numpy()
    n = len(df)
    t = df.index
    D = cfg.d / 100.0
    s = cfg.step / 100.0
    reg = regime(df, cfg.sma) if cfg.sma else None
    prev_ret = np.concatenate([[0.0], c[1:] / c[:-1] - 1.0]) * 100
    prev_ret = np.concatenate([[0.0], prev_ret[:-1]])                       # previous day's return, known at the open
    hi60 = pd.Series(c).rolling(60, min_periods=1).max().shift(1).bfill().to_numpy()
    dd60 = (np.concatenate([[c[0]], c[:-1]]) / hi60 - 1.0) * 100            # previous close vs its 60-day high, %
    unit = 0.01
    E, TP, W, ENT = [], [], [], []
    sumw = sumwinv = 0.0
    R = F = 0.0
    eq = np.zeros(n); gross = np.zeros(n); margin_need = np.zeros(n)
    Cn = np.zeros(n, dtype=np.int32)
    R_a = np.zeros(n); M_a = np.zeros(n); F_a = np.zeros(n); W_a = np.zeros(n)
    log = []                         # [entry_i, price, exit_i]
    pid = []
    lowest = math.inf
    entries = 0
    for i in range(n):
        days = 1 if i == 0 else max(1, (t[i] - t[i - 1]).days)
        blocked_all = bool(cfg.sma and not reg[i]) if cfg.sma_scope == "all" else False
        blocked_daily = bool(cfg.sma and not reg[i])
        dd_blocked = bool(cfg.dd_block and dd60[i] < -cfg.dd_block)
        crash_blocked = bool(cfg.crash and prev_ret[i] < -cfg.crash)
        new_this_bar = set()
        # --- daily-open entry
        if not E and not blocked_daily and not dd_blocked and len(E) < cfg.k:
            w = unit / D
            E.append(o[i]); TP.append(o[i] * (1 + D)); W.append(w); ENT.append(i)
            sumw += w; sumwinv += w / o[i]; lowest = o[i]
            log.append([i, o[i], -1]); pid.append(len(log) - 1)
            new_this_bar.add(len(log) - 1)
            entries += 1
        # --- ladder entries
        if E and not blocked_all and not dd_blocked and not crash_blocked:
            while len(E) < cfg.k and l[i] <= lowest * (1 - s):
                price = min(o[i], lowest * (1 - s))
                w = unit / D / (1.0 + cfg.depth * len(E))
                E.append(price); TP.append(price * (1 + D)); W.append(w); ENT.append(i)
                sumw += w; sumwinv += w / price; lowest = price
                log.append([i, price, -1]); pid.append(len(log) - 1)
                new_this_bar.add(len(log) - 1)
                entries += 1
        cnt = len(E)
        F += sumw * FIN_YEAR / 365.0 * days
        M = l[i] * sumwinv - sumw if cnt else 0.0
        R_a[i] = R; M_a[i] = M; F_a[i] = F; W_a[i] = sumw; Cn[i] = cnt
        # --- take profits
        j = 0
        hit = False
        while j < len(E):
            exit_ok = h[i] >= TP[j]
            if exit_ok and cfg.mode == "cons" and ENT[j] == i and pid[j] in new_this_bar and E[j] != o[i]:
                exit_ok = False                                        # bought at a lower level inside this bar
            if exit_ok:
                R += W[j] * (D - COST)
                sumw -= W[j]; sumwinv -= W[j] / E[j]
                log[pid[j]][2] = i
                for a in (E, TP, W, ENT, pid):
                    a.pop(j)
                hit = True
            else:
                j += 1
        if hit:
            lowest = min(E) if E else math.inf
    return dict(R=R_a, M=M_a, F=F_a, W=W_a, C=Cn, log=log, t=t, final_R=R, final_F=F)


def simulate_path(df: pd.DataFrame, cfg: Cfg):
    """Daily model with an explicit intraday PATH per bar: up day (close >= open) O-L-H-C, down day O-H-L-C.
    Up day:   entries (open, ladder levels down to the low), equity marked at the low with everything open, then every
              position (new ones too) exits when high >= TP.
    Down day: positions open at the open (carried + the daily-open entry) exit first when high >= TP, then the ladder fills
              on the way down to the low (levels counted from what is left), equity is marked at the low, and the fresh
              ladder positions exit only if the CLOSE reaches their TP."""
    o = df["o"].to_numpy(); h = df["h"].to_numpy(); l = df["l"].to_numpy(); c = df["c"].to_numpy()
    n = len(df); t = df.index
    D = cfg.d / 100.0; s = cfg.step / 100.0
    reg = regime(df, cfg.sma) if cfg.sma else None
    prev_ret = np.concatenate([[0.0], c[1:] / c[:-1] - 1.0]) * 100
    prev_ret = np.concatenate([[0.0], prev_ret[:-1]])
    hi60 = pd.Series(c).rolling(60, min_periods=1).max().shift(1).bfill().to_numpy()
    dd60 = (np.concatenate([[c[0]], c[:-1]]) / hi60 - 1.0) * 100
    unit = 0.01
    E, TP, W, FRESH, PID = [], [], [], [], []
    st = dict(sumw=0.0, sumwinv=0.0, R=0.0, F=0.0)
    R_a = np.zeros(n); M_a = np.zeros(n); F_a = np.zeros(n); W_a = np.zeros(n)
    Cn = np.zeros(n, dtype=np.int32)
    log = []

    def add(price, fresh, i):
        w = unit / D / (1.0 + cfg.depth * len(E))
        E.append(price); TP.append(price * (1 + D)); W.append(w); FRESH.append(fresh)
        st["sumw"] += w; st["sumwinv"] += w / price
        log.append([i, price, -1]); PID.append(len(log) - 1)

    def exits(i, level_fresh):
        j = 0
        while j < len(E):
            reach = level_fresh if FRESH[j] else h[i]
            if reach >= TP[j]:
                st["R"] += W[j] * (D - COST)
                st["sumw"] -= W[j]; st["sumwinv"] -= W[j] / E[j]
                log[PID[j]][2] = i
                for a in (E, TP, W, FRESH, PID):
                    a.pop(j)
            else:
                j += 1

    def ladder(i, allow):
        while allow and E and len(E) < cfg.k and l[i] <= min(E) * (1 - s):
            add(min(o[i], min(E) * (1 - s)), True, i)

    for i in range(n):
        days = 1 if i == 0 else max(1, (t[i] - t[i - 1]).days)
        blocked_all = bool(cfg.sma and not reg[i]) if cfg.sma_scope == "all" else False
        blocked_daily = bool(cfg.sma and not reg[i])
        dd_blocked = bool(cfg.dd_block and dd60[i] < -cfg.dd_block)
        crash_blocked = bool(cfg.crash and prev_ret[i] < -cfg.crash)
        ladder_ok = not blocked_all and not dd_blocked and not crash_blocked
        for q in range(len(FRESH)):
            FRESH[q] = False
        if not E and not blocked_daily and not dd_blocked:
            add(o[i], False, i)                                   # daily-open entry: behaves like a carried position
        up = c[i] >= o[i]
        if up:
            ladder(i, ladder_ok)
            M = l[i] * st["sumwinv"] - st["sumw"] if E else 0.0
            st["F"] += st["sumw"] * FIN_YEAR / 365.0 * days
            R_a[i] = st["R"]; M_a[i] = M; F_a[i] = st["F"]; W_a[i] = st["sumw"]; Cn[i] = len(E)
            for q in range(len(FRESH)):
                FRESH[q] = False                                   # path O-L-H-C: the high comes after every fill
            exits(i, h[i])
        else:
            exits(i, c[i])                                         # carried + daily-open positions exit at the high first
            n_before = len(E)
            ladder(i, ladder_ok)
            M = l[i] * st["sumwinv"] - st["sumw"] if E else 0.0
            st["F"] += st["sumw"] * FIN_YEAR / 365.0 * days
            R_a[i] = st["R"]; M_a[i] = M; F_a[i] = st["F"]; W_a[i] = st["sumw"]; Cn[i] = len(E)
            exits(i, c[i])                                         # fresh ladder fills exit only up to the close
    return dict(R=R_a, M=M_a, F=F_a, W=W_a, C=Cn, log=log, t=t, final_R=st["R"], final_F=st["F"])


def evaluate(sim, g: float, start=None):
    t = sim["t"]
    eq = 1.0 + g * (sim["R"] + sim["M"] - sim["F"])
    margin = MARGIN * g * sim["W"]
    C = sim["C"]
    stop_idx = np.nonzero((C > 0) & (eq <= CLOSEOUT * margin))[0]
    peak = np.maximum.accumulate(np.maximum(eq, 1.0))
    years = (t[-1] - t[0]).days / 365.25
    realised = g * (sim["R"] - sim["F"])
    return dict(ret_year=float(realised[-1] * 100 / years), min_eq=float(eq.min()),
                max_dd=float(((peak - eq) / peak).max()), max_gross=float((g * sim["W"]).max()),
                stopped=str(t[stop_idx[0]].date()) if stop_idx.size else "-", eq=eq, years=years,
                entries_pm=len(sim["log"]) / (years * 12))


def slice_from(df: pd.DataFrame, start) -> pd.DataFrame:
    return df[df.index >= start]


def calibrate():
    """Daily model vs the M5 engine on the overlapping 25 months (M5 numbers from docs/DAILY-SCALP-LONGONLY.md)."""
    m5 = {"GER40": dict(d=0.5, ret=4.62 * 12, min_eq=0.75, dd=0.47, entries=27.4),
          "SP500": dict(d=0.75, ret=2.66 * 12, min_eq=0.65, dd=0.45, entries=15.5)}
    print("CALIBRATION g=0.2 K=10 step 0.5 (M5 engine vs D1 model over the same 25 months)")
    for name, ref in m5.items():
        df = slice_from(load_d1(name), m5_start(name))
        print(f"{name}: M5 ret/yr {ref['ret']:.1f}% min_eq {ref['min_eq']:.2f} maxDD {ref['dd']:.2f} entries/mo {ref['entries']}")
        for mode in ("opt", "cons", "path"):
            sim = simulate_path(df, Cfg(d=ref["d"])) if mode == "path" else simulate(df, Cfg(d=ref["d"], mode=mode))
            e = evaluate(sim, 0.2)
            print(f"   D1 {mode}: ret/yr {e['ret_year']:.1f}% min_eq {e['min_eq']:.2f} maxDD {e['max_dd']:.2f} "
                  f"entries/mo {e['entries_pm']:.1f} gross {e['max_gross']:.1f} stop {e['stopped']}")


if __name__ == "__main__":
    calibrate()
