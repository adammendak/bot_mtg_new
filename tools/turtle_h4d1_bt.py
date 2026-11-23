#!/usr/bin/env python3
"""
turtle_h4d1_bt.py — test of pine/turtle_breakout_h4d1.pine (Turtle 20/10 in chart candles, long and short)
on our ten tickers, H4 and D1, Real candles and Heikin Ashi, over the last 12 months.

Rules (identical to the Pine indicator, completed candles only; short is the mirror image):
  long  : entry high > highest high of the previous N candles (N=20)      fill = max(band, open)
          stop = fill - 2 ATR20 (Wilder, previous candle); exit low < max(stop, lowest low of previous M=10)
          fill = min(open, level)
  short : entry low  < lowest low of the previous N candles               fill = min(band, open)
          stop = fill + 2 ATR20; exit high > min(stop, highest high of previous M)   fill = max(open, level)
  breakout candle: the stop is checked on the same candle (pessimistic); dual-hit -> stop first.
  Long & Short: one position at a time; no re-entry on the candle that closed a trade; if both bands break on
  the same flat candle the side whose band is closer to the open is taken.
1R = 2 ATR at entry. Net R subtracts one round-trip spread per trade (rough Capital.com values, same table
as tools/turtle_bt.py). No swaps. Capital demo mids. A trade still open at the end is marked at the last close.
Heikin Ashi mode runs every price (bands, ATR, fills) on HA candles -> NOT tradeable prices, signal quality only.

Tests besides the per-ticker table (for Long only, Short only and Long & Short, each pooled):
  * bootstrap 95% CI of avg net R (resampling trades)
  * random-entry baseline: same stop/exit rules, entries at random bars (drift only, no breakout timing);
    share of random runs that beat the real pooled avg net R
  * two half-year splits, buy&hold per ticker, exposure, max drawdown (closed trades, in R)
  * longer history for context (D1 since 2019 by calendar year, H4 since 2024-05 by half-year)

Usage:
  python tools/turtle_h4d1_bt.py                      # last 365 days, all tickers
  python tools/turtle_h4d1_bt.py --end 2026-11-23 --days 365 --symbols XAU,US100
"""
from __future__ import annotations

import argparse
import math
import random
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from tools.capital_data import Capital  # noqa: E402

N, M, ATR_LEN, MULT = 20, 10, 20, 2.0
# name -> (epic, rough round-trip spread in price units)
SYMBOLS = {"XAU": ("GOLD", 0.35), "BTC": ("BTCUSD", 60.0), "US100": ("US100", 1.5), "GER40": ("DE40", 1.5),
           "EURUSD": ("EURUSD", 0.00007), "XAG": ("SILVER", 0.025), "J225": ("J225", 10.0),
           "USDJPY": ("USDJPY", 0.010), "US500": ("US500", 0.5), "US30": ("US30", 2.5)}
TFS = {"D1": ("DAY", timedelta(days=1), datetime(2019, 1, 1, tzinfo=timezone.utc)),
       "H4": ("HOUR_4", timedelta(hours=4), datetime(2024, 5, 1, tzinfo=timezone.utc))}
SIDES = {"Long only": (1,), "Short only": (-1,), "Long & Short": (1, -1)}
SIMS = 300
BOOT = 5000


def heikin(rows):
    out = []
    for i, r in enumerate(rows):
        hc = (r["open"] + r["high"] + r["low"] + r["close"]) / 4.0
        ho = (r["open"] + r["close"]) / 2.0 if i == 0 else (out[-1]["open"] + out[-1]["close"]) / 2.0
        out.append(dict(time=r["time"], open=ho, high=max(r["high"], ho, hc), low=min(r["low"], ho, hc), close=hc))
    return out


