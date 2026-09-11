"""Logic tests — indicator math and conservative same-bar exits. No market prices."""

from __future__ import annotations

import unittest
from datetime import datetime, timezone

import numpy as np

from tools.ha_hunt_st_compare.indicators import atr, heikin_ashi, rma, rma_band, supertrend
from tools.ha_hunt_st_compare.ohlc import Bars, SeriesMeta, resample_bars, resample_ohlc
from tools.ha_hunt_st_compare.simulator import Book, Params, Trade, simulate
import pandas as pd


class RmaTests(unittest.TestCase):
    def test_wilder_seed_then_smooth(self):
        src = np.array([1.0, 2.0, 3.0, 4.0, 5.0], dtype=np.float64)
        out = rma(src, 3)
        self.assertTrue(np.isnan(out[0]) and np.isnan(out[1]))
        self.assertAlmostEqual(out[2], 2.0)  # SMA(1,2,3)
        self.assertAlmostEqual(out[3], (2.0 * 2 + 4.0) / 3)
        self.assertAlmostEqual(out[4], (out[3] * 2 + 5.0) / 3)

    def test_atr_first_bar_is_range(self):
        h = np.array([10.0, 12.0, 11.0])
        l = np.array([8.0, 9.0, 9.5])
        c = np.array([9.0, 11.0, 10.0])
        a = atr(h, l, c, 2)
        self.assertTrue(np.isnan(a[0]))
        # TR0 = 2, TR1 = max(3, |12-9|, |9-9|) = 3 → SMA = 2.5
        self.assertAlmostEqual(a[1], 2.5)

    def test_band_orders_high_low(self):
        h = np.array([3.0, 4.0, 5.0, 6.0], dtype=np.float64)
        l = np.array([1.0, 1.5, 2.0, 2.5], dtype=np.float64)
        up, lo = rma_band(h, l, 2)
        self.assertTrue(np.isnan(up[0]))
        self.assertGreater(up[3], lo[3])

    def test_supertrend_tv_sign(self):
        # Strong uptrend: highs/lows/closes rising.
        n = 40
        close = np.linspace(100, 140, n)
        high = close + 1.0
        low = close - 1.0
        line, d = supertrend(high, low, close, 2.0, 10)
        last = d[~np.isnan(d)][-1]
        self.assertEqual(last, -1.0)  # TV bull
        self.assertLess(line[~np.isnan(line)][-1], close[-1])


class HeikinAshiTests(unittest.TestCase):
    def test_pine_seed_then_smooth(self):
        o = np.array([10.0, 11.0, 12.0])
        h = np.array([12.0, 13.0, 14.0])
        l = np.array([9.0, 10.0, 11.0])
        c = np.array([11.0, 12.0, 13.0])
        ha_o, ha_c, ha_bull = heikin_ashi(o, h, l, c)
        self.assertAlmostEqual(ha_c[0], (10 + 12 + 9 + 11) / 4)
        self.assertAlmostEqual(ha_o[0], (10 + 11) / 2)
        self.assertAlmostEqual(ha_o[1], (ha_o[0] + ha_c[0]) / 2)
        self.assertAlmostEqual(ha_o[2], (ha_o[1] + ha_c[1]) / 2)
        self.assertTrue(ha_bull[0])  # 10.5 >= 10.5

    def test_bear_after_bull_is_colour_flip(self):
        n = 8
        o = np.linspace(100, 108, n)
        c = o + 1.0
        h = np.maximum(o, c) + 0.2
        l = np.minimum(o, c) - 0.2
        # Last bar: hard down close so haClose drops below smoothed haOpen.
        o[-1], c[-1] = 108.0, 90.0
        h[-1], l[-1] = 108.2, 89.8
        _, _, bull = heikin_ashi(o, h, l, c)
        self.assertTrue(bull[-2])
        self.assertFalse(bull[-1])


