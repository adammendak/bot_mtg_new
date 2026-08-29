package com.adam.server.sdd;

import java.util.Arrays;

/**
 * Exponential moving average, TradingView {@code ta.ema} semantics: the series is
 * seeded with the first source value, then
 * {@code ema[i] = alpha * src[i] + (1 - alpha) * ema[i-1]} with
 * {@code alpha = 2 / (period + 1)}. Matches the {@code ema()} calls in the
 * open-source WaveTrend Oscillator (LazyBear).
 */
public final class Ema {

    private Ema() {
    }

    public static double[] of(double[] source, int period) {
        double[] out = new double[source.length];
        Arrays.fill(out, Double.NaN);
        if (period <= 0 || source.length == 0) {
            return out;
        }
        double alpha = 2.0 / (period + 1);
        double prev = Double.NaN;
        for (int i = 0; i < source.length; i++) {
            double v = source[i];
            if (Double.isNaN(v)) {
                out[i] = prev; // carry the last value across a gap
                continue;
            }
            prev = Double.isNaN(prev) ? v : alpha * v + (1 - alpha) * prev;
            out[i] = prev;
        }
        return out;
    }
}