class Series:
    """Bars plus the per-candle levels (all from COMPLETED earlier candles)."""

    def __init__(self, rows):
        self.t = [r["time"] for r in rows]
        self.o = [r["open"] for r in rows]
        self.h = [r["high"] for r in rows]
        self.l = [r["low"] for r in rows]
        self.c = [r["close"] for r in rows]
        n = len(rows)
        tr = [self.h[0] - self.l[0]] + [max(self.h[i] - self.l[i], abs(self.h[i] - self.c[i - 1]),
                                            abs(self.l[i] - self.c[i - 1])) for i in range(1, n)]
        atr = [math.nan] * n
        for i in range(n):
            if i == ATR_LEN - 1:
                atr[i] = sum(tr[:ATR_LEN]) / ATR_LEN
            elif i >= ATR_LEN:
                atr[i] = (atr[i - 1] * (ATR_LEN - 1) + tr[i]) / ATR_LEN
        self.n = n
        self.warm = max(N, M, ATR_LEN) + 1
        self.ent_hi = [math.nan] * n   # long entry band
        self.ent_lo = [math.nan] * n   # short entry band
        self.ext_lo = [math.nan] * n   # long exit band
        self.ext_hi = [math.nan] * n   # short exit band
        self.atr = [math.nan] * n
        for i in range(self.warm, n):
            self.ent_hi[i] = max(self.h[i - N:i])
            self.ent_lo[i] = min(self.l[i - N:i])
            self.ext_lo[i] = min(self.l[i - M:i])
            self.ext_hi[i] = max(self.h[i - M:i])
            self.atr[i] = atr[i - 1]


def run_exit(s, j, side, stop):
    """Walk from the entry candle j to the exit. Returns (exit_index, exit_price, open_at_end)."""
    if side > 0:
        if s.l[j] < stop:
            return j, stop, False
        for k in range(j + 1, s.n):
            lvl = max(stop, s.ext_lo[k])
            if s.l[k] < lvl:
                return k, min(s.o[k], lvl), False
    else:
        if s.h[j] > stop:
            return j, stop, False
        for k in range(j + 1, s.n):
            lvl = min(stop, s.ext_hi[k])
            if s.h[k] > lvl:
                return k, max(s.o[k], lvl), False
    return s.n - 1, s.c[-1], True


def breakout_trades(s, spread, sides):
    trades = []
    i = s.warm
    while i < s.n:
        a = s.atr[i]
        if math.isnan(a):
            i += 1
            continue
        long_ok = 1 in sides and s.h[i] > s.ent_hi[i]
        short_ok = -1 in sides and s.l[i] < s.ent_lo[i]
        if not long_ok and not short_ok:
            i += 1
            continue
        if long_ok and short_ok:
            side = 1 if (s.ent_hi[i] - s.o[i]) <= (s.o[i] - s.ent_lo[i]) else -1
        else:
            side = 1 if long_ok else -1
        entry = max(s.ent_hi[i], s.o[i]) if side > 0 else min(s.ent_lo[i], s.o[i])
        risk = MULT * a
        stop = entry - side * risk
        k, px, open_end = run_exit(s, i, side, stop)
        trades.append(dict(i_in=i, i_out=k, t_in=s.t[i], t_out=s.t[k], side=side, r=side * (px - entry) / risk,
                           rn=(side * (px - entry) - spread) / risk, bars=max(1, k - i), open_end=open_end))
        i = k + 1
    return trades


def random_trades(s, spread, i0, p, rng, sides):
    """Same stop/exit rules; entries at the open of random flat candles (prob p per flat candle),
    direction random among `sides`."""
    rs = []
    i = max(i0, s.warm)
    while i < s.n:
        gap = int(math.log(1.0 - rng.random()) / math.log(1.0 - p)) if p < 1 else 0
        i += gap
        if i >= s.n or math.isnan(s.atr[i]):
            break
        side = rng.choice(sides)
        entry = s.o[i]
        risk = MULT * s.atr[i]
        stop = entry - side * risk
        k, px, _ = run_exit(s, i, side, stop)
        rs.append((side * (px - entry) - spread) / risk)
        i = k + 1
    return rs


