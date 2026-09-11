#!/usr/bin/env python3
"""
h4_ha_cloud_bt.py — rebuild of the "H4 HA hunt + oscillator cloud hold" research
backtest (see Downloads/h4_ha_cloud_aug_report.md), ported to the bot's own
indicator math so the result maps 1:1 onto server/.../sdd/*.

Data: Capital.com demo mids via tools/capital_data.py (same bars the live bot sees).
No spread, no commission, no orders. Stop-first on dual-hit bars.

Universe (SDD book): GER40=DE40, XAU=GOLD, US100, EURUSD, BTCUSD. BTC skips PP.

Entry stack (closed M15), shared by every variant:
  HA colour flip  AND  M15 close vs RMA33 vs RMA133 stacked with dir
  AND  H1 with (HA same dir OR H1 close/RMA33/RMA133 stacked)
  AND  daily PP with (prev session (H+L+C)/3, roll 21:00 UTC; BTC skips)

Risk: stop = 2.5 x last-closed H1 Wilder ATR14. 1R = 1x that ATR.
  10 PLN per R (1% of 1000, no compounding). One open ticket per name per book.

Variants:
  CTRL_SDD       no H4 gate; exit full 2R or stop (classic occupancy)
  H4_HA          H4 HA hunt gate; exit H4 HA flip-against or stop; cap 2/regime
  H4_STOCH_SOFT  + exit on H4 Stoch cloud (%K>80 / %K x<%D while %K>70 / mirror)
  H4_STOCH_HARD  STOCH_SOFT but only A+ (H12 Stoch trough/peak) entries
  H4_RSI_SOFT    + exit on H4 RSI (>70 long / <30 short) or HA flip or stop
  H4_RSI_HARD    RSI_SOFT but only A+ (H12 RSI trough/peak) entries

Usage:
  python tools/h4_ha_cloud_bt.py --start 2025-09-01 --end 2026-09-01
  python tools/h4_ha_cloud_bt.py --start 2026-08-01 --end 2026-09-01 --no-btc
"""
from __future__ import annotations

import argparse
import bisect
import json
import math
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from tools.capital_data import Capital  # noqa: E402

# ---------------------------------------------------------------------------
# config
# ---------------------------------------------------------------------------
EPICS = {"GER40": "DE40", "XAU": "GOLD", "US100": "US100", "EURUSD": "EURUSD", "BTCUSD": "BTCUSD",
         "USDJPY": "USDJPY",
         # candidate trending instruments (screen)
         "US500": "US500", "US30": "US30", "J225": "J225", "XAG": "SILVER", "GBPJPY": "GBPJPY",
         "EURJPY": "EURJPY", "USDCHF": "USDCHF", "USDCAD": "USDCAD", "GBPUSD": "GBPUSD",
         "COPPER": "COPPER", "WTI": "OIL_CRUDE"}
SKIP_PP = {"BTCUSD"}

RMA_FAST, RMA_SLOW = 33, 133   # SddEngine default. Sweep 100/133/144: H4/M15 prefers 100,
#                                H12/H1 prefers 144 (all 2nd-order, PF 1.4-2.0 / DD<12%).
ATR_PERIOD = 14
STOP_ATR_MULT = 2.5
TP_R = 2.0                     # CTRL_SDD only
PP_ROLL_HOUR_UTC = 21
RISK_PLN = 10.0               # per R, 1% of 1000, no compounding
CAP_PER_REGIME = 2

STOCH_K_LEN, STOCH_K_SMOOTH, STOCH_D_SMOOTH = 14, 3, 3
RSI_PERIOD = 14
OB, OS = 80.0, 20.0           # H4 stoch cloud top / bottom
K_CROSS_GATE_HI, K_CROSS_GATE_LO = 70.0, 30.0
RSI_OB, RSI_OS = 70.0, 30.0
APLUS_STOCH_LO, APLUS_STOCH_HI = 25.0, 75.0
APLUS_RSI_LO, APLUS_RSI_HI = 35.0, 65.0

# HTS ribbon entry (ports HtsEngine.java / Band.java): RMA band on high/low,
# fast 33 / slow 144, pullback into the fast band then a close-body breakout,
# slow band sloping with the trade, fast band clear of the slow band.
HTS_FAST, HTS_SLOW = 33, 144
HTS_PULLBACK, HTS_SLOPE = 10, 20
HTS_SEP, HTS_STOPBUF = 0.25, 0.25

# D1_EXTREME gate: daily Stochastic 14/3/3 on 21:00-UTC session bars must already
# be OS (< 20, longs) / OB (> 80, shorts) at the H4 HA flip that would open a hunt.
# Mid-range D1 -> that H4 regime is not huntable. (from h4_d1_sdd_vs_hts_report.md)
D1_OS, D1_OB = 20.0, 80.0

# SDD-entry family (report's original stack) + HTS-entry family (H4 HA flip = hunt
# trigger, HTS ribbon = the M15 entry, cloud-hold exit). *_D1 = same, gated by D1_EXTREME.
SDD_VARIANTS = ["CTRL_SDD", "H4_HA", "H4_HA_TRAIL", "H4_TP15", "H4_TP20",
                "H4_STOCH_SOFT", "H4_STOCH_HARD", "H4_RSI_SOFT", "H4_RSI_HARD",
                "SDD_H4_D1", "SDD_H4_D1_STOCH", "SDD_H4_D1_RSI"]
HTS_VARIANTS = ["HTS_HA", "HTS_STOCH", "HTS_RSI",
                "HTS_H4_D1", "HTS_H4_D1_STOCH", "HTS_H4_D1_RSI"]
# H12-hunt / H1-entry family (user test): H12 HA colour change = hunt trigger, entry on H1.
# 1R = 2.5 x last-closed H4 Wilder ATR14. Compared: cloud-hold vs fixed 2R/1.5R vs trail.
H12H1_VARIANTS = ["H12_SDD", "H12_SDD_2R", "H12_SDD_1R5", "H12_SDD_TRAIL", "H12_HTS"]
VARIANTS = SDD_VARIANTS + HTS_VARIANTS + H12H1_VARIANTS
TRAIL_ATR_MULT = 2.5
# hunted SDD entry + fixed TP (R:R comparison against cloud-hold)
FIXED_TP_M15 = {"H4_TP15": 1.5, "H4_TP20": 2.0}
FIXED_TP_H1 = {"H12_SDD_2R": 2.0, "H12_SDD_1R5": 1.5}
CLOUD_STOCH = {"H4_STOCH_SOFT", "H4_STOCH_HARD", "HTS_STOCH", "SDD_H4_D1_STOCH", "HTS_H4_D1_STOCH"}
CLOUD_RSI = {"H4_RSI_SOFT", "H4_RSI_HARD", "HTS_RSI", "SDD_H4_D1_RSI", "HTS_H4_D1_RSI"}

# H1 Supertrend HTF filter (PR #132's pine/ha_hunt_m15_h1_supertrend.pine, "variant B"):
# M15 entry (HA flip + stack, no separate H1 WITH check) gated by the last-closed H1
# Supertrend direction instead of the H4 HA hunt regime. Stop/cap/cloud-exit unchanged
# (2.5x H1 ATR14 stop; exit = H1 ST flips against OR stop; cap 2 fills/ST-regime).
ST_ATR_LEN, ST_FACTOR = 10, 2.0   # matches the PR's Pine defaults (Supertrend.java default factor is 3.0)
ST_SLOW = 100                     # matches HA4's default slow RMA (PR uses the same)
ST_VARIANTS = ["H4_ST"]
VARIANTS = VARIANTS + ST_VARIANTS

# True (un-hunt-gated) HTS ribbon, matching HtsEngine.evaluate() 1:1: entryDir
# needs LTF band clear AND the HTF band clear in the same direction (no HA hunt
# regime at all — that's an HA-hunt-only concept, HTS never had it). Two exit
# reads compared against a single fixed TP1 (the live bot's runner keeps a
# partial position trailing past TP1 with a locked 1R stop; that partial-fill
# bookkeeping isn't modelled here, so HTS_TRUE_TRAIL is the full-size proxy for
# "let it run" and HTS_TRUE_TP is the full-size proxy for "bank TP1 in full"):
#   HTS_TRUE_TP    stop = far fast-band edge + 0.25xband buffer; TP1 = RR(2.0) x stop dist
#   HTS_TRUE_TRAIL same stop; trail to the fast-band far edge each bar (never loosen);
#                  hard exit when the closed body crosses the slow band (Band.bodyBeyondSlow)
HTS_TRUE_VARIANTS = ["HTS_TRUE_TP", "HTS_TRUE_TRAIL"]
VARIANTS = VARIANTS + HTS_TRUE_VARIANTS

