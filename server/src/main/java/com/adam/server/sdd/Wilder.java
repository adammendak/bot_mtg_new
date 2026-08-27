package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.util.Arrays;
import java.util.List;

/**
 * Wilder smoothing (RMA) and Wilder ATR. First value is SMA; then
 * {@code (prev * (n-1) + current) / n}.
 */
public final class Wilder {

    private Wilder() {
    }

    public static double[] rma(double[] source, int period) {
        double[] out = new double[source.length];
        Arrays.fill(out, Double.NaN);
        if (period <= 0 || source.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += source[i];
        }
        out[period - 1] = sum / period;
        for (int i = period; i < source.length; i++) {
            out[i] = (out[i - 1] * (period - 1) + source[i]) / period;
        }
        return out;
    }

    public static double[] closes(List<Candle> candles) {
        double[] c = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            c[i] = candles.get(i).close();
        }
        return c;
    }

    public static double[] atr(List<Candle> candles, int period) {
        if (candles.isEmpty()) {
            return new double[0];
        }
        double[] tr = new double[candles.size()];
        Candle first = candles.getFirst();
        tr[0] = first.high() - first.low();
        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double range = c.high() - c.low();
            double highClose = Math.abs(c.high() - prev.close());
            double lowClose = Math.abs(c.low() - prev.close());
            tr[i] = Math.max(range, Math.max(highClose, lowClose));
        }
        return rma(tr, period);
    }

    public static double last(double[] values) {
        for (int i = values.length - 1; i >= 0; i--) {
            if (!Double.isNaN(values[i])) {
                return values[i];
            }
        }
        return Double.NaN;
    }
}
