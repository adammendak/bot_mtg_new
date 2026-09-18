"""Wilder RMA / ATR and TradingView-style Supertrend.

Matches ``server/src/main/java/com/adam/server/sdd/Wilder.java`` and
``Supertrend.java``. TV ``ta.supertrend`` direction is the negated Java trend
(``-1`` bull / price above the line, ``+1`` bear) — same as the Pine overlay.
"""

from __future__ import annotations

import numpy as np


def rma(source: np.ndarray, period: int) -> np.ndarray:
    """Wilder RMA: first value is SMA, then ``(prev * (n-1) + x) / n``."""
    n = len(source)
    out = np.full(n, np.nan, dtype=np.float64)
    if period <= 0 or n < period:
        return out
    src = source.astype(np.float64, copy=False)
    acc = float(np.sum(src[:period]))
    out[period - 1] = acc / period
    prev = out[period - 1]
    k = period - 1
    for i in range(period, n):
        prev = (prev * k + src[i]) / period
        out[i] = prev
    return out


def true_range(high: np.ndarray, low: np.ndarray, close: np.ndarray) -> np.ndarray:
    n = len(close)
    tr = np.empty(n, dtype=np.float64)
    tr[0] = high[0] - low[0]
    if n == 1:
        return tr
    prev_c = close[:-1]
    tr[1:] = np.maximum(high[1:] - low[1:], np.maximum(np.abs(high[1:] - prev_c), np.abs(low[1:] - prev_c)))
    return tr


def atr(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int) -> np.ndarray:
    return rma(true_range(high, low, close), period)


def rma_band(high: np.ndarray, low: np.ndarray, period: int) -> tuple[np.ndarray, np.ndarray]:
    """High/low RMA ribbon; upper = max(rmaH, rmaL), lower = min(...)."""
    rh = rma(high, period)
    rl = rma(low, period)
    upper = np.maximum(rh, rl)
    lower = np.minimum(rh, rl)
    return upper, lower


def supertrend(
    high: np.ndarray,
    low: np.ndarray,
    close: np.ndarray,
    factor: float,
    atr_period: int,
) -> tuple[np.ndarray, np.ndarray]:
    """Classic Supertrend.

    Returns ``(line, tv_dir)`` where ``tv_dir == -1`` is bull (TV / Pine) and
    ``+1`` is bear. Band math matches ``Supertrend.java``; TV sign is ``-trend``.
    """
    n = len(close)
    line = np.full(n, np.nan, dtype=np.float64)
    tv_dir = np.full(n, np.nan, dtype=np.float64)
    a = atr(high, low, close, atr_period)

    prev_final_up = np.nan
    prev_final_dn = np.nan
    prev_trend = 0  # Java: +1 up, -1 down
    prev_close = np.nan

    for i in range(n):
        ai = a[i]
        if np.isnan(ai):
            prev_close = close[i]
            continue
        src = (high[i] + low[i]) * 0.5
        up = src - factor * ai
        dn = src + factor * ai
        if np.isnan(prev_final_up):
            final_up = up
            final_dn = dn
        else:
            final_up = max(up, prev_final_up) if prev_close > prev_final_up else up
            final_dn = min(dn, prev_final_dn) if prev_close < prev_final_dn else dn

        if prev_trend == 0:
            trend = 1
        elif prev_trend == -1 and close[i] > prev_final_dn:
            trend = 1
        elif prev_trend == 1 and close[i] < prev_final_up:
            trend = -1
        else:
            trend = prev_trend

        line[i] = final_up if trend == 1 else final_dn
        tv_dir[i] = -float(trend)  # TV: -1 bull, +1 bear

        prev_final_up = final_up
        prev_final_dn = final_dn
        prev_trend = trend
        prev_close = close[i]

    return line, tv_dir


