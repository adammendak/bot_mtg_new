"""Bar-by-bar replay of ``pine/ha_hunt_m45_m5_st.pine``.

Closed M45 values use the Pine ``f_*Closed()`` idiom: last *completed* M45 bar,
then ``[1]`` (one extra closed HTF bar). Live / forming M45 is never used for
bias, SL, structure, or exits.

One position at a time. Conservative same-bar: stop is evaluated before TP1;
if both the stop and TP1 are touched on the entry/exit bar, the stop wins.
R is full-position (Pine overlay does not scale out); TP1 only arms the runner
trail. HTS-style 50/50 split R is reported as ``r_split`` for comparison.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import numpy as np

from .indicators import atr, rma, rma_band, supertrend
from .ohlc import Bars


@dataclass(frozen=True)
class Params:
    name: str = "baseline_144"
    fast_len: int = 33
    slow_len: int = 144
    st_atr_len: int = 10
    st_factor: float = 2.0
    band_cross_strict: bool = False
    req_m45_struct: bool = True
    cap_reg: int = 2
    stop_mode: str = "st"  # st | atr | band
    atr_mult: float = 2.5
    band_sl_buf: float = 0.25
    use_tp1: bool = True
    tp1_mult: float = 2.0
    exit_st_flip: bool = True
    long_only: bool = False
    enable_long: bool = True
    enable_short: bool = True
    htf_closed_shift: int = 1  # Pine f_*Closed [1]
    m45_minutes: int = 45


@dataclass
class Trade:
    symbol: str
    direction: str
    entry_time: str
    exit_time: str
    entry: float
    stop0: float
    exit: float
    r: float
    r_split: float
    tp1_hit: bool
    reason: str
    variant: str


@dataclass
class Book:
    symbol: str
    variant: str
    trades: list[Trade] = field(default_factory=list)
    n: int = 0
    wr_pct: float = 0.0
    sum_r: float = 0.0
    avg_r: float = 0.0
    max_dd_r: float = 0.0
    pf: float = 0.0
    exits: dict[str, int] = field(default_factory=dict)
    n_long: int = 0
    n_short: int = 0
    sum_r_long: float = 0.0
    sum_r_short: float = 0.0
    n_tp1: int = 0


def _metrics(trades: list[Trade], symbol: str, variant: str) -> Book:
    b = Book(symbol=symbol, variant=variant, trades=trades, n=len(trades))
    if not trades:
        return b
    rs = np.array([t.r for t in trades], dtype=np.float64)
    b.wr_pct = float(np.mean(rs > 0.0) * 100.0)
    b.sum_r = float(rs.sum())
    b.avg_r = float(rs.mean())
    eq = np.cumsum(rs)
    peak = np.maximum.accumulate(eq)
    dd = eq - peak
    b.max_dd_r = float(-dd.min()) if len(dd) else 0.0
    wins = rs[rs > 0].sum()
    losses = -rs[rs < 0].sum()
    b.pf = float(wins / losses) if losses > 0 else (float("inf") if wins > 0 else 0.0)
    for t in trades:
        b.exits[t.reason] = b.exits.get(t.reason, 0) + 1
        if t.direction == "long":
            b.n_long += 1
            b.sum_r_long += t.r
        else:
            b.n_short += 1
            b.sum_r_short += t.r
        if t.tp1_hit:
            b.n_tp1 += 1
    return b


def _bucket_starts(times: np.ndarray, minutes: int) -> np.ndarray:
    """UTC epoch floor — same as Java ``Resample``."""
    # times is datetime64[ns]
    sec = times.astype("datetime64[s]").astype(np.int64)
    span = minutes * 60
    return (sec // span) * span


def _aggregate_htf(
    starts: np.ndarray,
    o: np.ndarray,
    h: np.ndarray,
    l: np.ndarray,
    c: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Aggregate M5 into HTF buckets. Returns HTF (start_sec, o, h, l, c)."""
    if len(starts) == 0:
        z = np.array([], dtype=np.int64)
        e = np.array([], dtype=np.float64)
        return z, e, e, e, e
    change = np.empty(len(starts), dtype=bool)
    change[0] = True
    change[1:] = starts[1:] != starts[:-1]
    idx = np.nonzero(change)[0]
    n = len(idx)
    ho = np.empty(n, dtype=np.float64)
    hh = np.empty(n, dtype=np.float64)
    hl = np.empty(n, dtype=np.float64)
    hc = np.empty(n, dtype=np.float64)
    hs = starts[idx]
    ends = np.append(idx[1:], len(starts))
    for i, (a, b) in enumerate(zip(idx, ends)):
        ho[i] = o[a]
        hh[i] = np.max(h[a:b])
        hl[i] = np.min(l[a:b])
        hc[i] = c[b - 1]
    return hs, ho, hh, hl, hc


