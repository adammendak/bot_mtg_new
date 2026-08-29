package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.util.List;

/**
 * HTS-style RMA <b>band</b> (ribbon): the zone bounded by the RMA of the highs
 * and the RMA of the lows — a channel around price. Matches the TradingView
 * "HTS Ribbon" setting (method RMA, high/low, fast 33 / slow 144).
 * {@code upper[i] = rma(high, n)}, {@code lower[i] = rma(low, n)}; {@code NaN}
 * until the RMA is defined.
 */
public final class Band {

    private Band() {
    }

    public record Series(double[] upper, double[] lower) {

        public boolean ready(int i) {
            return i >= 0 && i < upper.length && !Double.isNaN(upper[i]) && !Double.isNaN(lower[i]);
        }
    }

    /**
     * HTS band trend-follow entry at LTF bar {@code i}: body of the close beyond
     * the fast band, fast band fully clear of the slow band, and the HTF band in
     * the same trend (at {@code htfIdx}). Returns {@code +1} long / {@code -1}
     * short / {@code 0} no signal.
     */
    public static int entryDir(double close, Series fast, Series slow, Series htfFast, Series htfSlow,
                               int i, int htfIdx) {
        if (!fast.ready(i) || !slow.ready(i) || !htfFast.ready(htfIdx) || !htfSlow.ready(htfIdx)) {
            return 0;
        }
        boolean ltfUp = close > fast.upper[i] && fast.lower[i] > slow.upper[i];
        boolean ltfDn = close < fast.lower[i] && fast.upper[i] < slow.lower[i];
        boolean htfUp = htfFast.lower[htfIdx] > htfSlow.upper[htfIdx];
        boolean htfDn = htfFast.upper[htfIdx] < htfSlow.lower[htfIdx];
        if (ltfUp && htfUp) {
            return 1;
        }
        if (ltfDn && htfDn) {
            return -1;
        }
        return 0;
    }

    /** Runner exit: the whole close body is on the losing side of the slow band. */
    public static boolean bodyBeyondSlow(double close, Series slow, int i, boolean buy) {
        if (!slow.ready(i)) {
            return false;
        }
        return buy ? close < slow.lower[i] : close > slow.upper[i];
    }

    public static Series rma(List<Candle> candles, int period) {
        int n = candles.size();
        double[] highs = new double[n];
        double[] lows = new double[n];
        for (int i = 0; i < n; i++) {
            highs[i] = candles.get(i).high();
            lows[i] = candles.get(i).low();
        }
        double[] rh = Wilder.rma(highs, period);
        double[] rl = Wilder.rma(lows, period);
        double[] upper = new double[n];
        double[] lower = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(rh[i]) || Double.isNaN(rl[i])) {
                upper[i] = Double.NaN;
                lower[i] = Double.NaN;
            } else {
                upper[i] = Math.max(rh[i], rl[i]);
                lower[i] = Math.min(rh[i], rl[i]);
            }
        }
        return new Series(upper, lower);
    }
}