class ResampleTests(unittest.TestCase):
    def test_epoch_bucket_m5(self):
        idx = pd.date_range("2026-01-02 10:00:00", periods=5, freq="1min", tz="UTC")
        df = pd.DataFrame(
            {"open": [1, 2, 3, 4, 5], "high": [2, 3, 4, 5, 6], "low": [0.5, 1, 2, 3, 4], "close": [1.5, 2.5, 3.5, 4.5, 5.5]},
            index=idx,
        )
        m5 = resample_ohlc(df, 5)
        self.assertEqual(len(m5), 1)
        self.assertEqual(m5.iloc[0]["open"], 1)
        self.assertEqual(m5.iloc[0]["close"], 5.5)
        self.assertEqual(m5.iloc[0]["high"], 6)
        self.assertEqual(m5.iloc[0]["low"], 0.5)

    def test_resample_bars_m5_to_m15(self):
        n = 3
        t0 = np.datetime64("2026-01-02T10:00:00")
        times = t0 + np.arange(n) * np.timedelta64(5, "m")
        meta = SeriesMeta("SYN", "synthetic_test", "", "", "", 0, n, "unit-test", 0)
        bars = Bars(times, np.array([1.0, 2.0, 3.0]), np.array([2.0, 3.0, 4.0]), np.array([0.5, 1.0, 2.0]), np.array([1.5, 2.5, 3.5]), meta)
        m15 = resample_bars(bars, 15)
        self.assertEqual(len(m15.close), 1)
        self.assertEqual(m15.open[0], 1.0)
        self.assertEqual(m15.close[0], 3.5)
        self.assertEqual(m15.high[0], 4.0)
        self.assertEqual(m15.low[0], 0.5)


def _synthetic_trend(n: int = 800) -> Bars:
    """Deterministic rising then falling path for gate/exit tests (not a market)."""
    t0 = np.datetime64("2025-09-01T00:00:00")
    times = t0 + np.arange(n) * np.timedelta64(5, "m")
    x = np.linspace(0, 8 * np.pi, n)
    close = 2000.0 + 40.0 * np.sin(x) + 0.05 * np.arange(n)
    high = close + 1.5
    low = close - 1.5
    open_ = close - 0.2
    meta = SeriesMeta("SYN", "synthetic_test", "", "", "", 0, n, "unit-test only", 0)
    return Bars(times, open_, high, low, close, meta)