# M5 entry (HA1 shape: HA flip + M5 RMA33/100 stack), gated AND stopped by the H1
# Supertrend — not just a direction filter this time: the stop IS the current
# last-closed H1 Supertrend band level (up[] for longs / dn[] for shorts), which
# ratchets on its own by construction while the trend holds, so no separate ATR
# stop or trail math is needed. Cap 2 fills/ST-regime, run standalone (own M5 fetch).
ST1_VARIANTS = ["M5_ST1"]
VARIANTS = VARIANTS + ST1_VARIANTS

# User's "dream" config: M45 Supertrend = direction filter + self-ratcheting SL,
# M5 fast-band cross = entry, TP1 1:1 on half + the other half trails (SL = the
# M45 ST band) until an M5 body closes beyond the slow band. See run_m45_st_hts().
TP_R_1TO1 = 1.0
# M45_ST_HTS_V2 = PR #137's locked Pine spec instead of the verbal one: TP1 2:1,
# M45-structure gate required, runner's full-exit band is M45's own slow band.
ST_HTS_VARIANTS = ["M45_ST_HTS", "M45_ST_HTS_V2"]
VARIANTS = VARIANTS + ST_HTS_VARIANTS

# Same "dream" config, one timeframe rung up: H1 Supertrend (filter+SL) / M15
# entry, HA4's own H4-hunt/M15-entry shape with Supertrend swapping in for the
# H4 HA hunt gate. Uses the already-fetched M15 feed — no separate M5 fetch.
ST1_HTS_VARIANTS = ["M15_ST1_HTS", "M15_ST1_HTS_V2"]
VARIANTS = VARIANTS + ST1_HTS_VARIANTS

# PR #140's locked "v3 bakeoff": TP1 1:2 half -> BE -> confirmed LTF HA-flip
# exit (no trailing, no ST-flip exit, no slow-band exit — all explicitly OFF
# in their defaults). See run_st_v3().
ST_V3_VARIANTS = ["M5_ST_V3", "M15_ST_V3"]
VARIANTS = VARIANTS + ST_V3_VARIANTS

LONGS_ONLY = False   # set from --longs-only; when True, short signals are dropped


# ---------------------------------------------------------------------------
# indicators — ported from server/src/main/java/com/adam/server/sdd/*
# ---------------------------------------------------------------------------
class Bar:
    __slots__ = ("t", "o", "h", "l", "c")

    def __init__(self, t, o, h, l, c):
        self.t, self.o, self.h, self.l, self.c = t, o, h, l, c


def to_bars(rows):
    return [Bar(r["time"], r["open"], r["high"], r["low"], r["close"]) for r in rows]


def heiken_ashi(bars):
    """HeikenAshi.java — first haOpen=(o+c)/2; bullish = haClose >= haOpen."""
    out = []
    for i, c in enumerate(bars):
        ha_close = (c.o + c.h + c.l + c.c) / 4.0
        if i == 0:
            ha_open = (c.o + c.c) / 2.0
        else:
            p = out[i - 1]
            ha_open = (p.o + p.c) / 2.0
        ha_high = max(c.h, ha_open, ha_close)
        ha_low = min(c.l, ha_open, ha_close)
        out.append(Bar(c.t, ha_open, ha_high, ha_low, ha_close))
    return out


def wilder_rma(src, period):
    """Wilder.java rma — out[period-1]=SMA; then (prev*(n-1)+cur)/n."""
    out = [math.nan] * len(src)
    if period <= 0 or len(src) < period:
        return out
    s = sum(src[:period])
    out[period - 1] = s / period
    for i in range(period, len(src)):
        out[i] = (out[i - 1] * (period - 1) + src[i]) / period
    return out


def wilder_atr(bars, period):
    """Wilder.java atr — tr[0]=h-l; then max(range,|h-pc|,|l-pc|); rma(tr)."""
    if not bars:
        return []
    tr = [bars[0].h - bars[0].l]
    for i in range(1, len(bars)):
        c, p = bars[i], bars[i - 1]
        tr.append(max(c.h - c.l, abs(c.h - p.c), abs(c.l - p.c)))
    return wilder_rma(tr, period)


def supertrend_bands(bars, atr_len, factor):
    """Classic TradingView ta.supertrend(factor, atrPeriod): hl2 +- factor*ATR bands,
    ratcheted, direction flips when close crosses the PREVIOUS bar's opposite final
    band. Returns (trend, up, dn) arrays aligned to `bars`: trend +1 bull / -1 bear
    (NaN-free once ATR is defined; seeded to +1 on the first defined bar); up/dn are
    the ratcheted band levels themselves — up is the live trailing-stop level while
    trend==+1 (non-decreasing until a flip resets it), dn the mirror for trend==-1."""
    atr = wilder_atr(bars, atr_len)
    n = len(bars)
    up = [math.nan] * n
    dn = [math.nan] * n
    trend = [1] * n
    for i in range(n):
        if math.isnan(atr[i]):
            continue
        hl2 = (bars[i].h + bars[i].l) / 2.0
        basic_up = hl2 - factor * atr[i]
        basic_dn = hl2 + factor * atr[i]
        if math.isnan(up[i - 1]) if i > 0 else True:
            up[i], dn[i], trend[i] = basic_up, basic_dn, 1
            continue
        up1, dn1 = up[i - 1], dn[i - 1]
        up[i] = max(basic_up, up1) if bars[i - 1].c > up1 else basic_up
        dn[i] = min(basic_dn, dn1) if bars[i - 1].c < dn1 else basic_dn
        prev = trend[i - 1]
        if prev == -1 and bars[i].c > dn1:
            trend[i] = 1
        elif prev == 1 and bars[i].c < up1:
            trend[i] = -1
        else:
            trend[i] = prev
    return trend, up, dn


def supertrend(bars, atr_len, factor):
    """Trend-only convenience wrapper around supertrend_bands()."""
    return supertrend_bands(bars, atr_len, factor)[0]


def last_valid(xs):
    for v in reversed(xs):
        if not (isinstance(v, float) and math.isnan(v)):
            return v
    return math.nan


def stacked(close, rma_fast, rma_slow, buy):
    if math.isnan(rma_fast) or math.isnan(rma_slow):
        return False
    if buy:
        return close > rma_fast > rma_slow
    return close < rma_fast < rma_slow


def session_day(t: datetime, roll_hour: int):
    if t.hour >= roll_hour:
        return (t + timedelta(days=1)).date()
    return t.date()


def build_pp_lookup(bars, roll_hour=PP_ROLL_HOUR_UTC):
    """(sorted session days, PP per day) over the full series. A past session's
    OHLC is complete, so PivotPoints.previousCompleted for any bar reduces to
    'largest day < the bar's session day' — a bisect, not an O(n) rescan."""
    agg = {}
    for b in bars:
        d = session_day(b.t, roll_hour)
        a = agg.get(d)
        if a is None:
            agg[d] = [b.h, b.l, b.c]
        else:
            a[0] = max(a[0], b.h)
            a[1] = min(a[1], b.l)
            a[2] = b.c
    days = sorted(agg)
    pps = [(agg[d][0] + agg[d][1] + agg[d][2]) / 3.0 for d in days]
    return days, pps


def pp_asof(days, pps, as_of: datetime, roll_hour=PP_ROLL_HOUR_UTC):
    cur = session_day(as_of, roll_hour)
    i = bisect.bisect_left(days, cur) - 1
    return pps[i] if i >= 0 else None


def sma(xs, n):
    out = [math.nan] * len(xs)
    run = 0.0
    for i, v in enumerate(xs):
        run += v
        if i >= n:
            run -= xs[i - n]
        if i >= n - 1:
            out[i] = run / n
    return out


def stoch(bars, k_len=STOCH_K_LEN, k_smooth=STOCH_K_SMOOTH, d_smooth=STOCH_D_SMOOTH):
    """Classic stochastic on a bar series. Returns (%K, %D) arrays aligned to bars."""
    n = len(bars)
    raw = [math.nan] * n
    for i in range(n):
        if i < k_len - 1:
            continue
        window = bars[i - k_len + 1: i + 1]
        hh = max(b.h for b in window)
        ll = min(b.l for b in window)
        rng = hh - ll
        raw[i] = 50.0 if rng == 0 else 100.0 * (bars[i].c - ll) / rng
    k = sma([0.0 if math.isnan(x) else x for x in raw], k_smooth)
    for i in range(n):
        if math.isnan(raw[i]) or i < k_len - 1 + k_smooth - 1:
            k[i] = math.nan
    d = sma([0.0 if math.isnan(x) else x for x in k], d_smooth)
    for i in range(n):
        if math.isnan(k[i]) or i < k_len - 1 + k_smooth - 1 + d_smooth - 1:
            d[i] = math.nan
    return k, d