def heikin_ashi(
    open_: np.ndarray,
    high: np.ndarray,
    low: np.ndarray,
    close: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Pine HA-Hunt entry-TF Heikin-Ashi (confirmed closed bars).

    ``haClose = (o+h+l+c)/4``
    ``haOpen  = (o+c)/2`` on bar 0, else ``(haOpen[1] + haClose[1]) / 2``
    Colour: bull when ``haClose >= haOpen``.
    """
    o = open_.astype(np.float64, copy=False)
    h = high.astype(np.float64, copy=False)
    l = low.astype(np.float64, copy=False)
    c = close.astype(np.float64, copy=False)
    n = len(c)
    ha_c = (o + h + l + c) / 4.0
    ha_o = np.empty(n, dtype=np.float64)
    if n == 0:
        return ha_o, ha_c, np.array([], dtype=bool)
    ha_o[0] = (o[0] + c[0]) / 2.0
    for i in range(1, n):
        ha_o[i] = (ha_o[i - 1] + ha_c[i - 1]) / 2.0
    ha_bull = ha_c >= ha_o
    return ha_o, ha_c, ha_bull


# ---------------------------------------------------------------------------
# SATS (Self-Aware Trend System) research port
# ---------------------------------------------------------------------------
# Approximates WillyAlgoTrader SATS v1.13.1 Default from the published TV
# description + the ProRealTime port. See tools/ha_hunt_st_compare/sats.py
# for documented simplifications vs the closed Pine.


def efficiency_ratio(close: np.ndarray, length: int) -> np.ndarray:
    """Kaufman ER = |close - close[N]| / sum(|close - close[1]|) over ``length``."""
    n = len(close)
    out = np.zeros(n, dtype=np.float64)
    if length <= 0 or n < length + 1:
        return out
    c = close.astype(np.float64, copy=False)
    moves = np.abs(np.diff(c, prepend=c[0]))
    cum = np.cumsum(moves)
    for i in range(length, n):
        vol = cum[i] - cum[i - length]
        if vol > 0:
            out[i] = abs(c[i] - c[i - length]) / vol
    return np.clip(out, 0.0, 1.0)


def rolling_extremes(src: np.ndarray, length: int) -> tuple[np.ndarray, np.ndarray]:
    """Inclusive rolling max / min of the last ``length`` bars."""
    n = len(src)
    mx = np.full(n, np.nan, dtype=np.float64)
    mn = np.full(n, np.nan, dtype=np.float64)
    if length <= 0 or n < length:
        return mx, mn
    x = src.astype(np.float64, copy=False)
    for i in range(length - 1, n):
        w = x[i - length + 1 : i + 1]
        mx[i] = float(np.max(w))
        mn[i] = float(np.min(w))
    return mx, mn


def tqi_series(
    high: np.ndarray,
    low: np.ndarray,
    close: np.ndarray,
    atr_arr: np.ndarray,
    *,
    er_len: int = 20,
    struct_len: int = 20,
    mom_len: int = 10,
    atr_baseline_len: int = 100,
    w_er: float = 0.35,
    w_vol: float = 0.20,
    w_struct: float = 0.25,
    w_mom: float = 0.20,
    volume: np.ndarray | None = None,
    vol_len: int = 20,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """4-factor Trend Quality Index in [0, 1].

    Returns ``(tqi, er, vol_ratio)``. Volume Z-score is used when ``volume``
    has non-zero bars; otherwise volatility is ATR / ATR-baseline mapped
    from [0.6, 1.8] → [0, 1] (SATS volume-less fallback).
    """
    n = len(close)
    tqi = np.full(n, 0.5, dtype=np.float64)
    er = efficiency_ratio(close, er_len)
    c = close.astype(np.float64, copy=False)
    h = high.astype(np.float64, copy=False)
    l = low.astype(np.float64, copy=False)

    atr_base = rma(atr_arr, atr_baseline_len)
    vol_ratio = np.ones(n, dtype=np.float64)
    for i in range(n):
        b = atr_base[i]
        a = atr_arr[i]
        if not np.isnan(b) and b > 0 and not np.isnan(a):
            vol_ratio[i] = a / b

    has_vol = volume is not None and np.any(np.asarray(volume) > 0)
    if has_vol:
        v = volume.astype(np.float64, copy=False)
        v_mean = np.full(n, np.nan)
        v_std = np.full(n, np.nan)
        for i in range(vol_len - 1, n):
            w = v[i - vol_len + 1 : i + 1]
            v_mean[i] = float(np.mean(w))
            v_std[i] = float(np.std(w, ddof=0))
        tqi_vol = np.zeros(n, dtype=np.float64)
        for i in range(n):
            if np.isnan(v_std[i]) or v_std[i] <= 0:
                tqi_vol[i] = 0.5
            else:
                z = (v[i] - v_mean[i]) / v_std[i]
                tqi_vol[i] = float(np.clip((z - (-1.0)) / 3.0, 0.0, 1.0))
    else:
        tqi_vol = np.clip((vol_ratio - 0.6) / 1.2, 0.0, 1.0)

    s_hi, s_lo = rolling_extremes(h, struct_len)
    s_range = s_hi - s_lo
    price_pos = np.full(n, 0.5, dtype=np.float64)
    ok = s_range > 0
    price_pos[ok] = (c[ok] - s_lo[ok]) / s_range[ok]
    tqi_struct = np.clip(np.abs(price_pos - 0.5) * 2.0, 0.0, 1.0)

    tqi_mom = np.zeros(n, dtype=np.float64)
    if mom_len > 0 and n > mom_len:
        for i in range(mom_len, n):
            win = c[i] - c[i - mom_len]
            if win == 0:
                continue
            aligned = 0
            for k in range(mom_len):
                bar = c[i - k] - c[i - k - 1]
                if (win > 0 and bar > 0) or (win < 0 and bar < 0):
                    aligned += 1
            tqi_mom[i] = aligned / mom_len

    w_sum = w_er + w_vol + w_struct + w_mom
    if w_sum <= 0:
        w_sum = 1.0
    tqi_er = np.clip(er, 0.0, 1.0)
    tqi[:] = (tqi_er * w_er + tqi_vol * w_vol + tqi_struct * w_struct + tqi_mom * w_mom) / w_sum
    np.clip(tqi, 0.0, 1.0, out=tqi)
    return tqi, er, vol_ratio


def symmetric_pivots(
    high: np.ndarray,
    low: np.ndarray,
    pivot_len: int = 3,
) -> tuple[np.ndarray, np.ndarray]:
    """Last confirmed symmetric pivot high/low (window 2*len+1), carried forward."""
    n = len(high)
    last_ph = np.full(n, np.nan, dtype=np.float64)
    last_pl = np.full(n, np.nan, dtype=np.float64)
    span = 2 * pivot_len + 1
    if n < span:
        return last_ph, last_pl
    ph = np.nan
    pl = np.nan
    for i in range(span - 1, n):
        centre = i - pivot_len
        w_h = high[i - span + 1 : i + 1]
        w_l = low[i - span + 1 : i + 1]
        if high[centre] >= np.max(w_h) - 1e-15:
            ph = float(high[centre])
        if low[centre] <= np.min(w_l) + 1e-15:
            pl = float(low[centre])
        last_ph[i] = ph
        last_pl[i] = pl
    return last_ph, last_pl


def sats_adaptive_supertrend(
    high: np.ndarray,
    low: np.ndarray,
    close: np.ndarray,
    *,
    atr_len: int = 14,
    base_mult: float = 2.0,
    use_tqi: bool = True,
    quality_strength: float = 0.4,
    quality_curve: float = 1.5,
    use_adaptive: bool = True,
    adapt_strength: float = 0.5,
    use_asym: bool = True,
    asym_strength: float = 0.5,
    use_eff_atr: bool = True,
    mult_smooth: bool = True,
    smooth_alpha: float = 0.15,
    use_char_flip: bool = True,
    char_flip_min_age: int = 5,
    char_flip_high: float = 0.55,
    char_flip_low: float = 0.25,
    char_flip_window: int = 5,
    er_len: int = 20,
) -> dict[str, np.ndarray]:
    """SATS adaptive SuperTrend on the chart TF.

    ``trend`` is +1 bull / -1 bear. ``char_flip[i]`` is True when this bar's
    flip was a window-based TQI collapse rather than a price break.
    Band source is hl2 (TV SuperTrend), not close (PRT port).
    """
    n = len(close)
    h = high.astype(np.float64, copy=False)
    l = low.astype(np.float64, copy=False)
    c = close.astype(np.float64, copy=False)
    src = (h + l) * 0.5
    raw_atr = atr(h, l, c, atr_len)
    tqi, er, vol_ratio = tqi_series(h, l, c, raw_atr, er_len=er_len)

    atr_val = raw_atr.copy()
    if use_eff_atr:
        atr_val = raw_atr * (0.5 + 0.5 * er)

    trend = np.ones(n, dtype=np.int8)
    st_line = np.full(n, np.nan, dtype=np.float64)
    char_flip = np.zeros(n, dtype=bool)
    price_flip = np.zeros(n, dtype=bool)
    active_mult = np.full(n, np.nan, dtype=np.float64)
    passive_mult = np.full(n, np.nan, dtype=np.float64)
    tqi_win_high = np.full(n, np.nan, dtype=np.float64)
    w = max(int(char_flip_window), 1)
    for i in range(w, n):
        tqi_win_high[i] = float(np.nanmax(tqi[i - w : i]))

    dir_ = 1
    line = np.nan
    age = 0
    act_sm = np.nan
    pas_sm = np.nan

    for i in range(n):
        ai = atr_val[i]
        qi = tqi[i]
        if np.isnan(ai) or np.isnan(src[i]):
            trend[i] = dir_
            st_line[i] = line
            continue

        legacy = 1.0 + adapt_strength * (0.5 - er[i]) if use_adaptive else 1.0
        if use_tqi:
            qdev = (1.0 - qi) ** quality_curve if qi < 1.0 else 0.0
            tqi_mult = 1.0 - quality_strength + quality_strength * (0.6 + 0.8 * qdev)
        else:
            tqi_mult = 1.0
        sym = base_mult * legacy * tqi_mult
        if use_tqi and use_asym:
            act_raw = sym * (1.0 - asym_strength * qi * 0.3)
            pas_raw = sym * (1.0 + asym_strength * qi * 0.4)
        else:
            act_raw = pas_raw = sym
        if mult_smooth:
            if np.isnan(act_sm):
                act_sm, pas_sm = act_raw, pas_raw
            else:
                act_sm = act_sm * (1.0 - smooth_alpha) + act_raw * smooth_alpha
                pas_sm = pas_sm * (1.0 - smooth_alpha) + pas_raw * smooth_alpha
        else:
            act_sm, pas_sm = act_raw, pas_raw
        active_mult[i] = act_sm
        passive_mult[i] = pas_sm

        if dir_ == 1:
            active_band = src[i] - act_sm * ai
            flip_band = src[i] + pas_sm * ai
        else:
            active_band = src[i] + act_sm * ai
            flip_band = src[i] - pas_sm * ai

        prev = dir_
        if np.isnan(line):
            line = active_band
            dir_ = 1
            age = 0
        else:
            if prev == 1:
                if active_band > line:
                    line = active_band
                if c[i] < line:
                    dir_ = -1
                    line = flip_band
                    price_flip[i] = True
            else:
                if active_band < line:
                    line = active_band
                if c[i] > line:
                    dir_ = 1
                    line = flip_band
                    price_flip[i] = True

            if (
                use_char_flip
                and use_tqi
                and not price_flip[i]
                and age >= char_flip_min_age
                and not np.isnan(tqi_win_high[i])
                and tqi_win_high[i] > char_flip_high
                and qi < char_flip_low
            ):
                if prev == 1:
                    dir_ = -1
                    line = c[i] + pas_sm * ai
                else:
                    dir_ = 1
                    line = c[i] - pas_sm * ai
                char_flip[i] = True

        if dir_ != prev:
            age = 0
        else:
            age += 1

        trend[i] = dir_
        st_line[i] = line

    flip_up = np.zeros(n, dtype=bool)
    flip_dn = np.zeros(n, dtype=bool)
    flip_up[1:] = (trend[1:] == 1) & (trend[:-1] == -1)
    flip_dn[1:] = (trend[1:] == -1) & (trend[:-1] == 1)

    return {
        "trend": trend.astype(np.float64),
        "line": st_line,
        "tqi": tqi,
        "er": er,
        "vol_ratio": vol_ratio,
        "atr": atr_val,
        "raw_atr": raw_atr,
        "flip_up": flip_up,
        "flip_dn": flip_dn,
        "char_flip": char_flip,
        "price_flip": price_flip,
        "active_mult": active_mult,
        "passive_mult": passive_mult,
        "tqi_win_high": tqi_win_high,
    }
