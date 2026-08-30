package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.Band;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * HTS ("wstęgi") live signal engine — the real-time sibling of
 * {@link HtsBacktestService}. Evaluates the latest closed execution-timeframe
 * bar against the settled default configuration (see {@code HTS-ROADMAP.md}):
 *
 * <ul>
 *   <li>fast RMA band (high/low, {@value #FAST_LEN}) fully clear of the slow band
 *       ({@value #SLOW_LEN}) on both the execution TF and the HTF;</li>
 *   <li>price pulled back into the fast band within the last
 *       {@value #PULLBACK_BARS} bars, then the current body closes back beyond
 *       the fast-band edge;</li>
 *   <li>no entry while the bands are consolidating (separation &lt;
 *       {@value #CONSOLIDATION_SEP} × slow-band width, or the slow band not
 *       sloping with the trade);</li>
 *   <li>stop = far edge of the fast band pushed out by
 *       {@value #STOP_BUFFER_FRAC} × fast-band width; TP1 target =
 *       {@value #RR} × stop distance.</li>
 * </ul>
 *
 * ADX is left off by default here (the backtest showed the hard gate hurts and
 * the permit adds nothing over the consolidation filter).
 */
@Component
public class HtsEngine {

    public static final int FAST_LEN = 33;
    public static final int SLOW_LEN = 144;
    public static final int PULLBACK_BARS = 10;
    public static final int SLOPE_BARS = 20;
    public static final double CONSOLIDATION_SEP = 0.25;
    public static final double STOP_BUFFER_FRAC = 0.25;
    public static final double RR = 2.0;

    /**
     * @param variant   the timeframe model this evaluation belongs to (tags the signal)
     * @param ltfClosed closed execution-timeframe candles up to and including the trigger bar
     * @param htfClosed closed higher-timeframe candles (context)
     * @return the HTS signal for this symbol, or {@code null} when there is no setup
     */
    public HtsScan evaluate(HtsVariant variant, String code, String epic,
                            List<Candle> ltfClosed, List<Candle> htfClosed, Instant now) {
        if (ltfClosed == null || htfClosed == null) {
            return null;
        }
        int need = SLOW_LEN + PULLBACK_BARS + SLOPE_BARS + 2;
        if (ltfClosed.size() < need || htfClosed.size() < SLOW_LEN + 2) {
            return null;
        }
        Band.Series lFast = Band.rma(ltfClosed, FAST_LEN);
        Band.Series lSlow = Band.rma(ltfClosed, SLOW_LEN);
        Band.Series hFast = Band.rma(htfClosed, FAST_LEN);
        Band.Series hSlow = Band.rma(htfClosed, SLOW_LEN);

        int i = ltfClosed.size() - 1;
        int h = htfClosed.size() - 1;
        Candle bar = ltfClosed.get(i);

        int dir = Band.entryDir(bar.close(), lFast, lSlow, hFast, hSlow, i, h);
        if (dir == 0) {
            return null;
        }
        boolean buy = dir > 0;
        if (consolidating(lSlow, buy, i) || bandsTooClose(lFast, lSlow, buy, i)) {
            return null;
        }
        if (!pulledBack(ltfClosed, lFast, buy, i)) {
            return null;
        }

        double bandW = Math.max(0, lFast.upper()[i] - lFast.lower()[i]);
        double buf = STOP_BUFFER_FRAC * bandW;
        double entry = bar.close();
        double stop = buy ? lFast.lower()[i] - buf : lFast.upper()[i] + buf;
        if (buy ? stop >= entry : stop <= entry) {
            return null;
        }
        double stopDist = Math.abs(entry - stop);
        double target = buy ? entry + RR * stopDist : entry - RR * stopDist;
        boolean htfUp = hFast.lower()[h] > hSlow.upper()[h];

        return new HtsScan(variant, now, code, epic, buy ? Direction.BUY : Direction.SELL,
                entry, stop, target, htfUp);
    }

    private static boolean bandsTooClose(Band.Series fast, Band.Series slow, boolean buy, int i) {
        double slowWidth = slow.upper()[i] - slow.lower()[i];
        double sep = buy ? fast.lower()[i] - slow.upper()[i] : slow.lower()[i] - fast.upper()[i];
        return slowWidth <= 0 || sep < CONSOLIDATION_SEP * slowWidth;
    }

    /** Slow band must slope with the trade over the last {@link #SLOPE_BARS} bars. */
    private static boolean consolidating(Band.Series slow, boolean buy, int i) {
        if (!slow.ready(i) || !slow.ready(i - SLOPE_BARS)) {
            return true;
        }
        double mid = (slow.upper()[i] + slow.lower()[i]) / 2.0;
        double midPrev = (slow.upper()[i - SLOPE_BARS] + slow.lower()[i - SLOPE_BARS]) / 2.0;
        return buy ? mid <= midPrev : mid >= midPrev;
    }

    private static boolean pulledBack(List<Candle> ltf, Band.Series fast, boolean buy, int i) {
        for (int k = Math.max(0, i - PULLBACK_BARS); k < i; k++) {
            if (!fast.ready(k)) {
                continue;
            }
            if (buy ? ltf.get(k).low() <= fast.upper()[k] : ltf.get(k).high() >= fast.lower()[k]) {
                return true;
            }
        }
        return false;
    }
}