def _map_closed_htf(
    m5_starts: np.ndarray,
    htf_starts: np.ndarray,
    minutes: int,
    shift: int,
) -> np.ndarray:
    """For each M5 bar, index of the Pine-closed HTF bar, or -1.

    Last completed HTF at this M5 confirm: ``htf_start + span <= m5_start + 5m``.
    Pine ``[1]`` then steps back ``shift`` closed bars.
    """
    span = minutes * 60
    m5_end = m5_starts + 5 * 60
    # last htf with start+span <= m5_end  →  start <= m5_end - span
    cutoff = m5_end - span
    pos = np.searchsorted(htf_starts, cutoff, side="right") - 1
    pos = pos - shift
    pos[pos < 0] = -1
    return pos


def simulate(symbol: str, bars: Bars, p: Params) -> Book:
    o, h, l, c = bars.open, bars.high, bars.low, bars.close
    n = len(c)
    if n < 50:
        return _metrics([], symbol, p.name)

    m5_starts = _bucket_starts(bars.time, 1)  # already 5m opens; use actual epoch
    # bars.time is the M5 open; keep it (do not rebucket by 1 minute)
    sec = bars.time.astype("datetime64[s]").astype(np.int64)
    m5_starts = sec

    hs, ho, hh, hl, hc = _aggregate_htf(_bucket_starts(bars.time, p.m45_minutes), o, h, l, c)
    if len(hc) < p.slow_len + p.htf_closed_shift + 5:
        return _metrics([], symbol, p.name)

    m45_rf = rma(hc, p.fast_len)
    m45_rs = rma(hc, p.slow_len)
    m45_fup, m45_flo = rma_band(hh, hl, p.fast_len)
    m45_sup, m45_slo = rma_band(hh, hl, p.slow_len)
    m45_st, m45_dir = supertrend(hh, hl, hc, p.st_factor, p.st_atr_len)
    m45_atr14 = atr(hh, hl, hc, 14)

    closed_i = _map_closed_htf(m5_starts, hs, p.m45_minutes, p.htf_closed_shift)

    def htf_val(arr: np.ndarray, i: int) -> float:
        j = closed_i[i]
        if j < 0:
            return np.nan
        return float(arr[j])

    f_up, f_lo = rma_band(h, l, p.fast_len)
    s_up, s_lo = rma_band(h, l, p.slow_len)

    beyond_long = c > f_up
    beyond_short = c < f_lo
    if p.band_cross_strict:
        long_now = beyond_long & (f_lo > s_up)
        short_now = beyond_short & (f_up < s_lo)
    else:
        long_now = beyond_long.copy()
        short_now = beyond_short.copy()
    # NaN-safe: comparisons with NaN are False
    long_now = np.where(np.isnan(f_up), False, long_now)
    short_now = np.where(np.isnan(f_lo), False, short_now)
    cross_long = np.zeros(n, dtype=bool)
    cross_short = np.zeros(n, dtype=bool)
    cross_long[1:] = long_now[1:] & ~long_now[:-1]
    cross_short[1:] = short_now[1:] & ~short_now[:-1]

    trades: list[Trade] = []
    pos = 0
    fills = 0
    ent = stp = tgt = last_r = init_sl = np.nan
    tp1_hit = False
    ent_i = 0
    prev_bull: Optional[bool] = None

    iso = bars.time.astype("datetime64[s]")

    def stop_price(i: int, is_long: bool) -> float:
        px = c[i]
        atr_r = p.atr_mult * htf_val(m45_atr14, i)
        atr_sl = px - atr_r if is_long else px + atr_r
        st = htf_val(m45_st, i)
        fup = htf_val(m45_fup, i)
        flo = htf_val(m45_flo, i)
        bw = fup - flo
        if p.stop_mode == "st" and not np.isnan(st) and ((st < px) if is_long else (st > px)):
            return st
        if p.stop_mode == "band" and not np.isnan(bw) and bw > 0:
            raw = flo - p.band_sl_buf * bw if is_long else fup + p.band_sl_buf * bw
            if abs(px - raw) > 0:
                return raw
        if p.stop_mode == "st":
            # fallback when ST is on the wrong side of price
            if not np.isnan(bw) and bw > 0:
                raw = flo - p.band_sl_buf * bw if is_long else fup + p.band_sl_buf * bw
                if abs(px - raw) > 0:
                    return raw
        return atr_sl

    def trail_stop(i: int, is_long: bool) -> float:
        if p.stop_mode == "st":
            return htf_val(m45_st, i)
        fup = htf_val(m45_fup, i)
        flo = htf_val(m45_flo, i)
        width = fup - flo
        if np.isnan(width) or width <= 0:
            return np.nan
        buf = p.band_sl_buf * width
        return flo - buf if is_long else fup + buf

    def close_trade(i: int, exit_px: float, reason: str) -> None:
        nonlocal pos, tp1_hit
        r_dist = last_r
        if not r_dist or r_dist <= 0 or np.isnan(r_dist):
            pos = 0
            tp1_hit = False
            return
        if pos == 1:
            r = (exit_px - ent) / r_dist
        else:
            r = (ent - exit_px) / r_dist
        if tp1_hit:
            r_split = 0.5 * p.tp1_mult + 0.5 * r
        else:
            r_split = r
        trades.append(
            Trade(
                symbol=symbol,
                direction="long" if pos == 1 else "short",
                entry_time=str(iso[ent_i]),
                exit_time=str(iso[i]),
                entry=float(ent),
                stop0=float(init_sl),
                exit=float(exit_px),
                r=float(r),
                r_split=float(r_split),
                tp1_hit=bool(tp1_hit),
                reason=reason,
                variant=p.name,
            )
        )
        pos = 0
        tp1_hit = False

    warmup = max(p.fast_len, 2)
    for i in range(n):
        j = closed_i[i]
        st_dir = htf_val(m45_dir, i)
        st_bull = st_dir == -1.0
        st_bear = st_dir == 1.0
        st_flip = prev_bull is not None and st_bull != prev_bull and not np.isnan(st_dir)
        if st_flip:
            fills = 0

        if pos != 0:
            exit_stop = (pos == 1 and l[i] <= stp) or (pos == -1 and h[i] >= stp)
            m45_slo_i = htf_val(m45_slo, i)
            m45_sup_i = htf_val(m45_sup, i)
            exit_slow = (
                p.use_tp1
                and tp1_hit
                and ((pos == 1 and c[i] < m45_slo_i) or (pos == -1 and c[i] > m45_sup_i))
            )
            exit_flip = p.exit_st_flip and ((pos == 1 and not st_bull) or (pos == -1 and st_bull))
            if exit_stop:
                close_trade(i, float(stp), "stop")
            elif exit_slow:
                close_trade(i, float(c[i]), "m45_slow_band")
            elif exit_flip:
                close_trade(i, float(c[i]), "m45_st_flip")
            else:
                if p.use_tp1 and not tp1_hit and ((pos == 1 and h[i] >= tgt) or (pos == -1 and l[i] <= tgt)):
                    tp1_hit = True
                if p.use_tp1 and tp1_hit:
                    tr = trail_stop(i, pos == 1)
                    if not np.isnan(tr):
                        if pos == 1 and tr > stp:
                            stp = tr
                        if pos == -1 and tr < stp:
                            stp = tr

        can_enter = fills < p.cap_reg and pos == 0 and i >= warmup and j >= 0
        m45_c = htf_val(hc, i)
        m45_rf_i = htf_val(m45_rf, i)
        m45_rs_i = htf_val(m45_rs, i)
        m45_fup_i = htf_val(m45_fup, i)
        m45_flo_i = htf_val(m45_flo, i)
        stack_l = (not np.isnan(m45_c)) and m45_c > m45_rf_i and m45_rf_i > m45_rs_i
        stack_s = (not np.isnan(m45_c)) and m45_c < m45_rf_i and m45_rf_i < m45_rs_i
        leave_l = (not np.isnan(m45_fup_i)) and c[i] > m45_fup_i
        leave_s = (not np.isnan(m45_flo_i)) and c[i] < m45_flo_i
        gate_l = (not p.req_m45_struct) or stack_l or leave_l
        gate_s = (not p.req_m45_struct) or stack_s or leave_s
        long_raw = st_bull and bool(cross_long[i]) and gate_l
        short_raw = st_bear and bool(cross_short[i]) and gate_s
        long_sig = long_raw and can_enter and p.enable_long
        short_sig = short_raw and can_enter and p.enable_short and not p.long_only

        if long_sig or short_sig:
            is_long = long_sig
            sl = stop_price(i, is_long)
            r_dist = abs(c[i] - sl)
            if r_dist > 0 and not np.isnan(r_dist):
                pos = 1 if is_long else -1
                ent = float(c[i])
                stp = float(sl)
                init_sl = float(sl)
                last_r = float(r_dist)
                tgt = ent + p.tp1_mult * last_r if is_long else ent - p.tp1_mult * last_r
                tp1_hit = False
                fills += 1
                ent_i = i

        if not np.isnan(st_dir):
            prev_bull = st_bull

    if pos != 0:
        close_trade(n - 1, float(c[-1]), "open_eod")

    return _metrics(trades, symbol, p.name)


def variants() -> list[Params]:
    """Small locked-stack bake-off (~10 cells), not a combinatorial explosion."""
    base = dict(slow_len=144, band_cross_strict=False, req_m45_struct=True, cap_reg=2, stop_mode="st", st_factor=2.0, long_only=False)
    out = [
        Params(name="baseline_144", **base),
        Params(name="cap1", **{**base, "cap_reg": 1}),
        Params(name="strict", **{**base, "band_cross_strict": True}),
        Params(name="nogate", **{**base, "req_m45_struct": False}),
        Params(name="slow100", **{**base, "slow_len": 100}),
        Params(name="stop_atr", **{**base, "stop_mode": "atr"}),
        Params(name="stop_band", **{**base, "stop_mode": "band"}),
        Params(name="st3", **{**base, "st_factor": 3.0}),
        Params(name="long_only", **{**base, "long_only": True}),
        Params(name="cap1_strict", **{**base, "cap_reg": 1, "band_cross_strict": True}),
    ]
    return out