def rsi(bars, period=RSI_PERIOD):
    n = len(bars)
    out = [math.nan] * n
    if n < period + 1:
        return out
    gains, losses = [], []
    for i in range(1, n):
        ch = bars[i].c - bars[i - 1].c
        gains.append(max(ch, 0.0))
        losses.append(max(-ch, 0.0))
    avg_g = sum(gains[:period]) / period
    avg_l = sum(losses[:period]) / period
    out[period] = 100.0 if avg_l == 0 else 100.0 - 100.0 / (1.0 + avg_g / avg_l)
    for i in range(period + 1, n):
        avg_g = (avg_g * (period - 1) + gains[i - 1]) / period
        avg_l = (avg_l * (period - 1) + losses[i - 1]) / period
        out[i] = 100.0 if avg_l == 0 else 100.0 - 100.0 / (1.0 + avg_g / avg_l)
    return out


def d1_session_bars(h1_bars, roll_hour=PP_ROLL_HOUR_UTC):
    """Daily bars on the 21:00-UTC session (same roll as PP). Returns (bars, end_instants)."""
    agg = {}
    order = []
    for b in h1_bars:
        d = session_day(b.t, roll_hour)
        a = agg.get(d)
        if a is None:
            agg[d] = [b.o, b.h, b.l, b.c]
            order.append(d)
        else:
            a[1] = max(a[1], b.h)
            a[2] = min(a[2], b.l)
            a[3] = b.c
    bars, ends = [], []
    for d in order:
        o, h, l, c = agg[d]
        end = datetime(d.year, d.month, d.day, roll_hour, tzinfo=timezone.utc)
        bars.append(Bar(end, o, h, l, c))
        ends.append(end)
    return bars, ends


def hts_ribbon(i, hi, close, lfu, lfl, lsu, lsl, hfu, hfl, hsu, hsl, ltf):
    """HTS ribbon (HtsEngine.evaluate) on any ltf index i + htf index hi.
    Returns (dir, entry, stop, one_r) or None."""
    if i < HTS_SLOW + HTS_PULLBACK + HTS_SLOPE + 2 or hi < 0:
        return None
    if math.isnan(lfu[i]) or math.isnan(lsu[i]) or math.isnan(hfu[hi]) or math.isnan(hsu[hi]):
        return None
    up = close > lfu[i] and lfl[i] > lsu[i] and hfl[hi] > hsu[hi]
    dn = close < lfl[i] and lfu[i] < lsl[i] and hfu[hi] < hsl[hi]
    if up:
        buy = True
    elif dn:
        buy = False
    else:
        return None
    j = i - HTS_SLOPE
    if math.isnan(lsu[j]):
        return None
    mid, midp = (lsu[i] + lsl[i]) / 2.0, (lsu[j] + lsl[j]) / 2.0
    if (mid <= midp) if buy else (mid >= midp):
        return None
    slw = lsu[i] - lsl[i]
    sep = (lfl[i] - lsu[i]) if buy else (lsl[i] - lfu[i])
    if slw <= 0 or sep < HTS_SEP * slw:
        return None
    pulled = False
    for k in range(max(0, i - HTS_PULLBACK), i):
        if math.isnan(lfu[k]):
            continue
        if (ltf[k].l <= lfu[k]) if buy else (ltf[k].h >= lfl[k]):
            pulled = True
            break
    if not pulled:
        return None
    buf = HTS_STOPBUF * max(0.0, lfu[i] - lfl[i])
    stop = (lfl[i] - buf) if buy else (lfu[i] + buf)
    if (stop >= close) if buy else (stop <= close):
        return None
    return (1 if buy else -1, close, stop, abs(close - stop))


def band_rma(bars, period):
    """Band.rma — RMA of highs and RMA of lows; upper=max, lower=min; NaN until ready."""
    rh = wilder_rma([b.h for b in bars], period)
    rl = wilder_rma([b.l for b in bars], period)
    up = [math.nan] * len(bars)
    lo = [math.nan] * len(bars)
    for i in range(len(bars)):
        if not (math.isnan(rh[i]) or math.isnan(rl[i])):
            up[i], lo[i] = max(rh[i], rl[i]), min(rh[i], rl[i])
    return up, lo