def stat(trs, key="rn"):
    if not trs:
        return dict(n=0, win=0.0, avg=0.0, sum=0.0, pf=0.0, dd=0.0, bars=0.0)
    rs = [t[key] for t in trs]
    w = sum(r for r in rs if r > 0)
    lo = -sum(r for r in rs if r <= 0)
    eq = peak = dd = 0.0
    for t in sorted(trs, key=lambda x: x["t_out"]):
        eq += t[key]
        peak = max(peak, eq)
        dd = max(dd, peak - eq)
    return dict(n=len(rs), win=100.0 * sum(1 for r in rs if r > 0) / len(rs), avg=sum(rs) / len(rs),
                sum=sum(rs), pf=(w / lo if lo > 0 else math.inf), dd=dd, bars=sum(t["bars"] for t in trs) / len(rs))


def row(name, st, extra=""):
    pf = "inf" if st["pf"] == math.inf else f"{st['pf']:.2f}"
    return (f"| {name} | {st['n']} | {st['win']:.0f}% | {st['avg']:+.2f} | {st['sum']:+.1f} | {pf} | "
            f"{st['dd']:.1f} | {st['bars']:.0f} |{extra}")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:  # noqa: BLE001
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--end", default=None, help="UTC date, default now")
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--symbols", default="")
    ap.add_argument("--out", default="tools/out/turtle_h4d1_report.md")
    args = ap.parse_args()

    end = datetime.fromisoformat(args.end).replace(tzinfo=timezone.utc) if args.end else datetime.now(timezone.utc)
    start = end - timedelta(days=args.days)
    mid = start + (end - start) / 2
    names = [x.strip().upper() for x in args.symbols.split(",") if x.strip()] or list(SYMBOLS)
    cap = Capital(demo=True)
    cap.login()

    data = {}  # (tf, name) -> rows (complete candles only)
    for name in names:
        epic = SYMBOLS[name][0]
        for tf, (res, span, since) in TFS.items():
            # Floor `end` to the candle grid: only COMPLETED candles are used, and Capital answers 404 for a
            # window that holds no new candle start (e.g. the running candle), which capital_data reports as
            # "epic not found".
            secs = int(span.total_seconds())
            end_tf = datetime.fromtimestamp(int(end.timestamp()) // secs * secs, tz=timezone.utc)
            rows = cap.candles(epic, res, since, end_tf)
            data[(tf, name)] = [r for r in rows if r["time"] + span <= end_tf]

    out = []
    p = out.append
    p("# Turtle 20/10, H4 and D1, long and short — last 12 months")
    p("")
    p(f"Window **{start.date()} -> {end.date()}**; trades counted by entry time. Capital demo mids, rough round-trip "
      f"spread subtracted (net R), no swaps. 1R = 2 ATR20 at entry. Open trades marked at the last close.")
    p("")
    rng = random.Random(7)
    hdr = "| | n | win | avg net R | sum net R | PF | maxDD (R) | avg bars |"
    sep = "|---|---:|---:|---:|---:|---:|---:|---:|"
    for mode in ("Real", "Heikin Ashi"):
        for tf in TFS:
            series = {}
            for name in names:
                rows = data[(tf, name)]
                if len(rows) >= 100:
                    series[name] = Series(heikin(rows) if mode == "Heikin Ashi" else rows)
            p(f"## {tf} — {mode} candles")
            p("")
            summary = []
            for label, sides in SIDES.items():
                per, pooled = {}, []
                for name, s in series.items():
                    trs = [t for t in breakout_trades(s, SYMBOLS[name][1], sides) if t["t_in"] >= start]
                    for t in trs:
                        t["sym"] = name
                    per[name] = trs
                    pooled += trs
                p(f"### {label}")
                p("")
                p(hdr + " B&H % | in market % |")
                p(sep + "---:|---:|")
                for name, s in series.items():
                    idx = [i for i in range(s.n) if s.t[i] >= start]
                    bh = 100.0 * (s.c[-1] / s.c[idx[0]] - 1.0) if idx else float("nan")
                    inmkt = 100.0 * sum(t["bars"] for t in per[name]) / max(1, len(idx))
                    p(row(name, stat(per[name]), f" {bh:+.0f}% | {inmkt:.0f}% |"))
                st = stat(pooled)
                p(row("**POOLED**", st, " | |"))
                p("")
                if not pooled:
                    continue
                gross = stat(pooled, "r")
                opens = sum(1 for t in pooled if t["open_end"])
                nl = sum(1 for t in pooled if t["side"] > 0)
                p(f"Gross (before spread) pooled: avg {gross['avg']:+.2f} R, PF {gross['pf']:.2f}. Long trades {nl}, "
                  f"short trades {len(pooled) - nl}. Still open at the end: {opens}. Return at 1% risk per trade "
                  f"(not compounded, trades may overlap): {st['sum']:+.1f}%.")
                rs = [t["rn"] for t in pooled]
                brng = random.Random(11)
                means = sorted(sum(brng.choice(rs) for _ in rs) / len(rs) for _ in range(BOOT))
                lo, hi = means[int(0.025 * BOOT)], means[int(0.975 * BOOT)]
                base = []
                for _ in range(SIMS):
                    allr = []
                    for name, s in series.items():
                        i0 = next(i for i in range(s.n) if s.t[i] >= start)
                        pr = max(1, len(per[name])) / max(1, s.n - i0)
                        allr += random_trades(s, SYMBOLS[name][1], i0, min(0.99, pr), rng, sides)
                    if allr:
                        base.append(sum(allr) / len(allr))
                base.sort()
                beat = 100.0 * sum(1 for b in base if b >= st["avg"]) / len(base)
                h1 = stat([t for t in pooled if t["t_in"] < mid])
                h2 = stat([t for t in pooled if t["t_in"] >= mid])
                p(f"Bootstrap 95% CI of pooled avg net R: [{lo:+.2f}, {hi:+.2f}]. Random-entry baseline ({SIMS} runs, "
                  f"same exits): median {base[len(base)//2]:+.2f} R, 95th pct {base[int(0.95*len(base))]:+.2f} R; "
                  f"{beat:.0f}% of random runs beat the real avg.")
                p(f"Halves: {start.date()}..{mid.date()} n={h1['n']} avg {h1['avg']:+.2f} PF {h1['pf']:.2f} | "
                  f"{mid.date()}..{end.date()} n={h2['n']} avg {h2['avg']:+.2f} PF {h2['pf']:.2f}.")
                p("")
                summary.append((label, st, beat, (lo, hi)))
            p("**Summary " + tf + " " + mode + "**")
            p("")
            p("| side | n | avg net R | sum net R | PF | 95% CI avg | random beats |")
            p("|---|---:|---:|---:|---:|---|---:|")
            for label, st, beat, ci in summary:
                pf = "inf" if st["pf"] == math.inf else f"{st['pf']:.2f}"
                p(f"| {label} | {st['n']} | {st['avg']:+.2f} | {st['sum']:+.1f} | {pf} | "
                  f"[{ci[0]:+.2f}, {ci[1]:+.2f}] | {beat:.0f}% |")
            p("")

    # longer history (Real candles) for context
    p("## Context: longer history (Real candles, pooled, net R)")
    p("")
    for tf, label in (("D1", "calendar year of entry"), ("H4", "half-year of entry")):
        for sl, sides in SIDES.items():
            allt = []
            for name in names:
                rows = data[(tf, name)]
                if len(rows) >= 100:
                    allt += breakout_trades(Series(rows), SYMBOLS[name][1], sides)
            p(f"**{tf} — {sl}** by {label}")
            p("")
            p("| period | n | win | avg net R | sum net R | PF |")
            p("|---|---:|---:|---:|---:|---:|")
            buckets = {}
            for t in allt:
                y = t["t_in"].year if tf == "D1" else f"{t['t_in'].year}-H{1 if t['t_in'].month <= 6 else 2}"
                buckets.setdefault(y, []).append(t)
            for k in sorted(buckets):
                st = stat(buckets[k])
                pf = "inf" if st["pf"] == math.inf else f"{st['pf']:.2f}"
                p(f"| {k} | {st['n']} | {st['win']:.0f}% | {st['avg']:+.2f} | {st['sum']:+.1f} | {pf} |")
            st = stat(allt)
            p(f"| **all** | {st['n']} | {st['win']:.0f}% | {st['avg']:+.2f} | {st['sum']:+.1f} | {st['pf']:.2f} |")
            p("")

    text = "\n".join(out)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
