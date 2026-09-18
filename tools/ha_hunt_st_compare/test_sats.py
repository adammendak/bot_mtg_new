"""SATS engine + R accounting tests. Synthetic prices only."""

from __future__ import annotations

import unittest

import numpy as np

from tools.ha_hunt_st_compare.indicators import (
    atr,
    efficiency_ratio,
    sats_adaptive_supertrend,
    tqi_series,
)
from tools.ha_hunt_st_compare.ohlc import Bars, SeriesMeta, resample_bars
from tools.ha_hunt_st_compare.sats import SatsParams, _realized_r, default_sats, simulate_sats
from tools.ha_hunt_st_compare.simulator import simulate, st_v3_prod_like
from tools.ha_hunt_st_compare.test_simulator import _synthetic_trend


class EfficiencyAndTqiTests(unittest.TestCase):
    def test_straight_line_er_is_one(self):
        c = np.linspace(100.0, 200.0, 50)
        er = efficiency_ratio(c, 10)
        self.assertGreater(er[-1], 0.999)

    def test_chop_er_is_low(self):
        c = np.array([100.0, 102.0, 100.0, 102.0, 100.0, 102.0, 100.0, 102.0, 100.0, 102.0, 100.0] * 4)
        er = efficiency_ratio(c, 10)
        self.assertLess(er[-1], 0.2)

    def test_tqi_in_unit_interval(self):
        n = 200
        c = 100 + np.cumsum(np.sin(np.linspace(0, 20, n)))
        h = c + 1.0
        l = c - 1.0
        a = atr(h, l, c, 14)
        tqi, er, vol = tqi_series(h, l, c, a)
        self.assertTrue(np.all((tqi >= 0.0) & (tqi <= 1.0)))
        self.assertEqual(len(er), n)
        self.assertTrue(np.all(vol > 0))


class SatsEngineTests(unittest.TestCase):
    def test_uptrend_ends_bullish(self):
        n = 300
        c = np.linspace(100, 180, n)
        h = c + 0.8
        l = c - 0.8
        eng = sats_adaptive_supertrend(h, l, c)
        last = eng["trend"][~np.isnan(eng["line"])][-1]
        self.assertEqual(last, 1.0)
        self.assertLess(eng["line"][~np.isnan(eng["line"])][-1], c[-1])

    def test_flip_on_hard_reversal(self):
        n = 250
        c = np.concatenate([np.linspace(100, 160, 180), np.linspace(160, 80, 70)])
        h = c + 1.0
        l = c - 1.0
        eng = sats_adaptive_supertrend(h, l, c)
        self.assertTrue(np.any(eng["flip_dn"]))


class RealizedRTests(unittest.TestCase):
    def test_tp3_is_two_r(self):
        r, reason = _realized_r(
            hit1=True, hit2=True, hit3=True, sl_hit=False, timeout=False, flip=False,
            tp1_r=1, tp2_r=2, tp3_r=3, exit_r=3,
        )
        self.assertAlmostEqual(r, 2.0)
        self.assertEqual(reason, "tp3")

    def test_pure_sl_is_minus_one(self):
        r, reason = _realized_r(
            hit1=False, hit2=False, hit3=False, sl_hit=True, timeout=False, flip=False,
            tp1_r=1, tp2_r=2, tp3_r=3, exit_r=-1,
        )
        self.assertAlmostEqual(r, -1.0)
        self.assertEqual(reason, "stop")

    def test_sl_after_tp1(self):
        r, _ = _realized_r(
            hit1=True, hit2=False, hit3=False, sl_hit=True, timeout=False, flip=False,
            tp1_r=1, tp2_r=2, tp3_r=3, exit_r=-1,
        )
        self.assertAlmostEqual(r, (1.0 / 3.0) * 1.0 + (2.0 / 3.0) * (-1.0))

    def test_timeout_marks_remaining_at_actual_r(self):
        r, reason = _realized_r(
            hit1=True, hit2=False, hit3=False, sl_hit=False, timeout=True, flip=False,
            tp1_r=1, tp2_r=2, tp3_r=3, exit_r=0.5,
        )
        self.assertAlmostEqual(r, (1.0 / 3.0) * 1.0 + (2.0 / 3.0) * 0.5)
        self.assertEqual(reason, "timeout")

    def test_sl_beats_same_bar_tp3_in_accounting(self):
        # caller must pass hit3=False when SL prints on the same bar
        r, reason = _realized_r(
            hit1=False, hit2=False, hit3=False, sl_hit=True, timeout=False, flip=False,
            tp1_r=1, tp2_r=2, tp3_r=3, exit_r=3,
        )
        self.assertEqual(reason, "stop")
        self.assertAlmostEqual(r, -1.0)


class SatsSimulateTests(unittest.TestCase):
    def test_runs_and_respects_window(self):
        bars = _synthetic_trend(2500)
        full = simulate_sats("SYN", bars, default_sats(5))
        late = simulate_sats(
            "SYN",
            bars,
            default_sats(5, trade_start="2025-09-08T00:00:00", trade_end="2025-09-09T00:00:00"),
        )
        self.assertGreaterEqual(full.n, late.n)
        for t in late.trades:
            self.assertGreaterEqual(t.entry_time, "2025-09-08T00:00:00")
            self.assertLess(t.entry_time, "2025-09-09T00:00:00")

    def test_stop_capped_and_tp_thirds_reasons(self):
        bars = _synthetic_trend(2500)
        book = simulate_sats("SYN", bars, default_sats(5))
        self.assertGreaterEqual(book.n, 0)
        for t in book.trades:
            self.assertIn(t.reason.split("char_flip_")[-1], {"stop", "tp3", "timeout", "flip_exit", "open_eod"})
            self.assertGreater(t.stop0, 0)

    def test_m15_resample_runs(self):
        m5 = _synthetic_trend(2400)
        m15 = resample_bars(m5, 15)
        book = simulate_sats("SYN", m15, default_sats(15))
        self.assertGreaterEqual(book.n, 0)


class StV3AblationHookTests(unittest.TestCase):
    def test_tqi_gate_does_not_increase_fills(self):
        m5 = _synthetic_trend(2400)
        prod = simulate("SYN", m5, st_v3_prod_like(5, 45))
        gated = simulate("SYN", m5, st_v3_prod_like(5, 45, tqi_min=0.5))
        self.assertLessEqual(gated.n, prod.n)

    def test_char_flip_instead_factory(self):
        p = st_v3_prod_like(15, 60, exit_char_flip=True, exit_band_cross=False)
        self.assertTrue(p.exit_char_flip)
        self.assertFalse(p.exit_band_cross)
        self.assertIn("cfonly", p.name)

    def test_char_flip_plus_runs(self):
        m5 = _synthetic_trend(2400)
        book = simulate("SYN", m5, st_v3_prod_like(5, 45, exit_char_flip=True, exit_band_cross=True))
        self.assertGreaterEqual(book.n, 0)


if __name__ == "__main__":
    unittest.main()
