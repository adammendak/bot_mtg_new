#!/usr/bin/env python3
"""
long_tp_stack_bt.py - long-only, NO stop-loss: every position waits for its own take-profit.

Question answered: how often to enter, at which levels, with what size, and how far an index can fall
before the account is stopped out / wiped, for SP500 (US500), US100 and GER40 (DE40).

Model (all on M5 bars, Capital mid prices):
  * Entry rules (long only):
      DAILY_OPEN    one buy per weekday at the session open (US 13:35 UTC, GER40 07:05 UTC)
      DIP_x         limit buy per day at previous close * (1 - x%) (fills at the level, or at the open on a gap)
      LADDER_s      first buy at the daily open when flat; add one more buy each time price falls s% below the
                    LOWEST open entry (averaging down, every position keeps its own TP)
  * Each position: take-profit at entry * (1 + D). No stop, no time exit. TP fills at the level when high >= TP.
  * Size: every entry has notional n = g / D of the account, so one TP is worth g % of the account.
  * Costs: spread (round trip) is deducted from each TP; optional overnight financing on open notional.
  * Mark-to-market equity at each bar LOW (worst case, positions that exit in the bar still count at the low):
        equity/A = 1 + n * (realised + sum_open(low/entry - 1) - financing)           (all per unit notional)
  * Stop-out: equity <= closeout * margin_used, margin_used = margin_rate * n * open_count
    (ESMA retail index CFD: 5% margin, 50% close-out); "wipe" = equity <= 0.

Usage:
  python tools/long_tp_stack_bt.py --cache <candle cache> --out tools/out/long_tp
"""
from __future__ import annotations

import argparse
import itertools
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

NL = chr(10)


@dataclass
class Idx:
    name: str
    file: str
    open_hour: float     # UTC hour of the daily entry bar


INDICES = {
    "SP500": Idx("SP500", "US500_MINUTE_5.csv", 13 + 35 / 60),
    "US100": Idx("US100", "US100_MINUTE_5.csv", 13 + 35 / 60),
    "GER40": Idx("GER40", "DE40_MINUTE_5.csv", 7 + 5 / 60),
}

D_GRID = [0.2, 0.3, 0.5, 0.75, 1.0]        # take-profit distance, % of price
G_GRID = [0.1, 0.2, 0.33, 0.5]             # profit per TP, % of the account
RULES = ["DAILY_OPEN", "DIP_0.25", "DIP_0.5", "DIP_1.0", "DIP_2.0", "LADDER_0.5", "LADDER_1.0", "LADDER_2.0"]
K_GRID = [0, 10]                        # max concurrent positions (0 = unlimited)

COST_PCT = 0.012                           # round-trip spread, % of price (deducted per TP)
FIN_YEAR_PCT = 5.5                         # overnight financing on notional, % per year (base case)
MARGIN_RATE = 0.05                         # 20:1
CLOSEOUT = 0.5                             # broker closes out below 50% of required margin


def load(cache: Path, idx: Idx) -> pd.DataFrame:
    df = pd.read_csv(cache / idx.file)
    df["t"] = pd.to_datetime(df["time"], utc=True)
    df = df.rename(columns={"open": "o", "high": "h", "low": "l", "close": "c"})[["t", "o", "h", "l", "c"]]
    return df.drop_duplicates("t").sort_values("t").set_index("t").astype(float)


def event_map(df: pd.DataFrame, idx: Idx, rule: str):
    """Pre-computable entries: {bar_index: price} for DAILY_OPEN and DIP_x (LADDER is dynamic)."""
    t = df.index
    days = t.normalize()
    ev = {}
    daily_close = df["c"].groupby(days).last()
    prev_close = daily_close.shift(1)
    first_of_day = {}
    for i, d in enumerate(days):
        if d not in first_of_day:
            first_of_day[d] = i
    for d, i0 in first_of_day.items():
        if d.weekday() >= 5:
            continue
        # end of this day's bars
        i1 = i0
        while i1 + 1 < len(t) and days[i1 + 1] == d:
            i1 += 1
        if rule == "DAILY_OPEN" or rule.startswith("LADDER"):
            target = d + pd.Timedelta(hours=idx.open_hour)
            j = int(t.searchsorted(target, side="left"))
            if j <= i1 and t[j] - target <= pd.Timedelta(minutes=10):
                ev[j] = float(df["o"].iat[j])
        elif rule.startswith("DIP_"):
            x = float(rule.split("_")[1]) / 100.0
            pc = prev_close.get(d)
            if pc is None or np.isnan(pc):
                continue
            level = pc * (1 - x)
            lows = df["l"].to_numpy()[i0:i1 + 1]
            hit = np.nonzero(lows <= level)[0]
            if hit.size:
                j = i0 + int(hit[0])
                o = float(df["o"].iat[j])
                ev[j] = min(level, o)
    return ev


