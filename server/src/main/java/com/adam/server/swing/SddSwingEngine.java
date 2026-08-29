package com.adam.server.swing;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.HeikenAshi;
import com.adam.server.sdd.PivotPoints;
import com.adam.server.sdd.Wilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * SDD-SWING — the H1-entry / H4-context strategy, implemented analogously to
 * the SDD-M15 {@link com.adam.server.sdd.SddEngine} but on higher timeframes:
 * <ul>
 *   <li>Entry considered after an <b>H1 candle closes</b> (instead of M15).</li>
 *   <li>The <b>H4 context</b> is a directional filter, not just a note: only
 *       trade in the direction of the prevailing H4 swing.</li>
 *   <li>Same signal machinery as M15: HA flip + RMA33/133 stacked + PP.</li>
 *   <li>1R = 1× H4 ATR (wider stop — swing cadence). Stop = 2.5× H4 ATR.</li>
 * </ul>
 * Reuses the same {@link HeikenAshi}, {@link Wilder} and {@link PivotPoints}
 * building blocks as SDD-M15 so behaviour stays consistent.
 */
@Component
public final class SddSwingEngine implements SwingSignalProvider {

    public static final int RMA_FAST = 33;
    public static final int RMA_SLOW = 133;
    public static final int ATR_PERIOD = 14;
    public static final double STOP_ATR_MULT = 2.5;

    private final ZoneId zone;

    public SddSwingEngine(AppProperties properties) {
        this.zone = ZoneId.of(properties.getTimezone());
    }

    /**
     * Evaluate the swing setup for one symbol at {@code now}.
     *
     * @param h1Closed closed H1 candles up to and including the trigger bar
     * @param h4Closed closed H4 candles (context)
     * @return swing scan for the symbol, or {@code null} when no setup
     */
    @Override
    public SwingScan evaluate(SwingSymbol symbol, String epic, List<Candle> h1Closed, List<Candle> h4Closed,
                              Instant now) {
        if (h1Closed.size() < RMA_SLOW + 2) {
            return null; // insufficient H1 bars
        }
        if (h4Closed.size() < RMA_FAST) {
            return null; // insufficient H4 context
        }

        // ---- H1 trigger (analogous to M15 in SDD-M15) ----
        List<HeikenAshi.Bar> haH1 = HeikenAshi.from(h1Closed);
        HeikenAshi.Bar lastHa = haH1.getLast();
        HeikenAshi.Bar prevHa = haH1.get(haH1.size() - 2);
        boolean flip = lastHa.bullish() != prevHa.bullish();
        if (!flip) {
            return null; // no fresh H1 HA flip → no setup
        }
        Direction direction = lastHa.bullish() ? Direction.BUY : Direction.SELL;
        boolean buy = direction.bullish();

        // ---- RMA stacked on H1 (analogous to M15 RMA33/133) ----
        double[] h1Closes = Wilder.closes(h1Closed);
        double rma33 = Wilder.last(Wilder.rma(h1Closes, RMA_FAST));
        double rma133 = Wilder.last(Wilder.rma(h1Closes, RMA_SLOW));
        Candle lastH1 = h1Closed.getLast();
        if (Double.isNaN(rma33) || Double.isNaN(rma133)) {
            return null;
        }
        boolean rmaStacked = buy
                ? lastH1.close() > rma33 && rma33 > rma133
                : lastH1.close() < rma33 && rma33 < rma133;
        if (!rmaStacked) {
            return null; // H1 not stacked → no setup
        }

        // ---- PP alignment on H1 (analogous to M15 PP gate) ----
        PivotPoints.Levels levels = PivotPoints.previousCompleted(h1Closed, lastH1.time(), zone);
        if (levels == null || !PivotPoints.aligned(lastH1.close(), levels.pp(), buy)) {
            return null; // PP not aligned → no setup
        }

        // ---- H4 context bias (the swing differentiator) ----
        SwingScan.H4Trend h4Trend = h4Trend(h4Closed);
        boolean h4Agrees = switch (h4Trend) {
            case UP -> buy;
            case DOWN -> !buy;
            case FLAT -> true; // no strong context → allow either (H1 rules decide)
        };
        if (!h4Agrees) {
            return null; // H4 context against the H1 flip → no setup
        }

        // ---- Stop / target: 1R = 1× H4 ATR (wider, swing-sized) ----
        double[] atr = Wilder.atr(h4Closed, ATR_PERIOD);
        double atrH4 = Wilder.last(atr);
        if (Double.isNaN(atrH4) || atrH4 <= 0) {
            return null;
        }
        double entry = lastH1.close();
        double oneR = atrH4;
        double stop = buy ? entry - STOP_ATR_MULT * atrH4 : entry + STOP_ATR_MULT * atrH4;
        double target = buy ? entry + oneR : entry - oneR;

        return new SwingScan(
                now,
                symbol.code(),
                epic,
                direction,
                entry,
                stop,
                target,
                h4Trend
        );
    }

    /** H4 trend from HA alignment + RMA33 on H4 closes (UP / DOWN / FLAT). */
    static SwingScan.H4Trend h4Trend(List<Candle> h4Closed) {
        if (h4Closed.size() < RMA_FAST + 2) {
            return SwingScan.H4Trend.FLAT;
        }
        List<HeikenAshi.Bar> ha = HeikenAshi.from(h4Closed);
        boolean haBull = ha.getLast().bullish();
        double[] closes = Wilder.closes(h4Closed);
        double rma33 = Wilder.last(Wilder.rma(closes, RMA_FAST));
        if (Double.isNaN(rma33)) {
            return SwingScan.H4Trend.FLAT;
        }
        boolean rmaBull = h4Closed.getLast().close() > rma33;
        // Both HA and RMA33 agree → UP/DOWN; otherwise FLAT.
        if (haBull && rmaBull) {
            return SwingScan.H4Trend.UP;
        }
        if (!haBull && !rmaBull) {
            return SwingScan.H4Trend.DOWN;
        }
        return SwingScan.H4Trend.FLAT;
    }

    /** Static helper so callers can filter closed H1 candles to {@code now}. */
    static List<Candle> closedBars(List<Candle> candles, Instant now) {
        List<Candle> closed = new ArrayList<>();
        for (Candle c : candles) {
            if (!c.time().plus(java.time.Duration.ofHours(1)).isAfter(now)) {
                closed.add(c);
            }
        }
        return closed;
    }
}
