package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Supertrend — an ATR trailing-band trend flip, matching the Capital.com
 * indicator settings: ATR period 10, source {@code (high + low) / 2}, ATR
 * multiplier 3, Wilder-RMA ATR (not SMA of true range), so it reuses
 * {@link Wilder#atr}.
 *
 * <pre>
 *   src = (high + low) / 2
 *   up  = src - mult * atr
 *   dn  = src + mult * atr
 *   finalUp[i] = close[i-1] &gt; finalUp[i-1] ? max(up[i], finalUp[i-1]) : up[i]
 *   finalDn[i] = close[i-1] &lt; finalDn[i-1] ? min(dn[i], finalDn[i-1]) : dn[i]
 *   trend[i]   = trend[i-1] == -1 &amp;&amp; close[i] &gt; finalDn[i-1] ?  1
 *              : trend[i-1] ==  1 &amp;&amp; close[i] &lt; finalUp[i-1] ? -1
 *              : trend[i-1]
 *   line[i]    = trend[i] == 1 ? finalUp[i] : finalDn[i]
 * </pre>
 *
 * {@code trend} is {@code +1} (up / line below price) or {@code -1} (down / line
 * above price); {@code 0} on the warm-up bars before the ATR is defined.
 */
public final class Supertrend {

    public static final int ATR_PERIOD = 10;
    public static final double MULT = 3.0;

    private Supertrend() {
    }

    public record Point(Instant time, int trend, double line, boolean flipUp, boolean flipDown) {
    }

    public static List<Point> compute(List<Candle> candles) {
        return compute(candles, ATR_PERIOD, MULT);
    }

    public static List<Point> compute(List<Candle> candles, int atrPeriod, double mult) {
        int n = candles.size();
        List<Point> out = new ArrayList<>(n);
        double[] atr = Wilder.atr(candles, atrPeriod);

        double prevFinalUp = Double.NaN;
        double prevFinalDn = Double.NaN;
        int prevTrend = 0;
        double prevClose = Double.NaN;

        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            double a = i < atr.length ? atr[i] : Double.NaN;
            if (Double.isNaN(a)) {
                out.add(new Point(c.time(), 0, Double.NaN, false, false));
                prevClose = c.close();
                continue;
            }
            double src = (c.high() + c.low()) / 2.0;
            double up = src - mult * a;
            double dn = src + mult * a;

            double finalUp;
            double finalDn;
            if (Double.isNaN(prevFinalUp)) {
                // first ATR-defined bar: seed the bands, start trend up.
                finalUp = up;
                finalDn = dn;
            } else {
                finalUp = prevClose > prevFinalUp ? Math.max(up, prevFinalUp) : up;
                finalDn = prevClose < prevFinalDn ? Math.min(dn, prevFinalDn) : dn;
            }

            int trend;
            if (prevTrend == 0) {
                trend = 1;
            } else if (prevTrend == -1 && c.close() > prevFinalDn) {
                trend = 1;
            } else if (prevTrend == 1 && c.close() < prevFinalUp) {
                trend = -1;
            } else {
                trend = prevTrend;
            }

            boolean flipUp = prevTrend == -1 && trend == 1;
            boolean flipDown = prevTrend == 1 && trend == -1;
            double line = trend == 1 ? finalUp : finalDn;
            out.add(new Point(c.time(), trend, line, flipUp, flipDown));

            prevFinalUp = finalUp;
            prevFinalDn = finalDn;
            prevTrend = trend;
            prevClose = c.close();
        }
        return out;
    }
}