def simulate(df: pd.DataFrame, idx: Idx, rule: str, d_pct: float, k_max: int, fin_year_pct: float):
    o = df["o"].tolist()
    h = df["h"].tolist()
    l = df["l"].tolist()
    n = len(o)
    D = d_pct / 100.0
    net_tp = D - COST_PCT / 100.0
    fin_bar = fin_year_pct / 100.0 / 365.0 / 288.0
    ladder = rule.startswith("LADDER")
    s = float(rule.split("_")[1]) / 100.0 if ladder else 0.0
    ev = event_map(df, idx, rule)

    E: list[float] = []          # open entry prices
    TP: list[float] = []
    ENTRY_I: list[int] = []
    sum_inv = 0.0
    R = 0.0
    F = 0.0
    R_arr = np.zeros(n)
    M_arr = np.zeros(n)
    F_arr = np.zeros(n)
    C_arr = np.zeros(n, dtype=np.int32)
    log = []                     # (entry_i, entry_price, tp_i or -1)
    slot = {}                    # position id -> log index
    pid_list: list[int] = []
    lowest = math.inf
    for i in range(n):
        # ---- entries
        price = None
        if not ladder:
            price = ev.get(i)
        else:
            if i in ev and not E:
                price = ev[i]
            elif E and l[i] <= lowest * (1 - s):
                price = min(o[i], lowest * (1 - s))
        if price is not None and (k_max == 0 or len(E) < k_max):
            E.append(price)
            TP.append(price * (1 + D))
            ENTRY_I.append(i)
            sum_inv += 1.0 / price
            lowest = min(lowest, price)
            log.append([i, price, -1])
            pid_list.append(len(log) - 1)
        cnt = len(E)
        # ---- worst-case mark and financing for this bar
        if cnt:
            M_arr[i] = l[i] * sum_inv - cnt
            F += cnt * fin_bar
        R_arr[i] = R
        F_arr[i] = F
        C_arr[i] = cnt
        # ---- take-profits
        if cnt:
            hi = h[i]
            j = 0
            hit_any = False
            while j < len(E):
                if hi >= TP[j] and ENTRY_I[j] <= i:
                    R += net_tp
                    sum_inv -= 1.0 / E[j]
                    log[pid_list[j]][2] = i
                    E.pop(j)
                    TP.pop(j)
                    ENTRY_I.pop(j)
                    pid_list.pop(j)
                    hit_any = True
                else:
                    j += 1
            if hit_any:
                lowest = min(E) if E else math.inf      # profit shows from the NEXT bar (no double count)
    return dict(R=R_arr, M=M_arr, F=F_arr, C=C_arr, log=log, open_end=len(E))


def evaluate(df: pd.DataFrame, sim: dict, d_pct: float, g_pct: float):
    D = d_pct / 100.0
    nn = (g_pct / 100.0) / D                      # notional of one entry as a multiple of the account
    eq = 1.0 + nn * (sim["R"] + sim["M"] - sim["F"])
    C = sim["C"]
    margin = MARGIN_RATE * nn * C
    stop = np.nonzero((C > 0) & (eq <= CLOSEOUT * margin))[0]
    wipe = np.nonzero(eq <= 0)[0]
    peak = np.maximum.accumulate(np.maximum(eq, 1.0))
    dd = float(((peak - eq) / peak).max())
    first_stop = df.index[stop[0]] if stop.size else None
    first_wipe = df.index[wipe[0]] if wipe.size else None
    realised_end = float(1.0 + nn * (sim["R"][-1] - sim["F"][-1]))
    with np.errstate(divide="ignore", invalid="ignore"):
        buf = np.where(C > 0, (eq - CLOSEOUT * margin) / (nn * C), np.inf)
    # equity is below the starting capital for how long (share of bars)
    under = float((eq < 1.0).mean())
    return dict(n_mult=nn, min_eq=float(eq.min()), max_dd=dd, stopped=first_stop, wiped=first_wipe,
                end_realised=realised_end, max_open=int(C.max()), max_gross=float(nn * C.max()),
                buffer_pct=float(buf.min() * 100), under_share=under)


def tp_stats(df: pd.DataFrame, sim: dict, d_pct: float):
    t = df.index
    lows = df["l"].to_numpy()
    log = sim["log"]
    days, mae, unresolved = [], [], 0
    for i, price, j in log:
        if j < 0:
            unresolved += 1
            mae.append(float(lows[i:].min() / price - 1))
            continue
        days.append((t[j] - t[i]).total_seconds() / 86400.0)
        mae.append(float(lows[i:j + 1].min() / price - 1))
    mae = np.array(mae) if mae else np.array([0.0])
    dd = np.array(days) if days else np.array([np.nan])
    months = (t[-1] - t[0]).days / 30.4
    return dict(entries=len(log), entries_pm=len(log) / months, tps_pm=len(days) / months,
                unresolved=unresolved, med_days=float(np.nanmedian(dd)), p95_days=float(np.nanpercentile(dd, 95)),
                max_days=float(np.nanmax(dd)), mae_med=float(np.median(mae) * 100),
                mae_p95=float(np.percentile(mae, 5) * 100), mae_worst=float(mae.min() * 100))


def run_index(args):
    name, cache, fin = args
    idx = INDICES[name]
    df = load(Path(cache), idx)
    rows, stats_rows = [], []
    for rule, d_pct, k in itertools.product(RULES, D_GRID, K_GRID):
        sim = simulate(df, idx, rule, d_pct, k, fin)
        ts = tp_stats(df, sim, d_pct)
        stats_rows.append(dict(index=name, rule=rule, d_pct=d_pct, k_max=k, **ts, open_end=sim["open_end"]))
        for g in G_GRID:
            ev = evaluate(df, sim, d_pct, g)
            rows.append(dict(index=name, rule=rule, d_pct=d_pct, k_max=k, g_pct=g, **ev))
    return rows, stats_rows


def main():
    import multiprocessing as mp
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True)
    ap.add_argument("--out", default=str(Path(__file__).parent / "out" / "long_tp"))
    ap.add_argument("--fin", type=float, default=FIN_YEAR_PCT)
    a = ap.parse_args()
    with mp.Pool(len(INDICES)) as pool:
        parts = pool.map(run_index, [(n, a.cache, a.fin) for n in INDICES])
    res = pd.DataFrame([r for p, _ in parts for r in p])
    st = pd.DataFrame([r for _, p in parts for r in p])
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    res.to_csv(a.out + "_equity.csv", index=False)
    st.to_csv(a.out + "_tpstats.csv", index=False)
    print("written", a.out + "_equity.csv", len(res), a.out + "_tpstats.csv", len(st))


if __name__ == "__main__":
    main()
