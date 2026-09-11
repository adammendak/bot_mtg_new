"""Logic tests — indicator math and conservative same-bar exits. No market prices."""

from __future__ import annotations

import unittest
from datetime import datetime, timezone

import numpy as np

from tools.ha_hunt_st_compare.indicators import atr, rma, rma_band, supertrend
from tools.ha_hunt_st_compare.ohlc import Bars, SeriesMeta, resample_ohlc
from tools.ha_hunt_st_compare.simulator import Params, simulate
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


if __name__ == "__main__":
    unittest.main()
