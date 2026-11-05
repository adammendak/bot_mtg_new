#!/usr/bin/env python3
"""
long_tp_target_m5.py - the TP-rule comparison (static vs prior daily high after a pullback) on REAL intraday data: the 25
months of M5 bars (09.2024-10.2026), so the order of events inside a day is not modelled. The levels (previous day's high,
N-day highs, swing high, all-time high) come from the 2010-2026 daily history so the ATH is the true one even at the start of
the window; the ATH is also updated intraday (max of the daily history and the M5 highs so far today).

Same ladder as the bot (daily-open entry when flat, ladder at lowest*(1-0.5%), K=10, fixed notional g/D per entry, no SL),
variant B adds the SMA50 daily trend filter. Spread/financing/margin as long_tp_stack_bt.py. The result series use the same
weights as long_tp_d1_bt.py, so long_tp_d1_grid.g_budget (DD <= 25 %, lowest equity >= 0.6) applies.

usage: python tools/long_tp_target_m5.py
"""
from __future__ import annotations

import itertools
import multiprocessing as mp
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_d1_bt as d1  # noqa: E402
import long_tp_d1_grid as gr  # noqa: E402
import long_tp_stack_bt as m5  # noqa: E402

BASE = {"GER40": dict(d=0.5, near=0.38), "SP500": dict(d=0.75, near=0.26)}
TARGETS = ["static", "pdh", "hi5", "hi20", "swing", "ath"]
STEP = 0.005
K = 10
UNIT = 0.01


def load_m5(name):
    idx = m5.INDICES[name]
    return m5.load(d1.CACHE, idx), idx


def simulate_m5(name, target, near, sma, cap=0.0, step=0.5, k=K, crash=0.0, d=None):
    df, idx = load_m5(name)
    daily = d1.load_d1(name)
    d = BASE[name]["d"] if d is None else d
    cfg = d1.Cfg(d=d, step=step, k=k, target=target, near=near, cap=cap, sma=sma, crash=crash)
    sstep = step / 100.0
    prev_ret = np.concatenate([[0.0], daily["c"].to_numpy()[1:] / daily["c"].to_numpy()[:-1] - 1.0]) * 100
    prev_ret = np.concatenate([[0.0], prev_ret[:-1]])
    tpf = d1.target_fn(daily, cfg)
    reg = d1.regime(daily, sma) if sma else None
    dpos = daily.index.searchsorted(df.index, side="right") - 1          # D1 bar of the day each M5 bar belongs to
    D = cfg.d / 100.0
    w = UNIT / D
    ev = m5.event_map(df, idx, "DAILY_OPEN")
    o = df["o"].to_numpy(); h = df["h"].to_numpy(); l = df["l"].to_numpy(); n = len(df)
    fin_bar = m5.FIN_YEAR_PCT / 100.0 / 365.0 / 288.0
    ath_hist = np.concatenate([[daily["o"].iat[0]], np.maximum.accumulate(daily["h"].to_numpy())[:-1]])
    E, TP = [], []
    pid = []
    sum_inv = 0.0
    R = F = 0.0
    R_a = np.zeros(n); M_a = np.zeros(n); F_a = np.zeros(n); W_a = np.zeros(n); C_a = np.zeros(n, dtype=np.int32)
    log = []
    lowest = np.inf
    day_hi = -np.inf
    last_day = -1
    for i in range(n):
        di = int(dpos[i])
        if di != last_day:
            last_day = di; day_hi = -np.inf
        day_hi = max(day_hi, h[i])
        ok = di >= 0 and (reg is None or bool(reg[di]))
        price = None
        if not E:
            if i in ev and ok:
                price = ev[i]
        elif l[i] <= lowest * (1 - sstep) and not (crash and di >= 0 and prev_ret[di] < -crash):
            price = min(o[i], lowest * (1 - sstep))
        if price is not None and len(E) < k and di >= 0:
            ath_now = max(ath_hist[di], day_hi)
            tp = tpf(di, price, ath_now)
            E.append(price); TP.append(tp); sum_inv += w / price; lowest = min(lowest, price)
            log.append([i, price, -1]); pid.append(len(log) - 1)
        cnt = len(E)
        if cnt:
            M_a[i] = l[i] * sum_inv - w * cnt
            F += w * cnt * fin_bar
        R_a[i] = R; F_a[i] = F; W_a[i] = w * cnt; C_a[i] = cnt
        if cnt:
            j = 0; hit = False
            while j < len(E):
                if h[i] >= TP[j] and log[pid[j]][0] <= i:
                    R += w * (TP[j] / E[j] - 1.0 - d1.COST)
                    sum_inv -= w / E[j]
                    log[pid[j]][2] = i
                    E.pop(j); TP.pop(j); pid.pop(j); hit = True
                else:
                    j += 1
            if hit:
                lowest = min(E) if E else np.inf
    return df, dict(R=R_a, M=M_a, F=F_a, W=W_a, C=C_a, log=log)


def run(args):
    name = args
    rows = []
    for sma, target, near_mode in itertools.product((0, 50), TARGETS, ("rule", "always")):
        if target == "static" and near_mode == "always":
            continue
        near = BASE[name]["near"] if near_mode == "rule" else 0.0
        df, sim = simulate_m5(name, target, near, sma)
        t = df.index
        years = (t[-1] - t[0]).days / 365.25
        X = sim["R"] + sim["M"] - sim["F"]
        W, C = sim["W"], sim["C"]
        g25 = gr.g_budget(X, W, C, 0.25)
        eq25 = gr.eq_curve(X, g25)
        row = dict(index=name, variant="B" if sma else "A", target=target, near=near_mode, entries_yr=len(sim["log"]) / years,
                   g25=g25, ret25=float(g25 * (sim["R"][-1] - sim["F"][-1]) * 100 / years), mineq25=float(eq25.min()))
        for gf in (0.1, 0.2):
            e = gr.eq_curve(X, gf)
            stop = bool(((C > 0) & (e <= 0.5 * d1.MARGIN * gf * W)).any())
            row[f"ret_g{gf}"] = float(gf * (sim["R"][-1] - sim["F"][-1]) * 100 / years)
            row[f"mineq_g{gf}"] = float(e.min()); row[f"dd_g{gf}"] = gr.max_dd(e); row[f"stop_g{gf}"] = stop
        holds = [((t[ex] if ex >= 0 else t[-1]) - t[ent]).total_seconds() / 86400 for ent, _, ex in sim["log"]]
        row.update(hold_med=float(np.median(holds)), hold_p95=float(np.percentile(holds, 95)), hold_max=float(max(holds)),
                   open_end=int(C[-1]), pct_ge8=float((C >= 8).mean() * 100))
        rows.append(row)
    return rows


def main():
    with mp.Pool(2) as pool:
        parts = pool.map(run, list(BASE))
    res = pd.DataFrame([r for p in parts for r in p])
    out = Path(__file__).parent / "out" / "long_tp_target_m5.csv"
    res.to_csv(out, index=False)
    print("written", out, len(res))


if __name__ == "__main__":
    main()
