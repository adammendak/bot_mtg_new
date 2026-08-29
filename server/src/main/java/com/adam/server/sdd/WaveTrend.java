package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * WaveTrend Oscillator — the open-source LazyBear formula (TradingView,
 * CC/MPL). Deliberately <b>not</b> Market Cipher (paid, closed): only the
 * public WaveTrend is reproduced here.
 *
 * <pre>
 *   ap  = (high + low + close) / 3
 *   esa = ema(ap, n1)
 *   d   = ema(|ap - esa|, n1)
 *   ci  = (ap - esa) / (0.015 * d)
 *   wt1 = ema(ci, n2)
 *   wt2 = sma(wt1, 4)
 * </pre>
 *
 * Defaults: {@code n1 = 10}, {@code n2 = 21}. Zone thresholds:
 * overbought-extreme {@code +60}, overbought {@code +53}, oversold {@code -53},
 * oversold-extreme {@code -60}.
 */
public final class WaveTrend {

    public static final int N1 = 10;
    public static final int N2 = 21;
    public static final double OVERBOUGHT_EXTREME = 60;
    public static final double OVERBOUGHT = 53;
    public static final double OVERSOLD = -53;
    public static final double OVERSOLD_EXTREME = -60;

    private static final double D_FLOOR = 1e-10; // guard ci against d -> 0

    private WaveTrend() {
    }

    public record Point(Instant time, double wt1, double wt2) {
    }

    public static List<Point> compute(List<Candle> candles) {
        return compute(candles, N1, N2);
    }

    public static List<Point> compute(List<Candle> candles, int n1, int n2) {
        int n = candles.size();
        double[] ap = new double[n];
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            ap[i] = (c.high() + c.low() + c.close()) / 3.0;
        }
        double[] esa = Ema.of(ap, n1);
        double[] absd = new double[n];
        for (int i = 0; i < n; i++) {
            absd[i] = Math.abs(ap[i] - esa[i]);
        }
        double[] d = Ema.of(absd, n1);
        double[] ci = new double[n];
        for (int i = 0; i < n; i++) {
            double denom = 0.015 * Math.max(d[i], D_FLOOR);
            ci[i] = (ap[i] - esa[i]) / denom;
        }
        double[] wt1 = Ema.of(ci, n2);
        double[] wt2 = Sma.of(wt1, 4);

        List<Point> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Point(candles.get(i).time(), wt1[i], wt2[i]));
        }
        return out;
    }
}
