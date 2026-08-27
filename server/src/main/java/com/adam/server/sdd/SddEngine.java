package com.adam.server.sdd;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * SDD-M15 gating. Full stack on a newly closed M15: HA flip + M15 RMA with + H1 with
 * (HA or RMA stacked). PP required except BTC. H4 is a regime note, not an AND filter.
 * H1-supporting (close vs RMA33) is log-only.
 */
public class SddEngine {

    public static final int RMA_FAST = 33;
    public static final int RMA_SLOW = 133;
    public static final int ATR_PERIOD = 14;
    public static final double STOP_ATR_MULT = 2.5;
    public static final Duration M15 = Duration.ofMinutes(15);

    private final ZoneId zone;

    public SddEngine(ZoneId zone) {
        this.zone = zone;
    }

    public SddScan evaluate(
            SddSymbol symbol,
            String epic,
            List<Candle> m15Raw,
            List<Candle> h1Raw,
            List<Candle> h4Raw,
            Instant now
    ) {
        List<Candle> m15 = closedBars(m15Raw, now, M15);
        List<Candle> h1 = closedBars(h1Raw, now, Duration.ofHours(1));
        List<Candle> h4 = closedBars(h4Raw, now, Duration.ofHours(4));

        List<String> failed = new ArrayList<>();
        if (m15.size() < RMA_SLOW + 2) {
            failed.add("insufficient_m15");
        }
        if (h1.size() < RMA_SLOW) {
            failed.add("insufficient_h1");
        }
        if (!failed.isEmpty()) {
            return empty(symbol, epic, now, failed, "insufficient bars");
        }

        List<HeikenAshi.Bar> haM15 = HeikenAshi.from(m15);
        HeikenAshi.Bar lastHa = haM15.getLast();
        HeikenAshi.Bar prevHa = haM15.get(haM15.size() - 2);
        boolean flip = lastHa.bullish() != prevHa.bullish();
        Direction direction = lastHa.bullish() ? Direction.BUY : Direction.SELL;
        boolean buy = direction.bullish();

        Candle last = m15.getLast();
        double[] closes = Wilder.closes(m15);
        double rma33 = Wilder.last(Wilder.rma(closes, RMA_FAST));
        double rma133 = Wilder.last(Wilder.rma(closes, RMA_SLOW));
        boolean rmaWith = stacked(last.close(), rma33, rma133, buy);

        List<HeikenAshi.Bar> haH1 = HeikenAshi.from(h1);
        HeikenAshi.Bar lastH1Ha = haH1.getLast();
        double[] h1Closes = Wilder.closes(h1);
        double h1Rma33 = Wilder.last(Wilder.rma(h1Closes, RMA_FAST));
        double h1Rma133 = Wilder.last(Wilder.rma(h1Closes, RMA_SLOW));
        Candle lastH1 = h1.getLast();
        boolean h1Ha = lastH1Ha.bullish() == buy;
        boolean h1Rma = stacked(lastH1.close(), h1Rma33, h1Rma133, buy);
        boolean h1With = h1Ha || h1Rma;
        boolean h1Supporting = buy ? lastH1.close() > h1Rma33 : lastH1.close() < h1Rma33;

        boolean ppOk;
        String ppNote;
        if (symbol.skipPivot()) {
            ppOk = true;
            ppNote = "pp skipped (BTC)";
        } else {
            PivotPoints.Levels levels = PivotPoints.previousCompleted(m15, last.time(), zone);
            if (levels == null) {
                ppOk = false;
                ppNote = "pp unavailable";
                failed.add("pp");
            } else {
                ppOk = PivotPoints.aligned(last.close(), levels.pp(), buy);
                ppNote = "pp=" + levels.pp();
                if (!ppOk) {
                    failed.add("pp");
                }
            }
        }

        if (!flip) {
            failed.add("ha");
        }
        if (!rmaWith) {
            failed.add("rma");
        }
        if (!h1With) {
            failed.add("h1");
        }

        double[] atr = Wilder.atr(h1, ATR_PERIOD);
        double atrH1 = Wilder.last(atr);
        double entry = last.close();
        double oneR = atrH1;
        double stop = buy ? entry - STOP_ATR_MULT * atrH1 : entry + STOP_ATR_MULT * atrH1;

        boolean fullStack = flip && rmaWith && h1With && ppOk;
        boolean newBar = true;
        boolean actionable = fullStack;

        String h4Note = h4Note(h4, buy);
        String reason;
        if (fullStack) {
            reason = "full stack " + direction + " " + ppNote;
        } else if (flip) {
            reason = "HA flip without full stack: " + String.join(",", failed) + " " + ppNote;
        } else {
            reason = "no HA flip " + ppNote;
        }

        return new SddScan(
                now,
                symbol.code(),
                epic,
                direction,
                new SddScan.Setup(flip, rmaWith, h1With, ppOk),
                stop,
                oneR,
                atrH1,
                entry,
                actionable,
                reason,
                List.copyOf(failed),
                newBar,
                flip,
                fullStack,
                h4Note + "; h1Supporting=" + h1Supporting,
                h1Supporting
        );
    }

    static boolean stacked(double close, double rmaFast, double rmaSlow, boolean buy) {
        if (Double.isNaN(rmaFast) || Double.isNaN(rmaSlow)) {
            return false;
        }
        if (buy) {
            return close > rmaFast && rmaFast > rmaSlow;
        }
        return close < rmaFast && rmaFast < rmaSlow;
    }

    static List<Candle> closedBars(List<Candle> candles, Instant now, Duration size) {
        List<Candle> closed = new ArrayList<>();
        for (Candle c : candles) {
            if (!c.time().plus(size).isAfter(now)) {
                closed.add(c);
            }
        }
        return closed;
    }

    private static String h4Note(List<Candle> h4, boolean buy) {
        if (h4.size() < RMA_FAST) {
            return "H4: insufficient";
        }
        List<HeikenAshi.Bar> ha = HeikenAshi.from(h4);
        boolean haAlign = ha.getLast().bullish() == buy;
        double[] closes = Wilder.closes(h4);
        double rma33 = Wilder.last(Wilder.rma(closes, RMA_FAST));
        boolean rmaAlign = buy ? h4.getLast().close() > rma33 : h4.getLast().close() < rma33;
        return "H4 HA " + (haAlign ? "aligned" : "against") + ", RMA33 " + (rmaAlign ? "aligned" : "against");
    }

    private static SddScan empty(SddSymbol symbol, String epic, Instant now, List<String> failed, String reason) {
        return new SddScan(
                now,
                symbol.code(),
                epic,
                Direction.BUY,
                new SddScan.Setup(false, false, false, false),
                0, 0, 0, 0,
                false,
                reason,
                List.copyOf(failed),
                false,
                false,
                false,
                "",
                false
        );
    }
}