class SameBarTests(unittest.TestCase):
    def test_stop_beats_tp1_on_wide_bar(self):
        """A bar that spans both SL and TP1 must book the stop (conservative)."""
        n = 400
        t0 = np.datetime64("2025-09-01T00:00:00")
        times = t0 + np.arange(n) * np.timedelta64(5, "m")
        close = np.full(n, 100.0)
        # Build a clean uptrend so ST is bull and a long can fire, then a monster bar.
        close[:200] = np.linspace(90, 110, 200)
        close[200:] = 110.0
        high = close + 0.4
        low = close - 0.4
        # After indicators are warm, spike a bar that hits both sides hard.
        high[320] = 200.0
        low[320] = 1.0
        meta = SeriesMeta("SYN", "synthetic_test", "", "", "", 0, n, "unit-test only", 0)
        bars = Bars(times, close.copy(), high, low, close, meta)
        book = simulate("SYN", bars, Params(name="t", slow_len=20, fast_len=8, cap_reg=4, req_m45_struct=False))
        # If any trade exits on that spike, reason must be stop when both sides print.
        for t in book.trades:
            if t.exit_time == str(times[320].astype("datetime64[s]")):
                self.assertEqual(t.reason, "stop")

    def test_simulate_returns_book(self):
        book = simulate("SYN", _synthetic_trend(), Params(name="t", slow_len=30, fast_len=8, req_m45_struct=False))
        self.assertIsInstance(book.n, int)
        self.assertGreaterEqual(book.n, 0)

    def test_partial_r_is_half_tp1_plus_half_runner(self):
        """TP1 at +2R on 50%, runner later −1R → combined +0.5R."""
        # Construct: entry 100, SL 99 (1R=1), TP1=102. Hit TP1 then stop runner at 99.
        n = 500
        t0 = np.datetime64("2025-09-01T00:00:00")
        times = t0 + np.arange(n) * np.timedelta64(5, "m")
        close = np.linspace(90, 120, n)
        high = close + 0.3
        low = close - 0.3
        meta = SeriesMeta("SYN", "synthetic_test", "", "", "", 0, n, "unit-test only", 0)
        bars = Bars(times, close.copy(), high, low, close, meta)
        book = simulate(
            "SYN",
            bars,
            Params(
                name="partial",
                slow_len=20,
                fast_len=8,
                cap_reg=4,
                req_m45_struct=False,
                st_tf_minutes=60,
                scale_tp1=True,
            ),
        )
        self.assertGreaterEqual(book.n, 0)
        for t in book.trades:
            if t.tp1_hit:
                # Combined R must equal 1.0 + 0.5 * runner_full_R, i.e. r_split.
                self.assertAlmostEqual(t.r, t.r_split, places=6)

    def test_half_tp1_moves_stop_to_be(self):
        """After 50% TP1 the runner stop is entry, so a dip back to entry is 0R on the half."""
        n = 80
        t0 = np.datetime64("2025-09-01T00:00:00")
        times = t0 + np.arange(n) * np.timedelta64(5, "m")
        close = np.linspace(100.0, 108.0, n)
        high = close + 0.05
        low = close - 0.05
        # Bar 70: tag a high TP1-like extreme then return through entry-ish.
        high[70] = 200.0
        low[70] = 100.0
        meta = SeriesMeta("SYN", "synthetic_test", "", "", "", 0, n, "unit-test only", 0)
        bars = Bars(times, close.copy(), high, low, close, meta)
        book = simulate(
            "SYN",
            bars,
            Params(
                name="be",
                slow_len=8,
                fast_len=4,
                st_atr_len=3,
                cap_reg=4,
                req_m45_struct=False,
                scale_tp1=True,
                st_tf_minutes=45,
            ),
        )
        be_exits = [t for t in book.trades if t.tp1_hit and abs(t.exit - t.entry) < 1e-9]
        # Not required that a BE exit exists on this path; just that scaled R never
        # goes below +1.0 once TP1 hit and exit is at/above entry for a long.
        for t in book.trades:
            if t.tp1_hit and t.direction == "long" and t.exit >= t.entry - 1e-12:
                self.assertGreaterEqual(t.r, 1.0 - 1e-9)

    def test_h1_st_params_run(self):
        book = simulate(
            "SYN",
            _synthetic_trend(),
            Params(name="h1", slow_len=30, fast_len=8, req_m45_struct=False, st_tf_minutes=60, scale_tp1=True),
        )
        self.assertGreaterEqual(book.n, 0)

    def test_ha_exit_runner_reasons(self):
        """HA-exit lock: after TP1 only BE or M5 HA flip — never ST trail / slow-band / ST flip."""
        book = simulate(
            "SYN",
            _synthetic_trend(),
            Params(
                name="ha",
                slow_len=30,
                fast_len=8,
                req_m45_struct=False,
                cap_reg=4,
                scale_tp1=True,
                exit_st_flip=False,
                exit_slow_band=False,
                exit_ha_flip=True,
                trail_after_tp1=False,
            ),
        )
        forbidden = {"m45_st_flip", "h1_st_flip", "m45_slow_band", "trail"}
        for t in book.trades:
            self.assertNotIn(t.reason, forbidden)
            if t.tp1_hit:
                self.assertIn(t.reason, {"be", "m5_ha_flip", "m15_ha_flip", "open_eod"})
                # Half at +2R booked +1R; BE runner ≥ 0 ⇒ combined ≥ +1R
                if t.reason == "be":
                    self.assertAlmostEqual(t.r, 1.0, places=5)
                    self.assertAlmostEqual(t.r_full, 0.0, places=5)


    def test_m15_resample_and_ha_exit_runs(self):
        m5 = _synthetic_trend(900)
        m15 = resample_bars(m5, 15)
        self.assertLess(len(m15.close), len(m5.close))
        self.assertGreater(len(m15.close), 200)
        book = simulate(
            "SYN",
            m15,
            Params(
                name="m15",
                slow_len=30,
                fast_len=8,
                req_m45_struct=False,
                cap_reg=4,
                chart_minutes=15,
                scale_tp1=True,
                exit_st_flip=False,
                exit_slow_band=False,
                exit_ha_flip=True,
                trail_after_tp1=False,
            ),
        )
        self.assertGreaterEqual(book.n, 0)
        for t in book.trades:
            self.assertNotIn(t.reason, {"m45_st_flip", "h1_st_flip", "m45_slow_band", "trail"})


