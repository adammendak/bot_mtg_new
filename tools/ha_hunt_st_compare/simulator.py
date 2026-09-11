"""Bar-by-bar replay of ``pine/ha_hunt_m45_m5_st.pine``.

Closed HTF values use the Pine ``f_*Closed()`` idiom: last *completed* HTF bar,
then ``[1]`` (one extra closed HTF bar). Live / forming HTF is never used for
bias, SL, structure, or exits.

One book at a time. Conservative same-bar: stop is evaluated before TP1;
if both the stop and TP1 are touched on the entry/exit bar, the stop wins.

``allow_pyramid=False`` (main / pre-#142): ``canEnter = fills < capReg and pos == 0``.
Sequential fills after a close still count toward ``capReg``; no add while open.

``allow_pyramid=True`` (PR #142): same-direction adds while open, up to ``capReg``.
Opposite entry is ignored. Book is aggregated: size = open units, entry = average
fill, SL stays at the initial protective stop (or BE after TP1). 1R / TP1
recompute from the average. Adding after TP1 keeps ``tp1Hit`` and moves BE to
the new average.

R accounting:
- ``scale_tp1=False`` (locked M45-ST overlay): full-position exit / initial 1R.
- ``scale_tp1=True``: TP1 closes 50% at +2R on that half (= +1R booked);
  combined R = 1.0 + 0.5 × runner_R after TP1, or full-position R if stopped
  before TP1.
- Pyramid books report **size-weighted R** vs the first fill's 1R
  (``|fill0 − SL0|``). A 2-unit stop ≈ −2R; a 2-unit TP1+BE ≈ +2R. One-unit
  books stay identical to the published half+BE+HA numbers.

Runner (Adam lock, ``exit_ha_flip=True``):
- After TP1 the remaining stop is **entry (BE)** and stays there.
- Full runner exit = confirmed chart-TF Heikin-Ashi colour flip against
  the position (body close), or BE hit. Chart TF is M5 (locked) or M15
  (analogy). No M45 slow-band, no ST-line trail, no ST-flip.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import numpy as np

from .indicators import atr, heikin_ashi, rma, rma_band, supertrend
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
    st_tf_minutes: int = 45  # 45 = locked M45 ST; 60 = H1 ST bias+SL
    chart_minutes: int = 5  # 5 = locked M5 trigger; 15 = M15 analogy
    scale_tp1: bool = False  # True = 50% at TP1, runner stop → BE
    exit_slow_band: bool = True  # M45 slow-band body after TP1 (OLD runner)
    exit_ha_flip: bool = False  # chart-TF HA colour flip against the runner
    trail_after_tp1: bool = True  # False = stop stays at BE; no ST-line trail
    allow_pyramid: bool = False  # True = PR #142 same-direction adds while open
    block_htf_band_overlap: bool = False  # True = skip both sides when closed M45 ribbons overlap


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
    r_full: float
    tp1_hit: bool
    reason: str
    variant: str
    units: int = 1
    n_adds: int = 0


@dataclass
class Book:
    symbol: str
    variant: str
    trades: list[Trade] = field(default_factory=list)
    n: int = 0
    wr_pct: float = 0.0
    wr_full_pct: float = 0.0  # win if unscaled full-position R > 0
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
    n_fills: int = 0
    n_pyramid: int = 0  # books that received a 2nd (or later) same-direction add
    pyramid_pct: float = 0.0


def m45_bands_overlap(fup: float, flo: float, sup: float, slo: float) -> bool:
    """Closed HTF fast+slow ribbons intersect (not clear either side).

    Long clear = flo > sup; short clear = fup < slo. Missing bands are not overlap
    (warmup) so the filter does not blank the book before HTF RMAs exist.
    """
    if any(np.isnan(x) for x in (fup, flo, sup, slo)):
        return False
    clear_long = flo > sup
    clear_short = fup < slo
    return (not clear_long) and (not clear_short)


def _metrics(trades: list[Trade], symbol: str, variant: str) -> Book:
    b = Book(symbol=symbol, variant=variant, trades=trades, n=len(trades))
    if not trades:
        return b
    rs = np.array([t.r for t in trades], dtype=np.float64)
    rf = np.array([t.r_full for t in trades], dtype=np.float64)
    b.wr_pct = float(np.mean(rs > 0.0) * 100.0)
    b.wr_full_pct = float(np.mean(rf > 0.0) * 100.0)
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
        b.n_fills += int(t.units)
        if t.units > 1:
            b.n_pyramid += 1
    b.pyramid_pct = 100.0 * b.n_pyramid / b.n if b.n else 0.0
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
    chart_starts: np.ndarray,
    htf_starts: np.ndarray,
    minutes: int,
    shift: int,
    chart_minutes: int = 5,
) -> np.ndarray:
    """For each chart bar, index of the Pine-closed HTF bar, or -1.

    Last completed HTF at this chart confirm:
    ``htf_start + span <= chart_start + chart_minutes``.
    Pine ``[1]`` then steps back ``shift`` closed bars.
    """
    span = minutes * 60
    chart_end = chart_starts + chart_minutes * 60
    cutoff = chart_end - span
    pos = np.searchsorted(htf_starts, cutoff, side="right") - 1
    pos = pos - shift
    pos[pos < 0] = -1
    return pos


def simulate(symbol: str, bars: Bars, p: Params) -> Book:
    o, h, l, c = bars.open, bars.high, bars.low, bars.close
    n = len(c)
    if n < 50:
        return _metrics([], symbol, p.name)

    # bars.time is the chart-TF open (M5 or M15). Do not rebucket here.
    sec = bars.time.astype("datetime64[s]").astype(np.int64)
    chart_starts = sec
    chart_min = int(p.chart_minutes)

    hs, ho, hh, hl, hc = _aggregate_htf(_bucket_starts(bars.time, p.m45_minutes), o, h, l, c)
    if len(hc) < p.slow_len + p.htf_closed_shift + 5:
        return _metrics([], symbol, p.name)

    m45_rf = rma(hc, p.fast_len)
    m45_rs = rma(hc, p.slow_len)
    m45_fup, m45_flo = rma_band(hh, hl, p.fast_len)
    m45_sup, m45_slo = rma_band(hh, hl, p.slow_len)
    m45_st, m45_dir = supertrend(hh, hl, hc, p.st_factor, p.st_atr_len)
    m45_atr14 = atr(hh, hl, hc, 14)

    closed_i = _map_closed_htf(chart_starts, hs, p.m45_minutes, p.htf_closed_shift, chart_min)

    def htf_val(arr: np.ndarray, i: int) -> float:
        j = closed_i[i]
        if j < 0:
            return np.nan
        return float(arr[j])

    # Bias + default SL Supertrend: M45 (locked) or closed H1.
    if p.st_tf_minutes == 60:
        h1s, _, h1h, h1l, h1c = _aggregate_htf(_bucket_starts(bars.time, 60), o, h, l, c)
        if len(h1c) < p.st_atr_len + p.htf_closed_shift + 5:
            return _metrics([], symbol, p.name)
        st_line, st_dir_arr = supertrend(h1h, h1l, h1c, p.st_factor, p.st_atr_len)
        st_closed = _map_closed_htf(chart_starts, h1s, 60, p.htf_closed_shift, chart_min)
    else:
        st_line, st_dir_arr = m45_st, m45_dir
        st_closed = closed_i

    def st_val(arr: np.ndarray, i: int) -> float:
        j = st_closed[i]
        if j < 0:
            return np.nan
        return float(arr[j])

    flip_reason = "h1_st_flip" if p.st_tf_minutes == 60 else "m45_st_flip"

    _, _, ha_bull = heikin_ashi(o, h, l, c)

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
    units = 0
    fill_pxs: list[float] = []
    units_at_tp1 = 0
    ent = stp = tgt = last_r = init_sl = np.nan
    tp1_hit = False
    ent_i = 0
    prev_bull: Optional[bool] = None

    iso = bars.time.astype("datetime64[s]")

    def stop_price(i: int, is_long: bool) -> float:
        px = c[i]
        atr_r = p.atr_mult * htf_val(m45_atr14, i)
        atr_sl = px - atr_r if is_long else px + atr_r
        st = st_val(st_line, i)
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
            return st_val(st_line, i)
        fup = htf_val(m45_fup, i)
        flo = htf_val(m45_flo, i)
        width = fup - flo
        if np.isnan(width) or width <= 0:
            return np.nan
        buf = p.band_sl_buf * width
        return flo - buf if is_long else fup + buf

    def close_trade(i: int, exit_px: float, reason: str) -> None:
        nonlocal pos, tp1_hit, units, fill_pxs, units_at_tp1
        n_u = len(fill_pxs) if fill_pxs else max(units, 1)
        first_r = abs(fill_pxs[0] - init_sl) if fill_pxs else last_r
        if not first_r or first_r <= 0 or np.isnan(first_r):
            pos = 0
            tp1_hit = False
            units = 0
            fill_pxs = []
            units_at_tp1 = 0
            return
        sign = 1.0 if pos == 1 else -1.0
        if n_u <= 1:
            # Identical to the published one-unit half+BE+HA formula.
            r_dist = last_r if last_r and last_r > 0 and not np.isnan(last_r) else first_r
            if pos == 1:
                r_full = (exit_px - ent) / r_dist
            else:
                r_full = (ent - exit_px) / r_dist
            r_split = (0.5 * p.tp1_mult + 0.5 * r_full) if tp1_hit else r_full
            r = r_split if (p.scale_tp1 and tp1_hit) else r_full
        else:
            # Size-weighted vs first-fill 1R so a 2-unit book is ~2 tickets of risk.
            pnl_full = sum((exit_px - fp) * sign for fp in fill_pxs)
            r_full = pnl_full / first_r
            if p.scale_tp1 and tp1_hit:
                u_tp1 = units_at_tp1 if units_at_tp1 else n_u
                pnl = 0.0
                for idx, fp in enumerate(fill_pxs):
                    if idx < u_tp1:
                        pnl += 0.5 * (tgt - fp) * sign
                        pnl += 0.5 * (exit_px - fp) * sign
                    else:
                        pnl += (exit_px - fp) * sign
                r = pnl / first_r
                r_split = r
            else:
                r = r_full
                r_split = r_full
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
                r_full=float(r_full),
                tp1_hit=bool(tp1_hit),
                reason=reason,
                variant=p.name,
                units=int(n_u),
                n_adds=max(int(n_u) - 1, 0),
            )
        )
        pos = 0
        tp1_hit = False
        units = 0
        fill_pxs = []
        units_at_tp1 = 0

    warmup = max(p.fast_len, 2)
    for i in range(n):
        j = closed_i[i]
        st_dir = st_val(st_dir_arr, i)
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
                p.exit_slow_band
                and p.use_tp1
                and tp1_hit
                and ((pos == 1 and c[i] < m45_slo_i) or (pos == -1 and c[i] > m45_sup_i))
            )
            exit_st = p.exit_st_flip and ((pos == 1 and not st_bull) or (pos == -1 and st_bull))
            if exit_stop:
                if tp1_hit and p.scale_tp1 and not p.trail_after_tp1:
                    close_trade(i, float(stp), "be")
                else:
                    close_trade(i, float(stp), "trail" if tp1_hit else "stop")
            elif exit_slow:
                close_trade(i, float(c[i]), "m45_slow_band")
            elif exit_st:
                close_trade(i, float(c[i]), flip_reason)
            else:
                if p.use_tp1 and not tp1_hit and ((pos == 1 and h[i] >= tgt) or (pos == -1 and l[i] <= tgt)):
                    tp1_hit = True
                    units_at_tp1 = len(fill_pxs) if fill_pxs else max(units, 1)
                    # Half-TP1: runner stop jumps to entry immediately.
                    # Full-trail (no scale) does not force BE.
                    if p.scale_tp1:
                        stp = float(ent)
                        if (pos == 1 and l[i] <= stp) or (pos == -1 and h[i] >= stp):
                            close_trade(i, float(stp), "be" if not p.trail_after_tp1 else "trail")
                if pos != 0 and p.use_tp1 and tp1_hit and p.trail_after_tp1:
                    tr = trail_stop(i, pos == 1)
                    if p.scale_tp1:
                        if pos == 1:
                            stp = float(ent) if stp < ent else stp
                            if not np.isnan(tr) and tr > stp:
                                stp = tr
                        else:
                            stp = float(ent) if stp > ent else stp
                            if not np.isnan(tr) and tr < stp:
                                stp = tr
                    elif not np.isnan(tr):
                        if pos == 1 and tr > stp:
                            stp = tr
                        if pos == -1 and tr < stp:
                            stp = tr
                    if p.scale_tp1 and ((pos == 1 and l[i] <= stp) or (pos == -1 and h[i] >= stp)):
                        close_trade(i, float(stp), "trail")
                # Runner: confirmed closed M5 HA colour flip against the position.
                # Long: bull → bear (haClose < haOpen after being bull).
                # Short: bear → bull. Only after TP1. Exit at body close.
                if pos != 0 and p.exit_ha_flip and tp1_hit and i > 0:
                    if pos == 1:
                        exit_ha = bool(ha_bull[i - 1]) and (not bool(ha_bull[i]))
                    else:
                        exit_ha = (not bool(ha_bull[i - 1])) and bool(ha_bull[i])
                    if exit_ha:
                        close_trade(i, float(c[i]), f"m{chart_min}_ha_flip")

        ready = fills < p.cap_reg and i >= warmup and j >= 0 and st_closed[i] >= 0
        if p.allow_pyramid:
            can_long = ready and (pos == 0 or pos == 1)
            can_short = ready and (pos == 0 or pos == -1)
        else:
            can_long = ready and pos == 0
            can_short = ready and pos == 0
        m45_c = htf_val(hc, i)
        m45_rf_i = htf_val(m45_rf, i)
        m45_rs_i = htf_val(m45_rs, i)
        m45_fup_i = htf_val(m45_fup, i)
        m45_flo_i = htf_val(m45_flo, i)
        m45_sup_i = htf_val(m45_sup, i)
        m45_slo_i = htf_val(m45_slo, i)
        stack_l = (not np.isnan(m45_c)) and m45_c > m45_rf_i and m45_rf_i > m45_rs_i
        stack_s = (not np.isnan(m45_c)) and m45_c < m45_rf_i and m45_rf_i < m45_rs_i
        leave_l = (not np.isnan(m45_fup_i)) and c[i] > m45_fup_i
        leave_s = (not np.isnan(m45_flo_i)) and c[i] < m45_flo_i
        gate_l = (not p.req_m45_struct) or stack_l or leave_l
        gate_s = (not p.req_m45_struct) or stack_s or leave_s
        m45_overlap = m45_bands_overlap(m45_fup_i, m45_flo_i, m45_sup_i, m45_slo_i)
        htf_ovl_ok = (not p.block_htf_band_overlap) or (not m45_overlap)
        long_raw = st_bull and bool(cross_long[i]) and gate_l and htf_ovl_ok
        short_raw = st_bear and bool(cross_short[i]) and gate_s and htf_ovl_ok
        long_sig = long_raw and can_long and p.enable_long
        short_sig = short_raw and can_short and p.enable_short and not p.long_only

        if long_sig or short_sig:
            is_long = bool(long_sig)
            is_add = p.allow_pyramid and pos != 0 and ((pos == 1 and is_long) or (pos == -1 and not is_long))
            if is_add:
                fill_pxs.append(float(c[i]))
                units = len(fill_pxs)
                ent = float(np.mean(fill_pxs))
                fills += 1
                if tp1_hit and p.scale_tp1 and not p.trail_after_tp1:
                    stp = float(ent)
                last_r = abs(ent - stp)
                if (not tp1_hit) and last_r > 0 and not np.isnan(last_r):
                    tgt = ent + p.tp1_mult * last_r if is_long else ent - p.tp1_mult * last_r
            else:
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
                    fill_pxs = [float(c[i])]
                    units = 1
                    units_at_tp1 = 0

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


def h1_compare_variants() -> list[Params]:
    """2×2: ST TF (M45 vs H1) × TP1 (full trail vs 50% scale)."""
    locked = dict(
        slow_len=144,
        band_cross_strict=False,
        req_m45_struct=True,
        cap_reg=2,
        stop_mode="st",
        st_factor=2.0,
        long_only=False,
        use_tp1=True,
        tp1_mult=2.0,
        exit_st_flip=True,
    )
    return [
        Params(name="baseline_144", st_tf_minutes=45, scale_tp1=False, **locked),  # A
        Params(name="m45st_partial", st_tf_minutes=45, scale_tp1=True, **locked),  # B
        Params(name="h1st_partial", st_tf_minutes=60, scale_tp1=True, **locked),  # C
        Params(name="h1st_full", st_tf_minutes=60, scale_tp1=False, **locked),  # D
    ]


def _ha_exit_locked(**overrides) -> Params:
    """Half TP1 + BE + M5 HA flip. ST is bias + initial SL only."""
    base = dict(
        slow_len=144,
        band_cross_strict=False,
        req_m45_struct=True,
        cap_reg=2,
        stop_mode="st",
        st_factor=2.0,
        long_only=False,
        use_tp1=True,
        tp1_mult=2.0,
        scale_tp1=True,
        exit_st_flip=False,
        exit_slow_band=False,
        exit_ha_flip=True,
        trail_after_tp1=False,
    )
    base.update(overrides)
    return Params(**base)


def ha_exit_variants() -> list[Params]:
    """A = M45 ST + HA runner; B = H1 ST + HA runner."""
    return [
        _ha_exit_locked(name="ha_m45", st_tf_minutes=45),
        _ha_exit_locked(name="ha_h1", st_tf_minutes=60),
    ]


def pyramid_ab_variants() -> list[Params]:
    """A–D matrix: ST 10/2 vs 12/3 × pyramid OFF vs ON.

    Locked half+BE+HA, slow 144, gate ON, cap 2. Stacks are applied by the
    runner (M5+M45 and M15+H1) via chart/st TF overrides.
    """
    cells = [
        ("A_st10x2_flat", 10, 2.0, False),
        ("B_st10x2_pyr", 10, 2.0, True),
        ("C_st12x3_flat", 12, 3.0, False),
        ("D_st12x3_pyr", 12, 3.0, True),
    ]
    out: list[Params] = []
    for name, atr_len, factor, pyr in cells:
        out.append(
            _ha_exit_locked(
                name=name,
                st_atr_len=atr_len,
                st_factor=factor,
                allow_pyramid=pyr,
            )
        )
    return out


def m15_ha_exit_variants() -> list[Params]:
    """M15 analogy: A = M45 ST, B = H1 ST, C = optional M15 full-trail."""
    return [
        _ha_exit_locked(name="m15_m45", chart_minutes=15, st_tf_minutes=45),
        _ha_exit_locked(name="m15_h1", chart_minutes=15, st_tf_minutes=60),
        Params(
            name="m15_m45_full",
            chart_minutes=15,
            st_tf_minutes=45,
            slow_len=144,
            band_cross_strict=False,
            req_m45_struct=True,
            cap_reg=2,
            stop_mode="st",
            st_factor=2.0,
            long_only=False,
            use_tp1=True,
            tp1_mult=2.0,
            scale_tp1=False,
            exit_st_flip=True,
            exit_slow_band=True,
            exit_ha_flip=False,
            trail_after_tp1=True,
        ),
    ]


def ha_exit_wr_perms(st_tf_minutes: int) -> list[Params]:
    """Stricter one-at-a-time + a few combos aimed at ~50% WR (slow 144)."""
    tag = "m45" if st_tf_minutes == 45 else "h1"
    return [
        _ha_exit_locked(name=f"ha_{tag}_strict", st_tf_minutes=st_tf_minutes, band_cross_strict=True),
        _ha_exit_locked(name=f"ha_{tag}_cap1", st_tf_minutes=st_tf_minutes, cap_reg=1),
        _ha_exit_locked(name=f"ha_{tag}_st3", st_tf_minutes=st_tf_minutes, st_factor=3.0),
        _ha_exit_locked(name=f"ha_{tag}_longonly", st_tf_minutes=st_tf_minutes, long_only=True),
        _ha_exit_locked(
            name=f"ha_{tag}_strict_cap1",
            st_tf_minutes=st_tf_minutes,
            band_cross_strict=True,
            cap_reg=1,
        ),
        _ha_exit_locked(
            name=f"ha_{tag}_strict_st3",
            st_tf_minutes=st_tf_minutes,
            band_cross_strict=True,
            st_factor=3.0,
        ),
        _ha_exit_locked(
            name=f"ha_{tag}_strict_cap1_st3",
            st_tf_minutes=st_tf_minutes,
            band_cross_strict=True,
            cap_reg=1,
            st_factor=3.0,
        ),
    ]
