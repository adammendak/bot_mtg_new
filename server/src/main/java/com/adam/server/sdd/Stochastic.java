package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.util.Arrays;
import java.util.List;

/**
 * Slow Stochastic ({@code %K} length / {@code %K} smooth / {@code %D} smooth),
 * matching the common TradingView {@code ta.stoch} + SMA defaults of 14, 3, 3.
 * Closed-bar only. Used as an optional H1 extreme filter for MMS entries
 * and adds, and as an optional entry-TF %K/%D cross filter.
 */
public final class Stochastic {

    private Stochastic() {
    }

    public record Series(double[] k, double[] d) {

        public boolean ready(int i) {
            return i >= 0 && i < k.length && !Double.isNaN(k[i]) && !Double.isNaN(d[i]);
        }
    }

    public static Series of(List<Candle> candles, int length, int kSmooth, int dSmooth) {
        if (candles == null || candles.isEmpty() || length <= 0 || kSmooth <= 0 || dSmooth <= 0) {
            return new Series(new double[0], new double[0]);
        }
        int n = candles.size();
        double[] raw = new double[n];
        Arrays.fill(raw, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (i < length - 1) {
                continue;
            }
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            for (int j = i - length + 1; j <= i; j++) {
                Candle c = candles.get(j);
                hh = Math.max(hh, c.high());
                ll = Math.min(ll, c.low());
            }
            double range = hh - ll;
            raw[i] = range <= 0 ? 50.0 : 100.0 * (candles.get(i).close() - ll) / range;
        }
        double[] k = AtrEnvelope.sma(raw, kSmooth);
        double[] d = AtrEnvelope.sma(k, dSmooth);
        return new Series(k, d);
    }

    /** %K crosses above %D on closed bar {@code i}. */
    public static boolean crossUp(Series st, int i) {
        return st != null && st.ready(i) && st.ready(i - 1)
                && st.k()[i - 1] <= st.d()[i - 1] && st.k()[i] > st.d()[i];
    }

    /** %K crosses below %D on closed bar {@code i}. */
    public static boolean crossDown(Series st, int i) {
        return st != null && st.ready(i) && st.ready(i - 1)
                && st.k()[i - 1] >= st.d()[i - 1] && st.k()[i] < st.d()[i];
    }
}