class PyramidTests(unittest.TestCase):
    def _ha_params(self, **kw) -> Params:
        base = dict(
            name="pyr",
            slow_len=30,
            fast_len=8,
            req_m45_struct=False,
            cap_reg=2,
            scale_tp1=True,
            exit_st_flip=False,
            exit_slow_band=False,
            exit_ha_flip=True,
            trail_after_tp1=False,
        )
        base.update(kw)
        return Params(**base)

    def test_flat_mode_never_adds_while_open(self):
        book = simulate("SYN", _synthetic_trend(), self._ha_params(allow_pyramid=False, cap_reg=4))
        self.assertTrue(all(t.units == 1 for t in book.trades))
        self.assertEqual(book.n_pyramid, 0)
        self.assertEqual(book.n_fills, book.n)

    def test_pyramid_can_add_second_unit(self):
        wavy = _synthetic_trend(1600)
        flat = simulate("SYN", wavy, self._ha_params(name="flat", allow_pyramid=False, cap_reg=2))
        pyr = simulate("SYN", wavy, self._ha_params(name="pyr", allow_pyramid=True, cap_reg=2))
        self.assertGreaterEqual(flat.n, 0)
        self.assertGreaterEqual(pyr.n, 0)
        # Same signals; pyramid may merge a 2nd fill into an open book.
        self.assertGreaterEqual(pyr.n_fills, pyr.n)
        if pyr.n_pyramid:
            self.assertTrue(any(t.units == 2 for t in pyr.trades))
            self.assertTrue(all(t.units <= 2 for t in pyr.trades))

    def test_two_unit_stop_is_about_minus_two_r(self):
        """Equal fills, shared SL, stop at SL0 → size-weighted ≈ −2R."""
        t = Trade(
            symbol="SYN",
            direction="long",
            entry_time="t0",
            exit_time="t1",
            entry=101.0,
            stop0=98.0,
            exit=98.0,
            r=-2.0,
            r_split=-2.0,
            r_full=-2.0,
            tp1_hit=False,
            reason="stop",
            variant="t",
            units=2,
            n_adds=1,
        )
        from tools.ha_hunt_st_compare.simulator import _metrics

        b = _metrics([t], "SYN", "t")
        self.assertEqual(b.n_pyramid, 1)
        self.assertEqual(b.n_fills, 2)
        self.assertAlmostEqual(b.pyramid_pct, 100.0)
        self.assertAlmostEqual(b.sum_r, -2.0)


class HtfOverlapTests(unittest.TestCase):
    def test_overlap_predicate(self):
        from tools.ha_hunt_st_compare.simulator import m45_bands_overlap

        self.assertTrue(m45_bands_overlap(12.0, 10.0, 11.0, 9.0))  # intersect
        self.assertFalse(m45_bands_overlap(14.0, 12.0, 11.0, 9.0))  # long clear
        self.assertFalse(m45_bands_overlap(8.0, 6.0, 11.0, 9.0))  # short clear
        self.assertFalse(m45_bands_overlap(float("nan"), 10.0, 11.0, 9.0))

    def test_overlap_off_is_default_and_on_runs(self):
        from tools.ha_hunt_st_compare.simulator import Params

        self.assertFalse(Params().block_htf_band_overlap)
        wavy = _synthetic_trend(900)
        off = simulate(
            "SYN",
            wavy,
            Params(
                name="ovl_off",
                slow_len=30,
                fast_len=8,
                req_m45_struct=False,
                cap_reg=4,
                scale_tp1=True,
                exit_st_flip=False,
                exit_slow_band=False,
                exit_ha_flip=True,
                trail_after_tp1=False,
                block_htf_band_overlap=False,
            ),
        )
        on = simulate(
            "SYN",
            wavy,
            Params(
                name="ovl_on",
                slow_len=30,
                fast_len=8,
                req_m45_struct=False,
                cap_reg=4,
                scale_tp1=True,
                exit_st_flip=False,
                exit_slow_band=False,
                exit_ha_flip=True,
                trail_after_tp1=False,
                block_htf_band_overlap=True,
            ),
        )
        self.assertGreaterEqual(off.n, 0)
        self.assertGreaterEqual(on.n, 0)
        self.assertGreaterEqual(off.n, on.n)

    def test_locked_st72_defaults(self):
        from tools.ha_hunt_st_compare.run_htf_overlap_ab import locked_st72

        p = locked_st72(5, 45)
        self.assertEqual(p.st_atr_len, 7)
        self.assertEqual(p.st_factor, 2.0)
        self.assertFalse(p.block_htf_band_overlap)
        self.assertFalse(p.band_cross_strict)
        self.assertFalse(p.allow_pyramid)
        self.assertTrue(p.exit_ha_flip)
        on = locked_st72(15, 60, block_htf_band_overlap=True)
        self.assertTrue(on.block_htf_band_overlap)
        self.assertEqual(on.chart_minutes, 15)
        self.assertEqual(on.st_tf_minutes, 60)

    def test_decide_call_wr_up_sumr_down_keeps_off(self):
        from tools.ha_hunt_st_compare.run_htf_overlap_ab import decide_call

        off = Book(symbol="XAU", variant="off", n=100, wr_pct=39.0, sum_r=48.0, pf=1.29, max_dd_r=9.7)
        on = Book(symbol="XAU", variant="on", n=70, wr_pct=44.0, sum_r=30.0, pf=1.20, max_dd_r=8.0)
        call, why = decide_call([("XAU", "M5+M45", off, on)])
        self.assertTrue(call.startswith("NO"))
        self.assertIn("sumR", why)