def resample(h1_bars, hours):
    """H1 -> Nh UTC buckets (report sec.9). Bucket time = bucket start."""
    buckets = {}
    order = []
    span = hours * 3600
    for b in h1_bars:
        key = (int(b.t.timestamp()) // span) * span
        if key not in buckets:
            buckets[key] = Bar(datetime.fromtimestamp(key, timezone.utc), b.o, b.h, b.l, b.c)
            order.append(key)
        else:
            x = buckets[key]
            x.h = max(x.h, b.h)
            x.l = min(x.l, b.l)
            x.c = b.c
    return [buckets[k] for k in order]


# ---------------------------------------------------------------------------
# as-of helpers
# ---------------------------------------------------------------------------
def idx_asof(bars, close_time: datetime, bar_span_h: int):
    """Index of the last bar fully closed at close_time (bar.t + span <= close_time)."""
    lo, hi, ans = 0, len(bars) - 1, -1
    span = timedelta(hours=bar_span_h)
    while lo <= hi:
        mid = (lo + hi) // 2
        if bars[mid].t + span <= close_time:
            ans = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return ans


# ---------------------------------------------------------------------------
# backtest
# ---------------------------------------------------------------------------
class Trade:
    def __init__(self, sym, variant, dir_, t_in, entry, stop, one_r, entry_osc, aplus, regime):
        self.sym, self.variant, self.dir = sym, variant, dir_
        self.t_in, self.entry, self.stop, self.one_r = t_in, entry, stop, one_r
        self.entry_osc, self.aplus, self.regime = entry_osc, aplus, regime
        self.t_out = None
        self.exit = None
        self.r = None
        self.reason = None
        self.bars = 0
        self.late = False


def run_symbol(sym, m15r, h1r, start, end):
    m15 = to_bars(m15r)
    h1 = to_bars(h1r)
    h4 = resample(h1, 4)
    h12 = resample(h1, 12)

    ha_m15 = heiken_ashi(m15)
    ha_h4 = heiken_ashi(h4)
    close_m15 = [b.c for b in m15]
    rma33_m15 = wilder_rma(close_m15, RMA_FAST)
    rma133_m15 = wilder_rma(close_m15, RMA_SLOW)

    close_h1 = [b.c for b in h1]
    rma33_h1 = wilder_rma(close_h1, RMA_FAST)
    rma133_h1 = wilder_rma(close_h1, RMA_SLOW)
    ha_h1 = heiken_ashi(h1)
    atr_h1 = wilder_atr(h1, ATR_PERIOD)

    # H4_ST: M15 entry gated by H1 Supertrend instead of the H4 HA hunt regime.
    # Uses its own slow RMA (ST_SLOW=100, HA4's default) — independent of the
    # shared RMA_SLOW used by the H4-hunt family.
    rma_st_m15 = wilder_rma(close_m15, ST_SLOW)
    st_h1 = supertrend(h1, ST_ATR_LEN, ST_FACTOR)
    st_regime_id = [0] * len(h1)
    for i in range(1, len(h1)):
        st_regime_id[i] = i if st_h1[i] != st_h1[i - 1] else st_regime_id[i - 1]

    k4, d4 = stoch(h4)
    rsi4 = rsi(h4)
    k12, d12 = stoch(h12)
    rsi12 = rsi(h12)

    lb_fu, lb_fl = band_rma(m15, HTS_FAST)
    lb_su, lb_sl = band_rma(m15, HTS_SLOW)
    hb_fu, hb_fl = band_rma(h4, HTS_FAST)
    hb_su, hb_sl = band_rma(h4, HTS_SLOW)

    d1_bars, d1_ends = d1_session_bars(h1)
    d1_k, _d1_d = stoch(d1_bars)

    pp_days, pp_vals = build_pp_lookup(m15)

    # H4 regime id = index of the h4 bar where HA colour last changed
    h4_bull_series = [b.c >= b.o for b in ha_h4]
    regime_id = [0] * len(h4)
    for i in range(1, len(h4)):
        regime_id[i] = i if h4_bull_series[i] != h4_bull_series[i - 1] else regime_id[i - 1]

    # D1_EXTREME: a regime is huntable only if, at the H4 bar where its colour
    # flipped, the last-closed D1 %K was already OS (bull regime) / OB (bear).
    d1_huntable = {}
    for r in set(regime_id):
        flip_end = h4[r].t + timedelta(hours=4)
        j = bisect.bisect_right(d1_ends, flip_end) - 1
        kk = d1_k[j] if j >= 0 else math.nan
        if math.isnan(kk):
            d1_huntable[r] = False
        elif h4_bull_series[r]:
            d1_huntable[r] = kk < D1_OS
        else:
            d1_huntable[r] = kk > D1_OB

    def aplus_stoch(h12_i, buy):
        if h12_i < 3:
            return False
        kk = k12
        if any(math.isnan(kk[j]) for j in range(h12_i - 3, h12_i + 1)):
            return False
        if buy:
            # local min with pre-turn value < 25, then turn up, within last 3 H12 bars
            return any(kk[j - 1] < APLUS_STOCH_LO and kk[j - 1] < kk[j - 2] and kk[j] > kk[j - 1]
                       for j in range(h12_i - 2, h12_i + 1))
        return any(kk[j - 1] > APLUS_STOCH_HI and kk[j - 1] > kk[j - 2] and kk[j] < kk[j - 1]
                   for j in range(h12_i - 2, h12_i + 1))

    def aplus_rsi(h12_i, buy):
        if h12_i < 3 or any(math.isnan(rsi12[j]) for j in range(h12_i - 3, h12_i + 1)):
            return False
        if buy:
            return any(rsi12[j - 1] < APLUS_RSI_LO and rsi12[j - 1] < rsi12[j - 2] and rsi12[j] > rsi12[j - 1]
                       for j in range(h12_i - 2, h12_i + 1))
        return any(rsi12[j - 1] > APLUS_RSI_HI and rsi12[j - 1] > rsi12[j - 2] and rsi12[j] < rsi12[j - 1]
                   for j in range(h12_i - 2, h12_i + 1))

    def hts_signal(i, h4i):
        return hts_ribbon(i, h4i, m15[i].c, lb_fu, lb_fl, lb_su, lb_sl,
                          hb_fu, hb_fl, hb_su, hb_sl, m15)

    open_tr = {v: None for v in VARIANTS}
    regime_fills = {v: {} for v in VARIANTS}      # variant -> {regime_id: count}
    trades = []

    def enter_cloud(v, dd, buy, bar, stop, one_r, h4i, h12i, rid):
        """Open a cloud-hold ticket for a non-CTRL variant (SDD or HTS entry)."""
        is_stoch = v in CLOUD_STOCH
        is_rsi = v in CLOUD_RSI
        ap_s, ap_r = aplus_stoch(h12i, buy), aplus_rsi(h12i, buy)
        if v.endswith("_HARD") and not (ap_r if is_rsi else ap_s):
            return
        osc = rsi4[h4i] if is_rsi else k4[h4i]
        osc = None if (osc is None or math.isnan(osc)) else osc
        tr = Trade(sym, v, dd, bar.t, bar.c, stop, one_r, osc, (ap_r if is_rsi else ap_s), rid)
        if is_stoch and osc is not None and (osc > OB if buy else osc < OS):
            tr.late = True
        if is_rsi and osc is not None and (osc > RSI_OB if buy else osc < RSI_OS):
            tr.late = True
        open_tr[v] = tr
        regime_fills[v][rid] = regime_fills[v].get(rid, 0) + 1

    for i in range(RMA_SLOW + 2, len(m15)):
        bar = m15[i]
        close_time = bar.t + timedelta(minutes=15)
        if close_time <= start:
            continue
        if bar.t >= end:
            break

        h1i = idx_asof(h1, close_time, 1)
        h4i = idx_asof(h4, close_time, 4)
        h12i = idx_asof(h12, close_time, 12)
        if h1i < RMA_SLOW or h4i < 2 or h12i < 1:
            continue
        if math.isnan(atr_h1[h1i]) or math.isnan(rma133_m15[i]) or math.isnan(rma133_h1[h1i]):
            continue

        # ---- manage open positions on THIS bar (stop-first) ----
        for v in VARIANTS:
            tr = open_tr[v]
            if tr is None:
                continue
            tr.bars += 1
            if v == "H4_HA_TRAIL":                      # ratchet 2.5xATR trail, never loosen
                cand = bar.c - TRAIL_ATR_MULT * atr_h1[h1i] * tr.dir
                tr.stop = max(tr.stop, cand) if tr.dir > 0 else min(tr.stop, cand)
            hit_stop = bar.l <= tr.stop if tr.dir > 0 else bar.h >= tr.stop
            exit_px = exit_reason = None
            if v == "H4_HA_TRAIL":
                if hit_stop:
                    exit_px, exit_reason = tr.stop, "trail_stop"
            elif v in FIXED_TP_M15:
                tp = tr.entry + FIXED_TP_M15[v] * tr.one_r * tr.dir
                hit_tp = bar.h >= tp if tr.dir > 0 else bar.l <= tp
                if hit_stop:
                    exit_px, exit_reason = tr.stop, "stop"
                elif hit_tp:
                    exit_px, exit_reason = tp, "tp"
            elif v == "CTRL_SDD":
                tp = tr.entry + TP_R * tr.one_r * tr.dir
                hit_tp = bar.h >= tp if tr.dir > 0 else bar.l <= tp
                if hit_stop:
                    exit_px, exit_reason = tr.stop, "stop"
                elif hit_tp:
                    exit_px, exit_reason = tp, "tp_2r"
            elif v == "H4_ST":                          # H1 Supertrend flips against, or stop
                st_against = st_h1[h1i] != tr.dir
                if hit_stop:
                    exit_px, exit_reason = tr.stop, "stop"
                elif st_against:
                    exit_px, exit_reason = bar.c, "h1_st_flip_against"
            elif v == "HTS_TRUE_TP":                    # fixed RR(2.0) TP1, or stop
                tp = tr.entry + TP_R * tr.one_r * tr.dir
                hit_tp = bar.h >= tp if tr.dir > 0 else bar.l <= tp
                if hit_stop:
                    exit_px, exit_reason = tr.stop, "stop"
                elif hit_tp:
                    exit_px, exit_reason = tp, "tp1_2r"
            elif v == "HTS_TRUE_TRAIL":                 # trail to fast-band far edge; hard exit body-beyond-slow
                if not (math.isnan(lb_fu[i]) or math.isnan(lb_fl[i])):
                    buf = HTS_STOPBUF * max(0.0, lb_fu[i] - lb_fl[i])
                    cand = (lb_fl[i] - buf) if tr.dir > 0 else (lb_fu[i] + buf)
                    tr.stop = max(tr.stop, cand) if tr.dir > 0 else min(tr.stop, cand)
                hit_stop = bar.l <= tr.stop if tr.dir > 0 else bar.h >= tr.stop
                beyond_slow = (bar.c < lb_sl[i]) if tr.dir > 0 else (bar.c > lb_su[i])
                if hit_stop:
                    exit_px, exit_reason = tr.stop, "trail_stop"
                elif not (math.isnan(lb_sl[i]) or math.isnan(lb_su[i])) and beyond_slow:
                    exit_px, exit_reason = bar.c, "body_beyond_slow"
            else:
                ha4_against = (ha_h4[h4i].c >= ha_h4[h4i].o) != (tr.dir > 0)
                if hit_stop:
                    exit_px, exit_reason = tr.stop, "stop"
                elif ha4_against:
                    exit_px, exit_reason = bar.c, "h4_ha_flip_against"
                elif not tr.late and v in CLOUD_STOCH:
                    kk, dd = k4[h4i], d4[h4i]
                    kprev, dprev = k4[h4i - 1], d4[h4i - 1]
                    if tr.dir > 0:
                        if kk > OB:
                            exit_px, exit_reason = bar.c, "h4_stoch_cloud_top"
                        elif (not math.isnan(kk) and not math.isnan(kprev)
                              and kprev >= dprev and kk < dd and kk > K_CROSS_GATE_HI):
                            exit_px, exit_reason = bar.c, "h4_stoch_k_cross_d"
                    else:
                        if kk < OS:
                            exit_px, exit_reason = bar.c, "h4_stoch_cloud_bot"
                        elif (not math.isnan(kk) and not math.isnan(kprev)
                              and kprev <= dprev and kk > dd and kk < K_CROSS_GATE_LO):
                            exit_px, exit_reason = bar.c, "h4_stoch_k_cross_d"
                elif not tr.late and v in CLOUD_RSI:
                    rr = rsi4[h4i]
                    if tr.dir > 0 and not math.isnan(rr) and rr > RSI_OB:
                        exit_px, exit_reason = bar.c, "h4_rsi_ob"
                    elif tr.dir < 0 and not math.isnan(rr) and rr < RSI_OS:
                        exit_px, exit_reason = bar.c, "h4_rsi_os"
            if exit_px is not None:
                tr.t_out, tr.exit, tr.reason = bar.t, exit_px, exit_reason
                tr.r = (exit_px - tr.entry) / tr.one_r * tr.dir
                trades.append(tr)
                open_tr[v] = None

        h4_bull = h4_bull_series[h4i]
        rid = regime_id[h4i]

        # ---- SDD-entry family (report's original stack) ----
        bull = ha_m15[i].c >= ha_m15[i].o
        prev_bull = ha_m15[i - 1].c >= ha_m15[i - 1].o
        flip = bull != prev_bull
        buy = bull
        d = 1 if buy else -1
        rma_with = stacked(bar.c, rma33_m15[i], rma133_m15[i], buy)
        h1b = h1[h1i]
        h1_with = ((ha_h1[h1i].c >= ha_h1[h1i].o) == buy
                   or stacked(h1b.c, rma33_h1[h1i], rma133_h1[h1i], buy))
        if sym in SKIP_PP:
            pp_ok = True
        else:
            pp = pp_asof(pp_days, pp_vals, close_time)
            pp_ok = pp is not None and (bar.c > pp if buy else bar.c < pp)

        if flip and rma_with and h1_with and pp_ok and (buy or not LONGS_ONLY):
            # 1R == stop distance == 2.5 x last-closed H1 Wilder ATR14
            one_r = STOP_ATR_MULT * atr_h1[h1i]
            stop = bar.c - one_r * d
            hunt_ok = (h4_bull == buy)
            for v in SDD_VARIANTS:
                if open_tr[v] is not None:
                    continue
                if v == "CTRL_SDD":
                    open_tr[v] = Trade(sym, v, d, bar.t, bar.c, stop, one_r,
                                       k4[h4i] if not math.isnan(k4[h4i]) else None, False, rid)
                    continue
                if not hunt_ok or regime_fills[v].get(rid, 0) >= CAP_PER_REGIME:
                    continue
                if "_D1" in v and not d1_huntable.get(rid, False):
                    continue
                if v in FIXED_TP_M15:                   # hunted SDD entry + fixed R:R TP
                    open_tr[v] = Trade(sym, v, d, bar.t, bar.c, stop, one_r, None, False, rid)
                    regime_fills[v][rid] = regime_fills[v].get(rid, 0) + 1
                    continue
                enter_cloud(v, d, buy, bar, stop, one_r, h4i, h12i, rid)

        # ---- H4_ST family (M15 entry, gated by H1 Supertrend instead of H4 HA hunt;
        #      no separate H1 WITH check — the Supertrend direction IS the H1 gate) ----
        if open_tr["H4_ST"] is None and flip and pp_ok and (buy or not LONGS_ONLY):
            rma_st = stacked(bar.c, rma33_m15[i], rma_st_m15[i], buy)
            st_bull = st_h1[h1i] == 1
            if rma_st and (st_bull == buy):
                rid_st = st_regime_id[h1i]
                if regime_fills["H4_ST"].get(rid_st, 0) < CAP_PER_REGIME:
                    one_r_st = STOP_ATR_MULT * atr_h1[h1i]
                    stop_st = bar.c - one_r_st * d
                    open_tr["H4_ST"] = Trade(sym, "H4_ST", d, bar.t, bar.c, stop_st, one_r_st,
                                             None, False, rid_st)
                    regime_fills["H4_ST"][rid_st] = regime_fills["H4_ST"].get(rid_st, 0) + 1

        # ---- HTS-entry family (H4 HA flip = hunt trigger, HTS ribbon = the M15 entry) ----
        sig = hts_signal(i, h4i)
        if sig is not None:
            hd, _hentry, hstop, hone_r = sig
            hbuy = hd > 0
            if h4_bull == hbuy and (hbuy or not LONGS_ONLY):   # entry dir must match the H4 HA regime
                for v in HTS_VARIANTS:
                    if open_tr[v] is not None or regime_fills[v].get(rid, 0) >= CAP_PER_REGIME:
                        continue
                    if "_D1" in v and not d1_huntable.get(rid, False):
                        continue
                    enter_cloud(v, hd, hbuy, bar, hstop, hone_r, h4i, h12i, rid)

            # ---- HTS_TRUE family: the real HtsEngine.evaluate() shape — no hunt-
            # regime gate at all (never existed in HtsEngine), one open ticket per
            # variant (no per-regime cap either — the live engine has none) ----
            if hbuy or not LONGS_ONLY:
                for v in HTS_TRUE_VARIANTS:
                    if open_tr[v] is None:
                        open_tr[v] = Trade(sym, v, hd, bar.t, bar.c, hstop, hone_r, None, False, 0)

    # window-end flatten
    if m15:
        last = m15[-1]
        for v in VARIANTS:
            tr = open_tr[v]
            if tr is None:
                continue
            tr.t_out, tr.exit, tr.reason = last.t, last.c, "window_end"
            tr.r = (last.c - tr.entry) / tr.one_r * tr.dir
            trades.append(tr)
    return trades


def run_h12h1(sym, h1r, start, end):
    """H12-hunt / H1-entry family. H12 HA colour change opens a hunt (cap 2/regime);
    entry on closed H1 (SDD stack or HTS ribbon); 1R = 2.5 x last-closed H4 ATR14.
    Exits compared: cloud-hold (H12 HA flip / stop) vs fixed 2R vs 2.5xATR trail."""
    h1 = to_bars(h1r)
    h4 = resample(h1, 4)
    h12 = resample(h1, 12)
    ha_h1, ha_h4, ha_h12 = heiken_ashi(h1), heiken_ashi(h4), heiken_ashi(h12)
    c_h1 = [b.c for b in h1]
    rf_h1, rs_h1 = wilder_rma(c_h1, RMA_FAST), wilder_rma(c_h1, RMA_SLOW)
    rf_h4 = wilder_rma([b.c for b in h4], RMA_FAST)
    atr_h4 = wilder_atr(h4, ATR_PERIOD)
    lb_fu, lb_fl = band_rma(h1, HTS_FAST)
    lb_su, lb_sl = band_rma(h1, HTS_SLOW)
    hb_fu, hb_fl = band_rma(h4, HTS_FAST)
    hb_su, hb_sl = band_rma(h4, HTS_SLOW)
    pp_days, pp_vals = build_pp_lookup(h1)

    b12 = [b.c >= b.o for b in ha_h12]
    reg = [0] * len(h12)
    for i in range(1, len(h12)):
        reg[i] = i if b12[i] != b12[i - 1] else reg[i - 1]

    open_tr = {v: None for v in H12H1_VARIANTS}
    fills = {v: {} for v in H12H1_VARIANTS}
    trades = []

    for i in range(RMA_SLOW + 2, len(h1)):
        bar = h1[i]
        ct = bar.t + timedelta(hours=1)
        if ct <= start:
            continue
        if bar.t >= end:
            break
        h4i = idx_asof(h4, ct, 4)
        h12i = idx_asof(h12, ct, 12)
        if h4i < RMA_FAST or h12i < 1 or math.isnan(atr_h4[h4i]) or math.isnan(rs_h1[i]):
            continue

        for v in list(open_tr):
            tr = open_tr[v]
            if tr is None:
                continue
            tr.bars += 1
            if v == "H12_SDD_TRAIL":
                cand = bar.c - TRAIL_ATR_MULT * atr_h4[h4i] * tr.dir
                tr.stop = max(tr.stop, cand) if tr.dir > 0 else min(tr.stop, cand)
            hit = bar.l <= tr.stop if tr.dir > 0 else bar.h >= tr.stop
            px = rsn = None
            if v in FIXED_TP_H1:
                tp = tr.entry + FIXED_TP_H1[v] * tr.one_r * tr.dir
                if hit:
                    px, rsn = tr.stop, "stop"
                elif bar.h >= tp if tr.dir > 0 else bar.l <= tp:
                    px, rsn = tp, "tp"
            elif v == "H12_SDD_TRAIL":
                if hit:
                    px, rsn = tr.stop, "trail_stop"
            else:                                          # cloud-hold on H12 HA
                against = (ha_h12[h12i].c >= ha_h12[h12i].o) != (tr.dir > 0)
                if hit:
                    px, rsn = tr.stop, "stop"
                elif against:
                    px, rsn = bar.c, "h12_ha_flip_against"
            if px is not None:
                tr.t_out, tr.exit, tr.reason = bar.t, px, rsn
                tr.r = (px - tr.entry) / tr.one_r * tr.dir
                trades.append(tr)
                open_tr[v] = None

        h12_bull = b12[h12i]
        rid = reg[h12i]
        one_r = STOP_ATR_MULT * atr_h4[h4i]

        bull = ha_h1[i].c >= ha_h1[i].o
        flip = bull != (ha_h1[i - 1].c >= ha_h1[i - 1].o)
        buy = bull
        d = 1 if buy else -1
        rma_with = stacked(bar.c, rf_h1[i], rs_h1[i], buy)
        h4_with = ((ha_h4[h4i].c >= ha_h4[h4i].o) == buy
                   or (bar.c > rf_h4[h4i] if buy else bar.c < rf_h4[h4i]))
        if sym in SKIP_PP:
            pp_ok = True
        else:
            pp = pp_asof(pp_days, pp_vals, ct)
            pp_ok = pp is not None and (bar.c > pp if buy else bar.c < pp)

        if (flip and rma_with and h4_with and pp_ok and h12_bull == buy
                and (buy or not LONGS_ONLY)):
            stop = bar.c - one_r * d
            for v in ("H12_SDD", "H12_SDD_2R", "H12_SDD_1R5", "H12_SDD_TRAIL"):
                if open_tr[v] is None and fills[v].get(rid, 0) < CAP_PER_REGIME:
                    open_tr[v] = Trade(sym, v, d, bar.t, bar.c, stop, one_r, None, False, rid)
                    fills[v][rid] = fills[v].get(rid, 0) + 1

        sig = hts_ribbon(i, h4i, bar.c, lb_fu, lb_fl, lb_su, lb_sl, hb_fu, hb_fl, hb_su, hb_sl, h1)
        if sig is not None:
            hd, _, hstop, hone = sig
            hbuy = hd > 0
            if h12_bull == hbuy and (hbuy or not LONGS_ONLY):
                v = "H12_HTS"
                if open_tr[v] is None and fills[v].get(rid, 0) < CAP_PER_REGIME:
                    open_tr[v] = Trade(sym, v, hd, bar.t, bar.c, hstop, hone, None, False, rid)
                    fills[v][rid] = fills[v].get(rid, 0) + 1

    if h1:
        last = h1[-1]
        for v, tr in open_tr.items():
            if tr is None:
                continue
            tr.t_out, tr.exit, tr.reason = last.t, last.c, "window_end"
            tr.r = (last.c - tr.entry) / tr.one_r * tr.dir
            trades.append(tr)
    return trades


def run_m5_st1(sym, m5r, h1r, start, end):
    """M5_ST1: M5 entry (HA-flip + M5 RMA33/ST_SLOW stack, HA1 shape), gated by
    the last-closed H1 Supertrend direction; stop = that Supertrend's own band
    level (up[] long / dn[] short) — a self-ratcheting trailing stop, not a
    fixed-ATR distance. Re-evaluated every M5 bar as h1i advances to a fresh
    closed H1 bar. Cap 2 fills / ST-regime."""
    m5 = to_bars(m5r)
    h1 = to_bars(h1r)
    ha_m5 = heiken_ashi(m5)
    close_m5 = [b.c for b in m5]
    rma_fast_m5 = wilder_rma(close_m5, RMA_FAST)
    rma_slow_m5 = wilder_rma(close_m5, ST_SLOW)
    st_h1, st_up, st_dn = supertrend_bands(h1, ST_ATR_LEN, ST_FACTOR)
    st_regime_id = [0] * len(h1)
    for i in range(1, len(h1)):
        st_regime_id[i] = i if st_h1[i] != st_h1[i - 1] else st_regime_id[i - 1]
    pp_days, pp_vals = build_pp_lookup(m5)

    v = "M5_ST1"
    open_tr = None
    fills = {}
    trades = []

    for i in range(ST_SLOW + 2, len(m5)):
        bar = m5[i]
        close_time = bar.t + timedelta(minutes=5)
        if close_time <= start:
            continue
        if bar.t >= end:
            break
        h1i = idx_asof(h1, close_time, 1)
        if h1i < ST_ATR_LEN or math.isnan(rma_slow_m5[i]) or math.isnan(st_up[h1i]) or math.isnan(st_dn[h1i]):
            continue

        if open_tr is not None:
            tr = open_tr
            tr.bars += 1
            tr.stop = st_up[h1i] if tr.dir > 0 else st_dn[h1i]   # self-ratcheting
            hit_stop = bar.l <= tr.stop if tr.dir > 0 else bar.h >= tr.stop
            st_against = st_h1[h1i] != tr.dir
            if hit_stop or st_against:
                px = tr.stop if hit_stop else bar.c
                rsn = "st_stop" if hit_stop else "st_flip_against"
                tr.t_out, tr.exit, tr.reason = bar.t, px, rsn
                tr.r = (px - tr.entry) / tr.one_r * tr.dir if tr.one_r > 0 else 0.0
                trades.append(tr)
                open_tr = None

        if open_tr is None:
            bull = ha_m5[i].c >= ha_m5[i].o
            flip = bull != (ha_m5[i - 1].c >= ha_m5[i - 1].o)
            buy = bull
            d = 1 if buy else -1
            rma_with = stacked(bar.c, rma_fast_m5[i], rma_slow_m5[i], buy)
            st_bull = st_h1[h1i] == 1
            if sym in SKIP_PP:
                pp_ok = True
            else:
                pp = pp_asof(pp_days, pp_vals, close_time)
                pp_ok = pp is not None and (bar.c > pp if buy else bar.c < pp)
            rid = st_regime_id[h1i]
            if (flip and rma_with and pp_ok and (st_bull == buy) and (buy or not LONGS_ONLY)
                    and fills.get(rid, 0) < CAP_PER_REGIME):
                stop = st_up[h1i] if buy else st_dn[h1i]
                one_r = abs(bar.c - stop)
                valid = one_r > 0 and ((buy and stop < bar.c) or (not buy and stop > bar.c))
                if valid:
                    open_tr = Trade(sym, v, d, bar.t, bar.c, stop, one_r, None, False, rid)
                    fills[rid] = fills.get(rid, 0) + 1

    if m5 and open_tr is not None:
        last = m5[-1]
        open_tr.t_out, open_tr.exit, open_tr.reason = last.t, last.c, "window_end"
        open_tr.r = (last.c - open_tr.entry) / open_tr.one_r * open_tr.dir if open_tr.one_r > 0 else 0.0
        trades.append(open_tr)
    return trades


def run_st_hts_generic(sym, ltf_bars_raw, ltf_minutes, htf_hours, start, end, variant,
                       tp1_mult=TP_R_1TO1, require_htf_struct=False, exit_band="ltf",
                       runner_mode="trail", pyramid=False):
    """Generic engine behind M45_ST_HTS (ltf=M5, htf=M45, htf_hours=0.75) and
    M15_ST_HTS (ltf=M15, htf=H1, htf_hours=1.0) — same "dream" config either
    way: HTF Supertrend is BOTH the direction filter and the stop-loss (its
    own band level, self-ratcheting, resampled from the LTF feed); entry is a
    fresh LTF close beyond the LTF's fast RMA-of-high/low band (HTS ribbon
    fast=33, matching real Band.java), in the Supertrend's direction; exit is
    a genuine half/half split — TP1 = tp1_mult x R on the first half, the
    other half trails (SL = the HTF Supertrend band) until a candle BODY
    closes beyond a slow band (the real HtsEngine runner rule, Supertrend
    swapped in for the structural fast-band stop). One Trade per signal,
    r = 0.5*(TP1 leg) + 0.5*(runner leg).

    exit_band="ltf" (default, the user's confirmed definition): runner's
    full-exit band is the LTF's own slow band (HTS_SLOW=144) — body closes on
    the OPPOSITE side (below slow-band-low for a long, above slow-band-high
    for a short). exit_band="htf": PR #137's locked-spec alternative — the
    HTF's own slow band (ST_SLOW=100) instead. require_htf_struct=True adds
    PR #137's structure gate (HTF stacked vs its own RMA33/100, OR LTF close
    already beyond the closed HTF fast band, in the ST direction).

    PYRAMIDING: does NOT require flat — a fresh signal opens a new ticket
    (its own TP1+runner pair) alongside any already-open ones in the same
    Supertrend regime, capped at CAP_PER_REGIME total per regime (matching
    the live engine's fill cap and PR #140's "Pyramiding for ha-hunt st" fix:
    canEnter no longer requires pos==0, it adds in the same direction up to
    capReg instead of forcing exit-then-reenter)."""
    ltf = to_bars(ltf_bars_raw)
    htf = resample(ltf, htf_hours)
    st_htf, st_up, st_dn = supertrend_bands(htf, ST_ATR_LEN, ST_FACTOR)
    st_regime_id = [0] * len(htf)
    for i in range(1, len(htf)):
        st_regime_id[i] = i if st_htf[i] != st_htf[i - 1] else st_regime_id[i - 1]
    fu, fl = band_rma(ltf, HTS_FAST)
    su, sl = band_rma(ltf, HTS_SLOW)
    # HTF structure (PR #137 gate) + HTF's own slow band (PR #137 runner exit)
    close_htf = [b.c for b in htf]
    rma_fast_htf = wilder_rma(close_htf, RMA_FAST)
    rma_slow_htf = wilder_rma(close_htf, ST_SLOW)
    fu_h, fl_h = band_rma(htf, HTS_FAST)
    su_h, sl_h = band_rma(htf, ST_SLOW)
    # runner_mode="be_haflip" (PR #140's locked v3 bakeoff): no trailing at all —
    # runner's stop stays at the ORIGINAL fixed SL until TP1 fires, then jumps
    # once to breakeven and sits there; full exit on a confirmed LTF Heikin-Ashi
    # colour flip against the position (HaHuntEngine.haFlipDirection style).
    ha_ltf = heiken_ashi(ltf) if runner_mode == "be_haflip" else None

    fills = {}
    trades = []
    open_tickets = []   # list of {leg_tp1, leg_run} dicts — pyramiding: >1 can be open at once

    def htf_idx(close_time):
        return idx_asof(htf, close_time, htf_hours)

    for i in range(HTS_SLOW + 2, len(ltf)):
        bar = ltf[i]
        close_time = bar.t + timedelta(minutes=ltf_minutes)
        if close_time <= start:
            continue
        if bar.t >= end:
            break
        hi = htf_idx(close_time)
        if hi < ST_ATR_LEN or math.isnan(st_up[hi]) or math.isnan(st_dn[hi]) or math.isnan(fu[i]) or math.isnan(su[i]):
            continue

        # ---- manage every open ticket's two legs ----
        still_open = []
        for tk in open_tickets:
            leg_tp1, leg_run = tk["tp1"], tk["run"]
            if leg_tp1 is not None and not leg_tp1.get("done"):
                d = leg_tp1["dir"]
                tp = leg_tp1["entry"] + tp1_mult * leg_tp1["one_r"] * d
                hit_tp = bar.h >= tp if d > 0 else bar.l <= tp
                hit_stop = bar.l <= leg_tp1["stop"] if d > 0 else bar.h >= leg_tp1["stop"]
                if hit_stop:
                    leg_tp1["r"], leg_tp1["done"] = -1.0, True
                elif hit_tp:
                    leg_tp1["r"], leg_tp1["done"] = tp1_mult, True
            if leg_run is not None and not leg_run.get("done") and runner_mode == "be_haflip":
                d = leg_run["dir"]
                if leg_tp1 is not None and leg_tp1.get("done") and not leg_run.get("moved_to_be"):
                    leg_run["stop"] = leg_run["entry"]   # jump to breakeven exactly once, then sit
                    leg_run["moved_to_be"] = True
                hit_stop = bar.l <= leg_run["stop"] if d > 0 else bar.h >= leg_run["stop"]
                bull = ha_ltf[i].c >= ha_ltf[i].o
                prev_bull = ha_ltf[i - 1].c >= ha_ltf[i - 1].o
                ha_flip_against = (bull != prev_bull) and (bull != (d > 0))
                if hit_stop:
                    px = leg_run["stop"]
                    leg_run["r"] = (px - leg_run["entry"]) / leg_run["one_r"] * d
                    leg_run["done"] = True
                elif ha_flip_against:
                    leg_run["r"] = (bar.c - leg_run["entry"]) / leg_run["one_r"] * d
                    leg_run["done"] = True
            elif leg_run is not None and not leg_run.get("done"):
                d = leg_run["dir"]
                leg_run["stop"] = st_up[hi] if d > 0 else st_dn[hi]   # ratchets with HTF ST
                hit_stop = bar.l <= leg_run["stop"] if d > 0 else bar.h >= leg_run["stop"]
                if exit_band == "htf":
                    # opposite-side close beyond the HTF's own slow band
                    beyond_slow = (bar.c < sl_h[hi]) if d > 0 else (bar.c > su_h[hi])
                    slow_ok = not (math.isnan(sl_h[hi]) or math.isnan(su_h[hi]))
                else:
                    # opposite-side close beyond the LTF's own slow band (confirmed definition)
                    beyond_slow = (bar.c < sl[i]) if d > 0 else (bar.c > su[i])
                    slow_ok = not (math.isnan(sl[i]) or math.isnan(su[i]))
                if hit_stop:
                    px = leg_run["stop"]
                    leg_run["r"] = (px - leg_run["entry"]) / leg_run["one_r"] * d
                    leg_run["done"] = True
                elif slow_ok and beyond_slow:
                    leg_run["r"] = (bar.c - leg_run["entry"]) / leg_run["one_r"] * d
                    leg_run["done"] = True

            if leg_tp1 is not None and leg_tp1.get("done") and leg_run is not None and leg_run.get("done"):
                r = 0.5 * leg_tp1["r"] + 0.5 * leg_run["r"]
                tr = Trade(sym, variant, leg_tp1["dir"], leg_tp1["t_in"], leg_tp1["entry"],
                           leg_tp1["stop"], leg_tp1["one_r"], None, False, leg_tp1["rid"])
                tr.t_out, tr.exit, tr.reason = bar.t, bar.c, "tp1+runner"
                tr.bars = i - leg_tp1["i_in"]
                tr.r = r
                trades.append(tr)
            else:
                still_open.append(tk)
        open_tickets = still_open

        # ---- entry: fresh LTF close beyond the fast band, direction == HTF ST.
        # pyramid=True: does not require flat, only fills.get(rid) < CAP_PER_REGIME
        # (backtested WORSE — PF 1.54/1.61 -> 1.02/1.10 on M5_ST_V3, MaxDD 13.5% ->
        # 55.9% — default False, kept only for A/B). pyramid=False: one ticket at a
        # time, same as before pyramiding was added. ----
        if pyramid or not open_tickets:
            beyond_up = bar.c > fu[i]
            beyond_dn = bar.c < fl[i]
            prev_up = (not math.isnan(fu[i - 1])) and ltf[i - 1].c > fu[i - 1]
            prev_dn = (not math.isnan(fl[i - 1])) and ltf[i - 1].c < fl[i - 1]
            fresh_up = beyond_up and not prev_up
            fresh_dn = beyond_dn and not prev_dn
            st_bull = st_htf[hi] == 1
            buy = st_bull
            trig = fresh_up if buy else fresh_dn
            if trig and require_htf_struct:
                hc = close_htf[hi]
                if buy:
                    struct_ok = ((not math.isnan(rma_fast_htf[hi]) and not math.isnan(rma_slow_htf[hi])
                                  and hc > rma_fast_htf[hi] > rma_slow_htf[hi])
                                 or (not math.isnan(fu_h[hi]) and bar.c > fu_h[hi]))
                else:
                    struct_ok = ((not math.isnan(rma_fast_htf[hi]) and not math.isnan(rma_slow_htf[hi])
                                  and hc < rma_fast_htf[hi] < rma_slow_htf[hi])
                                 or (not math.isnan(fl_h[hi]) and bar.c < fl_h[hi]))
                trig = trig and struct_ok
            if trig:
                rid = st_regime_id[hi]
                if fills.get(rid, 0) < CAP_PER_REGIME:
                    d = 1 if buy else -1
                    entry = bar.c
                    stop = st_up[hi] if buy else st_dn[hi]
                    one_r = abs(entry - stop)
                    valid = one_r > 0 and ((buy and stop < entry) or (not buy and stop > entry))
                    if valid:
                        base = dict(entry=entry, stop=stop, one_r=one_r, dir=d, t_in=bar.t, i_in=i, rid=rid)
                        open_tickets.append({"tp1": dict(base), "run": dict(base)})
                        fills[rid] = fills.get(rid, 0) + 1

    return trades


def run_m45_st_hts(sym, m5r, start, end, variant="M45_ST_HTS", tp1_mult=TP_R_1TO1,
                    require_m45_struct=False, exit_band="m5"):
    """M45 (filter+SL) / M5 (entry) — see run_st_hts_generic(). exit_band "m5"/"m45"
    map onto the generic "ltf"/"htf"."""
    return run_st_hts_generic(sym, m5r, 5, 0.75, start, end, variant, tp1_mult,
                              require_m45_struct, "ltf" if exit_band == "m5" else "htf")


def run_m15_st1_hts(sym, m15r, start, end, variant="M15_ST1_HTS", tp1_mult=TP_R_1TO1,
                     require_h1_struct=False, exit_band="m15"):
    """H1 (filter+SL) / M15 (entry) analogue of run_m45_st_hts — one notch up
    the timeframe ladder (HA4's own H4-hunt/M15-entry shape, Supertrend swapped
    in for the H4 HA hunt gate). exit_band "m15"/"h1" map onto "ltf"/"htf"."""
    return run_st_hts_generic(sym, m15r, 15, 1.0, start, end, variant, tp1_mult,
                              require_h1_struct, "ltf" if exit_band == "m15" else "htf")


def run_st_v3(sym, ltf_r, ltf_minutes, htf_hours, start, end, variant):
    """PR #140's locked "v3 bakeoff" config: TP1 1:2 half, stop to breakeven
    after TP1 (no trailing), full exit on a confirmed LTF Heikin-Ashi flip
    against the position. M5_ST_V3 = M5 entry / M45 ST (htf_hours=0.75);
    M15_ST_V3 = M15 entry / H1 ST (htf_hours=1.0). Structure gate ON, matching
    reqM45Struct=true in both Pine files."""
    return run_st_hts_generic(sym, ltf_r, ltf_minutes, htf_hours, start, end, variant,
                              tp1_mult=2.0, require_htf_struct=True, runner_mode="be_haflip")


# ---------------------------------------------------------------------------
# stats
# ---------------------------------------------------------------------------
def stats(trades):
    n = len(trades)
    if n == 0:
        return dict(n=0, wr=0, total_r=0, avg_r=0, pln=0, pct=0, maxdd=0, pf=0, avg_bars=0, aplus=0)
    wins = [t for t in trades if t.r > 0]
    total_r = sum(t.r for t in trades)
    gross_w = sum(t.r for t in wins)
    gross_l = -sum(t.r for t in trades if t.r <= 0)
    eq = 0.0
    peak = 0.0
    maxdd = 0.0
    for t in sorted(trades, key=lambda x: x.t_in):
        eq += t.r * RISK_PLN
        peak = max(peak, eq)
        maxdd = max(maxdd, peak - eq)
    return dict(
        n=n,
        wr=100.0 * len(wins) / n,
        total_r=total_r,
        avg_r=total_r / n,
        pln=total_r * RISK_PLN,
        pct=total_r * RISK_PLN / 1000.0 * 100.0,
        maxdd=maxdd / 1000.0 * 100.0,
        pf=(gross_w / gross_l) if gross_l > 0 else math.inf,
        avg_bars=sum(t.bars for t in trades) / n,
        aplus=100.0 * sum(1 for t in trades if t.aplus) / n,
    )


def fmt_row(name, s):
    pf = "inf" if s["pf"] == math.inf else f"{s['pf']:.2f}"
    return (f"| {name} | {s['n']} | {s['wr']:.1f} | {s['total_r']:+.2f} | {s['avg_r']:.3f} | "
            f"{s['pln']:+.2f} | {s['pct']:+.2f}% | {s['maxdd']:.1f} | {pf} | {s['avg_bars']:.1f} | "
            f"{s['aplus']:.0f}% |")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:  # noqa: BLE001
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="2025-09-01")
    ap.add_argument("--end", default="2026-09-01")
    ap.add_argument("--no-btc", action="store_true")
    ap.add_argument("--longs-only", action="store_true")
    ap.add_argument("--symbols", default="", help="comma list, e.g. XAU,US100 (default: whole book)")
    ap.add_argument("--warmup-days", type=int, default=120)
    ap.add_argument("--m5", action="store_true", help="also fetch M5 + run M5_ST1 (H1 Supertrend filter+SL)")
    ap.add_argument("--out-prefix", default="tools/out/h4_ha_cloud")
    ap.add_argument("--st-atr", type=int, default=None, help="Supertrend ATR period override (default 10)")
    ap.add_argument("--st-factor", type=float, default=None, help="Supertrend factor override (default 2.0)")
    args = ap.parse_args()

    global LONGS_ONLY
    LONGS_ONLY = args.longs_only
    global ST_ATR_LEN, ST_FACTOR
    if args.st_atr is not None:
        ST_ATR_LEN = args.st_atr
    if args.st_factor is not None:
        ST_FACTOR = args.st_factor

    start = datetime.fromisoformat(args.start).replace(tzinfo=timezone.utc)
    end = datetime.fromisoformat(args.end).replace(tzinfo=timezone.utc)
    fetch_from = start - timedelta(days=args.warmup_days)

    if args.symbols.strip():
        want = {s.strip().upper() for s in args.symbols.split(",")}
        syms = [s for s in EPICS if s in want]
    else:
        syms = [s for s in EPICS if not (args.no_btc and s == "BTCUSD")]
    cap = Capital(demo=True)
    cap.login()

    all_trades = []
    per_sym = {}
    coverage = {}
    for sym in syms:
        epic = EPICS[sym]
        sys.stderr.write(f"[{sym}] fetching...\n")
        m15 = cap.candles(epic, "MINUTE_15", fetch_from, end)
        h1 = cap.candles(epic, "HOUR", fetch_from, end)
        coverage[sym] = (len(m15), len(h1),
                         m15[0]["time"].isoformat() if m15 else None,
                         m15[-1]["time"].isoformat() if m15 else None)
        tr = run_symbol(sym, m15, h1, start, end) + run_h12h1(sym, h1, start, end)
        tr += run_m15_st1_hts(sym, m15, start, end)
        tr += run_m15_st1_hts(sym, m15, start, end, variant="M15_ST1_HTS_V2", tp1_mult=2.0,
                              require_h1_struct=True, exit_band="h1")
        tr += run_st_v3(sym, m15, 15, 1.0, start, end, "M15_ST_V3")
        if args.m5:
            # M5_ST1 needs far less history than the M15/H1 warmup above (M5 = 12x
            # the bar rate) — a separate, shorter fetch avoids months of unneeded M5.
            m5_from = start - timedelta(days=min(args.warmup_days, 20))
            m5 = cap.candles(epic, "MINUTE_5", m5_from, end)
            tr += run_m5_st1(sym, m5, h1, start, end)
            tr += run_m45_st_hts(sym, m5, start, end)
            tr += run_m45_st_hts(sym, m5, start, end, variant="M45_ST_HTS_V2", tp1_mult=2.0,
                                 require_m45_struct=True, exit_band="m45")
            tr += run_st_v3(sym, m5, 5, 0.75, start, end, "M5_ST_V3")
        per_sym[sym] = tr
        all_trades += tr
        sys.stderr.write(f"[{sym}] {len(tr)} trades\n")

    Path(args.out_prefix).parent.mkdir(parents=True, exist_ok=True)

    # trades csv (equity_simulator-compatible)
    csv_path = f"{args.out_prefix}_trades.csv"
    with open(csv_path, "w", encoding="utf-8") as fh:
        fh.write("entry_time,exit_time,symbol,variant,direction,result,r_multiple,"
                 "entry,stop,exit,exit_reason,bars,entry_osc,aplus,late\n")
        for t in sorted(all_trades, key=lambda x: (x.variant, x.t_in)):
            res = "WIN" if t.r > 0 else ("BE" if t.r == 0 else "LOSS")
            fh.write(f"{t.t_in.isoformat()},{t.t_out.isoformat()},{t.sym},{t.variant},"
                     f"{'LONG' if t.dir > 0 else 'SHORT'},{res},{t.r:.4f},{t.entry:.5f},"
                     f"{t.stop:.5f},{t.exit:.5f},{t.reason},{t.bars},"
                     f"{'' if t.entry_osc is None else f'{t.entry_osc:.1f}'},{int(t.aplus)},{int(t.late)}\n")

    # report
    lines = []
    lines.append(f"# H4 HA hunt + oscillator cloud hold — Capital demo rebuild")
    lines.append("")
    lines.append(f"Window **{args.start} → {args.end}** (warmup {args.warmup_days}d). "
                 f"Generated {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC.")
    lines.append(f"Data: Capital.com **demo mids** via `tools/capital_data.py` "
                 f"({'no BTC' if args.no_btc else 'SDD book'}). Spread/commission ignored. No orders. Stop-first.")
    lines.append("")
    lines.append("## Coverage")
    lines.append("")
    for sym, (nm, nh, a, b) in coverage.items():
        lines.append(f"- **{sym}** ({EPICS[sym]}): M15 {nm} bars, H1 {nh} bars, {a} .. {b}")
    lines.append("")
    lines.append("## TOTAL (book summed)")
    lines.append("")
    lines.append("| Variant | n | WR% | Total R | Avg R | PLN | % of 1000 | MaxDD% | PF | Avg bars | A+ % |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    summary = {"window": [args.start, args.end], "no_btc": args.no_btc, "variants": {}}
    for v in VARIANTS:
        s = stats([t for t in all_trades if t.variant == v])
        lines.append(fmt_row(v, s))
        summary["variants"][v] = s | {"pf": (None if s["pf"] == math.inf else s["pf"])}
    lines.append("")
    for v in VARIANTS:
        vt = [t for t in all_trades if t.variant == v]
        reasons = {}
        for t in vt:
            reasons[t.reason] = reasons.get(t.reason, 0) + 1
        lines.append(f"- `{v}` exits: " + ", ".join(f"{k} {n}" for k, n in sorted(reasons.items(), key=lambda x: -x[1])))
    lines.append("")
    for v in VARIANTS:
        lines.append(f"## Per-symbol — {v}")
        lines.append("")
        lines.append("| Symbol | n | WR% | Total R | Avg R | PLN | % of 1000 | MaxDD% | PF | Avg bars | A+ % |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for sym in syms:
            s = stats([t for t in per_sym[sym] if t.variant == v])
            lines.append(fmt_row(sym, s))
        lines.append("")

    md_path = f"{args.out_prefix}_report.md"
    Path(md_path).write_text("\n".join(lines), encoding="utf-8")
    Path(f"{args.out_prefix}_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    print("\n".join(lines))
    sys.stderr.write(f"\nwrote {md_path}\n      {csv_path}\n      {args.out_prefix}_summary.json\n")


if __name__ == "__main__":
    main()
