"""Research simulator approximating SATS v1.13.1 Default on the chart TF.

Entry = SuperTrend flip only (price break or window-based character-flip).
Score is computed for logging but is **not** an entry filter.

Simplifications vs the published Pine (WillyAlgoTrader SATS v1.13.1) are
listed in ``SIMPLIFICATIONS`` and in ``docs/sats-vs-st-v3-12m.md``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

import numpy as np

from .indicators import sats_adaptive_supertrend, symmetric_pivots
from .ohlc import Bars
from .simulator import Book, Trade, _metrics

SIMPLIFICATIONS = [
    "No official Pine in-repo; engine reconstructed from SATS TV docs (through v1.12.0) + ProRealTime port. v1.13.1 patch notes were not independently published.",
    "Chart TF = entry TF only (M15 or M5). No MTF request.security, no Auto-preset ATR/ER/RSI remap per TF — Default is ATR14 / baseMult 2.0 on both.",
    "Band source is hl2 (TV SuperTrend). The PRT port uses close.",
    "No volume series in HistData/Dukascopy/Coinbase loaders used here — TQI volatility factor always uses ATR/ATR-baseline mapped [0.6, 1.8]→[0,1].",
    "Character-flip is window-based (v1.12 note): max TQI over the prior charFlipWindow=5 bars > 0.55 AND current TQI < 0.25 AND trendAge ≥ 5. Old 1-bar collapse almost never fired.",
    "Max SL distance defaulted to 4.0× ATR (v1.12 added the cap; Pine default not in public notes). Pivot SL = further of (pivot ± 1.5 ATR, close ± 1.5 ATR), then clipped to ≤ 4 ATR from entry.",
    "Fixed TP 1/2/3R thirds. Dynamic TP, Auto-calibration, dashboard/regime grid, and score-graded alerts are off / not used as filters.",
    "Timeout remaining is marked to the close (v1.12 honest P&L). Flip-exit closes remaining at close then opens the new flip. Same-bar SL+TP: SL wins, new TP tags on that bar are ignored.",
    "Pivots are symmetric 2*3+1 (confirmed 3 bars later). No Pine pivot lookahead.",
    "One ticket at a time. No spread/commission. Stop-first. Signals only after warmup (ATR baseline 100 + 10).",
]


@dataclass(frozen=True)
class SatsParams:
    name: str = "SATS_Default"
    atr_len: int = 14
    base_mult: float = 2.0
    use_tqi: bool = True
    quality_strength: float = 0.4
    quality_curve: float = 1.5
    use_adaptive: bool = True
    adapt_strength: float = 0.5
    use_asym: bool = True
    asym_strength: float = 0.5
    use_eff_atr: bool = True
    use_char_flip: bool = True
    char_flip_min_age: int = 5
    char_flip_high: float = 0.55
    char_flip_low: float = 0.25
    char_flip_window: int = 5
    sl_atr_mult: float = 1.5
    max_sl_atr: float = 4.0
    tp1_r: float = 1.0
    tp2_r: float = 2.0
    tp3_r: float = 3.0
    timeout_bars: int = 100
    pivot_len: int = 3
    warmup_extra: int = 10
    trade_start: Optional[str] = None
    trade_end: Optional[str] = None
    chart_minutes: int = 15


def default_sats(chart_minutes: int, trade_start: Optional[str] = None, trade_end: Optional[str] = None) -> SatsParams:
    return SatsParams(
        name=f"SATS_Default_M{chart_minutes}",
        chart_minutes=chart_minutes,
        trade_start=trade_start,
        trade_end=trade_end,
    )


def _stop_long(entry: float, pivot_low: float, bar_low: float, atr_i: float, p: SatsParams) -> float:
    sl_base = pivot_low if not np.isnan(pivot_low) and pivot_low > 0 else bar_low
    raw = sl_base - p.sl_atr_mult * atr_i
    floor = entry - p.sl_atr_mult * atr_i
    sl = min(raw, floor)  # further from entry
    cap = entry - p.max_sl_atr * atr_i
    sl = max(sl, cap)  # not further than max ATR
    return sl


def _stop_short(entry: float, pivot_high: float, bar_high: float, atr_i: float, p: SatsParams) -> float:
    sl_base = pivot_high if not np.isnan(pivot_high) and pivot_high > 0 else bar_high
    raw = sl_base + p.sl_atr_mult * atr_i
    ceil = entry + p.sl_atr_mult * atr_i
    sl = max(raw, ceil)
    cap = entry + p.max_sl_atr * atr_i
    sl = min(sl, cap)
    return sl


def _realized_r(
    *,
    hit1: bool,
    hit2: bool,
    hit3: bool,
    sl_hit: bool,
    timeout: bool,
    flip: bool,
    tp1_r: float,
    tp2_r: float,
    tp3_r: float,
    exit_r: float,
) -> tuple[float, str]:
    """1/3 size per TP. SL first. Timeout/flip mark remaining at actual R."""
    third = 1.0 / 3.0
    if sl_hit:
        taken = 0.0
        rem = 1.0
        if hit1:
            taken += third * tp1_r
            rem -= third
        if hit2:
            taken += third * tp2_r
            rem -= third
        return taken + rem * (-1.0), "stop"
    if hit3:
        return (tp1_r + tp2_r + tp3_r) / 3.0, "tp3"
    taken = 0.0
    rem = 1.0
    if hit1:
        taken += third * tp1_r
        rem -= third
    if hit2:
        taken += third * tp2_r
        rem -= third
    r = taken + rem * exit_r
    if flip:
        return r, "flip_exit"
    if timeout:
        return r, "timeout"
    return r, "open_eod"


def simulate_sats(symbol: str, bars: Bars, p: SatsParams) -> Book:
    o, h, l, c = bars.open, bars.high, bars.low, bars.close
    n = len(c)
    if n < 120:
        return _metrics([], symbol, p.name)

    eng = sats_adaptive_supertrend(
        h,
        l,
        c,
        atr_len=p.atr_len,
        base_mult=p.base_mult,
        use_tqi=p.use_tqi,
        quality_strength=p.quality_strength,
        quality_curve=p.quality_curve,
        use_adaptive=p.use_adaptive,
        adapt_strength=p.adapt_strength,
        use_asym=p.use_asym,
        asym_strength=p.asym_strength,
        use_eff_atr=p.use_eff_atr,
        use_char_flip=p.use_char_flip,
        char_flip_min_age=p.char_flip_min_age,
        char_flip_high=p.char_flip_high,
        char_flip_low=p.char_flip_low,
        char_flip_window=p.char_flip_window,
    )
    ph, pl = symmetric_pivots(h, l, p.pivot_len)
    atr_i = eng["atr"]
    flip_up = eng["flip_up"]
    flip_dn = eng["flip_dn"]
    char_flip = eng["char_flip"]
    tqi = eng["tqi"]

    warmup = max(50, p.atr_len, 20, 100, p.pivot_len * 2 + 1) + p.warmup_extra
    win_lo = np.datetime64(p.trade_start) if p.trade_start else None
    win_hi = np.datetime64(p.trade_end) if p.trade_end else None
    iso = bars.time.astype("datetime64[s]")

    trades: list[Trade] = []
    pos = 0
    ent = sl = risk = np.nan
    tp1 = tp2 = tp3 = np.nan
    hit1 = hit2 = hit3 = False
    ent_i = 0
    entry_tqi = 0.0
    entry_cf = False

    def close_now(i: int, exit_px: float, sl_hit: bool, timeout: bool, flip: bool) -> None:
        nonlocal pos, hit1, hit2, hit3
        sign = 1.0 if pos == 1 else -1.0
        exit_r = ((exit_px - ent) / risk) * sign if risk > 0 else 0.0
        r, reason = _realized_r(
            hit1=hit1,
            hit2=hit2,
            hit3=hit3,
            sl_hit=sl_hit,
            timeout=timeout,
            flip=flip,
            tp1_r=p.tp1_r,
            tp2_r=p.tp2_r,
            tp3_r=p.tp3_r,
            exit_r=exit_r,
        )
        if sl_hit:
            exit_px = float(sl)
        trades.append(
            Trade(
                symbol=symbol,
                direction="long" if pos == 1 else "short",
                entry_time=str(iso[ent_i]),
                exit_time=str(iso[i]),
                entry=float(ent),
                stop0=float(sl),
                exit=float(exit_px),
                r=float(r),
                r_split=float(r),
                r_full=float(exit_r),
                tp1_hit=bool(hit1),
                reason=("char_flip_" + reason) if entry_cf and reason == "flip_exit" else reason,
                variant=p.name,
            )
        )
        pos = 0
        hit1 = hit2 = hit3 = False

    def try_open(i: int, is_long: bool) -> None:
        nonlocal pos, ent, sl, risk, tp1, tp2, tp3, hit1, hit2, hit3, ent_i, entry_tqi, entry_cf
        ai = atr_i[i]
        if np.isnan(ai) or ai <= 0:
            return
        entry = float(c[i])
        if is_long:
            stop = _stop_long(entry, float(pl[i]) if not np.isnan(pl[i]) else np.nan, float(l[i]), ai, p)
            if stop >= entry:
                return
        else:
            stop = _stop_short(entry, float(ph[i]) if not np.isnan(ph[i]) else np.nan, float(h[i]), ai, p)
            if stop <= entry:
                return
        r_dist = abs(entry - stop)
        if r_dist <= 0:
            return
        pos = 1 if is_long else -1
        ent = entry
        sl = float(stop)
        risk = r_dist
        tp1 = entry + p.tp1_r * r_dist * pos
        tp2 = entry + p.tp2_r * r_dist * pos
        tp3 = entry + p.tp3_r * r_dist * pos
        hit1 = hit2 = hit3 = False
        ent_i = i
        entry_tqi = float(tqi[i])
        entry_cf = bool(char_flip[i])

    for i in range(n):
        in_window = True
        if win_lo is not None:
            in_window = in_window and iso[i] >= win_lo
        if win_hi is not None:
            in_window = in_window and iso[i] < win_hi
        ready = i >= warmup and in_window and not np.isnan(atr_i[i])

        if pos != 0 and i > ent_i:
            d = pos
            sl_hit = (l[i] <= sl) if d > 0 else (h[i] >= sl)
            reach1 = (h[i] >= tp1) if d > 0 else (l[i] <= tp1)
            reach2 = (h[i] >= tp2) if d > 0 else (l[i] <= tp2)
            reach3 = (h[i] >= tp3) if d > 0 else (l[i] <= tp3)
            age = i - ent_i
            timeout = age >= p.timeout_bars
            new_flip = (d > 0 and bool(flip_dn[i])) or (d < 0 and bool(flip_up[i]))
            if sl_hit:
                # same-bar TP tags on the SL bar do not count
                close_now(i, float(sl), sl_hit=True, timeout=False, flip=False)
            else:
                if reach1:
                    hit1 = True
                if reach2:
                    hit2 = True
                if reach3:
                    hit3 = True
                if hit3:
                    px = float(tp3)
                    close_now(i, px, sl_hit=False, timeout=False, flip=False)
                elif new_flip:
                    close_now(i, float(c[i]), sl_hit=False, timeout=False, flip=True)
                elif timeout:
                    close_now(i, float(c[i]), sl_hit=False, timeout=True, flip=False)

        if pos == 0 and ready:
            if bool(flip_up[i]):
                try_open(i, True)
            elif bool(flip_dn[i]):
                try_open(i, False)

    if pos != 0:
        close_now(n - 1, float(c[-1]), sl_hit=False, timeout=False, flip=False)

    return _metrics(trades, symbol, p.name)
