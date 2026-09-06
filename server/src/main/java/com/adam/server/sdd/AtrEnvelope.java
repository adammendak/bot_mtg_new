package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.util.Arrays;
import java.util.List;

/**
 * Closed-bar ATR envelope used by the MastermindZX MMS mean-reversion variant.
 * Either a triangular-MA centre ({@link Mode#TMA_ATR}, default) or a simple-MA
 * centre ({@link Mode#BB_ATR}) with bands at {@code centre ± multiplier × ATR}.
 *
 * <p>TMA is SMA-of-SMA (the common non-repainting triangular average). Both
 * modes use Wilder ATR of the same period. No lookahead: index {@code i} uses
 * only bars {@code 0..i}.
 */
public final class AtrEnvelope {

    public enum Mode { TMA_ATR, BB_ATR }

    private AtrEnvelope() {
    }

    public record Series(double[] centre, double[] upper, double[] lower) {

        public boolean ready(int i) {
            return i >= 0 && i < centre.length
                    && !Double.isNaN(centre[i]) && !Double.isNaN(upper[i]) && !Double.isNaN(lower[i]);
        }
    }

    public static Series of(List<Candle> candles, int period, double atrMult, Mode mode) {
        if (candles == null || candles.isEmpty() || period <= 0 || atrMult <= 0) {
            return new Series(new double[0], new double[0], new double[0]);
        }
        double[] close = Wilder.closes(candles);
        double[] mid = mode == Mode.BB_ATR ? sma(close, period) : tma(close, period);
        double[] atr = Wilder.atr(candles, period);
        double[] upper = new double[close.length];
        double[] lower = new double[close.length];
        Arrays.fill(upper, Double.NaN);
        Arrays.fill(lower, Double.NaN);
        for (int i = 0; i < close.length; i++) {
            if (Double.isNaN(mid[i]) || Double.isNaN(atr[i])) {
                continue;
            }
            upper[i] = mid[i] + atrMult * atr[i];
            lower[i] = mid[i] - atrMult * atr[i];
        }
        return new Series(mid, upper, lower);
    }

    /** Triangular MA: {@code SMA(SMA(src, n), n)}. Defined from index {@code 2n-2}. */
    public static double[] tma(double[] src, int period) {
        return sma(sma(src, period), period);
    }

    /**
     * Rolling mean. A window that still contains {@code NaN} (e.g. the second
     * SMA of a TMA) stays {@code NaN} — it does not poison later values.
     */
    public static double[] sma(double[] src, int period) {
        double[] out = new double[src.length];
        Arrays.fill(out, Double.NaN);
        if (period <= 0 || src.length < period) {
            return out;
        }
        for (int i = period - 1; i < src.length; i++) {
            double sum = 0;
            boolean ok = true;
            for (int j = i - period + 1; j <= i; j++) {
                double v = src[j];
                if (Double.isNaN(v)) {
                    ok = false;
                    break;
                }
                sum += v;
            }
            if (ok) {
                out[i] = sum / period;
            }
        }
        return out;
    }
}
