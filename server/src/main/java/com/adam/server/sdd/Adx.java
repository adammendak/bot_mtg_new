package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Wilder's ADX / DI. Standard construction:
 * <pre>
 *   +DM = up-move   if it exceeds the down-move and is positive, else 0
 *   -DM = down-move  if it exceeds the up-move  and is positive, else 0
 *   ATR   = RMA(TrueRange, period)
 *   +DI   = 100 · RMA(+DM) / ATR
 *   -DI   = 100 · RMA(-DM) / ATR
 *   DX    = 100 · |+DI − −DI| / (+DI + −DI)
 *   ADX   = RMA(DX, period)
 * </pre>
 * {@code adx} is {@code NaN} until it is defined (~2·period bars). A rising ADX
 * above ~20–25 marks a genuine trend — HTS uses it to allow an early (pre-cross)
 * band entry.
 */
public final class Adx {

    public static final int PERIOD = 14;
    public static final double TREND_THRESHOLD = 20.0;

    private Adx() {
    }

    public record Point(Instant time, double adx, double plusDi, double minusDi) {

        public boolean trending() {
            return trending(TREND_THRESHOLD);
        }

        public boolean trending(double threshold) {
            return !Double.isNaN(adx) && adx >= threshold;
        }
    }

    public static List<Point> compute(List<Candle> candles) {
        return compute(candles, PERIOD);
    }

    public static List<Point> compute(List<Candle> candles, int period) {
        int n = candles.size();
        double[] tr = new double[n];
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        for (int i = 1; i < n; i++) {
            Candle c = candles.get(i);
            Candle p = candles.get(i - 1);
            double up = c.high() - p.high();
            double down = p.low() - c.low();
            plusDm[i] = (up > down && up > 0) ? up : 0;
            minusDm[i] = (down > up && down > 0) ? down : 0;
            double range = c.high() - c.low();
            tr[i] = Math.max(range, Math.max(Math.abs(c.high() - p.close()), Math.abs(c.low() - p.close())));
        }
        double[] atr = Wilder.rma(tr, period);
        double[] sPlus = Wilder.rma(plusDm, period);
        double[] sMinus = Wilder.rma(minusDm, period);

        // dx warm-up must be 0, not NaN — Wilder.rma seeds with a plain sum and
        // would otherwise propagate NaN through the whole ADX line.
        double[] dx = new double[n];
        double[] plusDi = new double[n];
        double[] minusDi = new double[n];
        java.util.Arrays.fill(plusDi, Double.NaN);
        java.util.Arrays.fill(minusDi, Double.NaN);
        int firstValid = -1;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(atr[i]) || atr[i] == 0) {
                dx[i] = 0;
                continue;
            }
            if (firstValid < 0) {
                firstValid = i;
            }
            double pdi = 100 * sPlus[i] / atr[i];
            double mdi = 100 * sMinus[i] / atr[i];
            plusDi[i] = pdi;
            minusDi[i] = mdi;
            double sum = pdi + mdi;
            dx[i] = sum == 0 ? 0 : 100 * Math.abs(pdi - mdi) / sum;
        }
        double[] adx = Wilder.rma(dx, period);
        // Not meaningful until DX itself has been smoothed for a full period.
        int adxReady = firstValid < 0 ? n : firstValid + period;
        for (int i = 0; i < Math.min(adxReady, n); i++) {
            adx[i] = Double.NaN;
        }

        List<Point> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Point(candles.get(i).time(), adx[i], plusDi[i], minusDi[i]));
        }
        return out;
    }
}
