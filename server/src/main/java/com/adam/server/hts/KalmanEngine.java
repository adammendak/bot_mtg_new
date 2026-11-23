package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.Kalman;
import com.adam.server.sdd.Wilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Live engine for {@link HtsVariant.Strategy#KALMAN} ({@link HtsVariant#KAL_H4}):
 * BigBeluga's Kalman Trend Levels as a stop-and-reverse system on closed H4
 * candles. Two Kalman filters ({@link Kalman}) run on the close — short 18,
 * long 144 — and the trend is UP while the short one sits above the long one.
 * Every crossover is an entry in the new direction (and, in
 * {@link HtsTradeService}, an exit of the opposite position).
 *
 * <p>Signal bar = the closed H4 candle on which the crossover happened; the
 * order goes out at market right after (the backtest fills at the next open).
 * A flip still counts for {@link #MAX_AGE_BARS} closed bars after it
 * happened, so a scan that was skipped (restart, broker hiccup, closed
 * market) still catches it — the gate's per-bar dedupe + {@code
 * alreadyExecuted} stop it from ever firing twice for the same flip.
 *
 * <p>The protective stop is NOT part of the backtest strategy (pure S&amp;R is
 * always in the market): it is a disaster stop at {@link #STOP_ATR_MULT}×ATR14
 * that the exit logic never relies on, and that sizes the position (1R = the
 * stop distance). A 3×ATR stop was tested against the plain S&amp;R and did
 * not change the edge.
 *
 * <p>Evidence (H4, Capital.com data, costs included, realised trades only):
 * 18/144 is <b>not</b> a proven edge — last 12&nbsp;months pooled over
 * US100/XAU/BTC/GER40/EURUSD was −28.6&nbsp;% (PF 0.87), while the 33/144
 * pairing was +34.2&nbsp;% (PF 1.26). This bot exists as an explicit
 * forward test of the requested 18/144 configuration on the {@code swing}
 * demo book.
 */
@Component
public class KalmanEngine {

    private static final Logger log = LoggerFactory.getLogger(KalmanEngine.class);

    static final int SHORT_LEN = 18;
    static final int LONG_LEN = 144;
    static final int ATR_LEN = 14;
    static final double STOP_ATR_MULT = 3.0;
    /** A flip older than this many closed bars is stale — do not chase it. */
    static final int MAX_AGE_BARS = 2;
    /** Bars needed before the long filter has forgotten its seed (about 3x its time constant). */
    static final int MIN_BARS = LONG_LEN * 3;

    /**
     * Trend of the last closed bar: +1 short filter above long, −1 below,
     * 0 when there is not enough history or the filters coincide.
     */
    public int trend(List<Candle> closed) {
        return trendAt(filters(closed), closed == null ? 0 : closed.size() - 1);
    }

    /**
     * @param closed closed H4 candles, ascending (drop the still-forming bar before calling)
     * @return the entry for the most recent crossover (≤ {@link #MAX_AGE_BARS} closed bars old), or null
     */
    public HtsScan evaluate(HtsVariant v, String code, String epic, List<Candle> closed) {
        if (v.strategy() != HtsVariant.Strategy.KALMAN || !v.universe().contains(code)) {
            return null;
        }
        if (closed == null || closed.size() < MIN_BARS) {
            return null;
        }
        double[][] k = filters(closed);
        int last = closed.size() - 1;
        int flipBar = -1;
        for (int i = last; i > last - MAX_AGE_BARS && i >= 1; i--) {
            int now = trendAt(k, i);
            int prev = trendAt(k, i - 1);
            if (now != 0 && prev != 0 && now != prev) {
                flipBar = i;
                break;
            }
        }
        if (flipBar < 0) {
            return null;
        }
        int dir = trendAt(k, flipBar);
        if (trendAt(k, last) != dir) {
            return null; // flipped back since — the later flip (if any) is the signal
        }
        double atr = Wilder.last(Wilder.atr(closed, ATR_LEN));
        double close = closed.get(last).close();
        if (Double.isNaN(atr) || atr <= 0 || close <= 0) {
            return null;
        }
        boolean buy = dir > 0;
        double dist = STOP_ATR_MULT * atr;
        double stop = buy ? close - dist : close + dist;
        if (stop <= 0) {
            return null;
        }
        double target = buy ? close + HtsEngine.RR * dist : close - HtsEngine.RR * dist;
        log.info("KALMAN [{}] {} {} flip on bar {} ({} bar(s) ago), entry {} stop {}",
                v.name(), code, buy ? "LONG" : "SHORT", closed.get(flipBar).time(), last - flipBar,
                round(close), round(stop));
        return new HtsScan(v, closed.get(flipBar).time(), code, epic,
                buy ? Direction.BUY : Direction.SELL, close, stop, target, buy);
    }

    /**
     * True when the last closed bar's trend is against the open position
     * ({@code buy} = long): the S&amp;R exit.
     */
    public boolean exitSignal(List<Candle> closed, boolean buy) {
        if (closed == null || closed.size() < MIN_BARS) {
            return false;
        }
        int t = trend(closed);
        return buy ? t < 0 : t > 0;
    }

    private static double[][] filters(List<Candle> closed) {
        if (closed == null || closed.isEmpty()) {
            return new double[2][0];
        }
        double[] c = Wilder.closes(closed);
        return new double[][]{Kalman.filter(c, SHORT_LEN), Kalman.filter(c, LONG_LEN)};
    }

    private static int trendAt(double[][] k, int i) {
        if (i < 0 || k[0].length <= i) {
            return 0;
        }
        double s = k[0][i];
        double l = k[1][i];
        if (Double.isNaN(s) || Double.isNaN(l) || s == l) {
            return 0;
        }
        return s > l ? 1 : -1;
    }

    private static double round(double x) {
        return Math.round(x * 100000.0) / 100000.0;
    }
}
