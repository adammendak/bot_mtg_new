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