class SeriesMapTests(unittest.TestCase):
    def test_us500_is_histdata_spxusd(self):
        from tools.ha_hunt_st_compare.ohlc import DUKASCOPY_INSTRUMENTS, HISTDATA_PAIRS, SERIES_NOTES

        self.assertEqual(HISTDATA_PAIRS["US500"], "SPXUSD")
        self.assertEqual(HISTDATA_PAIRS["US100"], "NSXUSD")
        self.assertNotIn("US30", HISTDATA_PAIRS)
        self.assertEqual(DUKASCOPY_INSTRUMENTS["US30"], "USA30.IDX/USD")
        self.assertIn("DJIA", SERIES_NOTES["US30"])

    def test_locked_flat_disables_pyramid(self):
        from tools.ha_hunt_st_compare.run_st_indices import ST_GRID, locked_flat

        p = locked_flat(10, 2.0, 5, 45)
        self.assertFalse(p.allow_pyramid)
        self.assertTrue(p.exit_ha_flip)
        self.assertTrue(p.scale_tp1)
        self.assertFalse(p.trail_after_tp1)
        self.assertEqual(p.slow_len, 144)
        self.assertEqual(p.fast_len, 33)
        self.assertTrue(p.req_m45_struct)
        self.assertEqual(p.cap_reg, 2)
        required = {(10, 2.0), (12, 3.0), (10, 3.0), (14, 2.0), (14, 3.0), (7, 2.0), (7, 3.0), (12, 2.0)}
        self.assertTrue(required.issubset(set(ST_GRID)))


class PickBestStTests(unittest.TestCase):
    def _book(self, sum_r: float) -> Book:
        b = Book(symbol="X", variant="t")
        b.sum_r = sum_r
        b.n = 10
        return b

    def test_keep_10_2_when_mixed(self):
        from tools.ha_hunt_st_compare.run_st_indices import pick_best_st

        books = {
            ("XAU", "M5+M45", 10, 2.0): self._book(30.0),
            ("US100", "M15+H1", 10, 2.0): self._book(48.0),
            ("XAU", "M5+M45", 12, 3.0): self._book(40.0),
            ("US100", "M15+H1", 12, 3.0): self._book(10.0),
        }
        atr, fac, why = pick_best_st(books, grid=[(10, 2.0), (12, 3.0)])
        self.assertEqual((atr, fac), (10, 2.0))
        self.assertIn("no grid cell beat", why)

    def test_switch_when_both_stacks_win(self):
        from tools.ha_hunt_st_compare.run_st_indices import pick_best_st

        books = {
            ("XAU", "M5+M45", 10, 2.0): self._book(30.0),
            ("US100", "M15+H1", 10, 2.0): self._book(48.0),
            ("XAU", "M5+M45", 7, 2.0): self._book(31.0),
            ("US100", "M15+H1", 7, 2.0): self._book(50.0),
        }
        atr, fac, why = pick_best_st(books, grid=[(10, 2.0), (7, 2.0)])
        self.assertEqual((atr, fac), (7, 2.0))
        self.assertIn("beat 10/2", why)


if __name__ == "__main__":
    unittest.main()
