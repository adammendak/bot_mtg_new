package com.adam.server.sdd;

import java.util.Arrays;

/**
 * Simple moving average over the last {@code period} samples. {@code NaN} until
 * {@code period} samples are available. Used for the WaveTrend signal line
 * ({@code wt2 = sma(wt1, 4)}).
 */
public final class Sma {

    private Sma() {
    }

    public static double[] of(double[] source, int period) {
        double[] out = new double[source.length];
        Arrays.fill(out, Double.NaN);
        if (period <= 0 || source.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < source.length; i++) {
            sum += source[i];
            if (i >= period) {
                sum -= source[i - period];
            }
            if (i >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }
}
