package com.adam.server.swing;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.HeikenAshi;
import com.adam.server.sdd.PivotPoints;
import com.adam.server.sdd.Supertrend;
import com.adam.server.sdd.WaveTrend;
import com.adam.server.sdd.Wilder;

import java.time.ZoneId;
import java.util.List;

/**
 * The market snapshot around an SDD-SWING (H1) signal — both timeframes, the
 * same indicator stack the trader watches on Capital.com — so the notification
 * reads like an analyst note instead of a field dump. Purely descriptive: the
 * WaveTrend / Supertrend readings here do not gate the signal (the engine still
 * decides), they colour it for the human.
 */
public record SwingSignalContext(
        // ---- H4 (HTF context) ----
        String h4Trend,          // "UP (HA + RMA33 aligned)" ...
        double h4Wt1,
        double h4Wt2,
        String h4WtZone,         // oversold extreme / oversold / neutral / overbought / overbought extreme
        double h4Atr,
        String h4VsPivot,        // "above PP by 0.6x ATR" / "no PP (BTC)" ...
        // ---- H1 (LTF / execution) ----
        int h1SupertrendTrend,   // +1 up / -1 down / 0 warm-up
        double h1SupertrendLine,
        double h1Rma33,
        double h1Rma133,
        String h1RmaStack,       // "stacked long (close > RMA33 > RMA133)" ...
        double h1Wt1,
        // ---- trade geometry ----
        double stopDistancePrice,
        double stopDistanceAtrH4,
        double stopDistancePct,
        double riskReward) {

    public static SwingSignalContext from(List<Candle> h1, List<Candle> h4, SwingScan s, ZoneId zone) {
        boolean buy = s.direction() == Direction.BUY;

        List<WaveTrend.Point> h4Wt = WaveTrend.compute(h4);
        double h4wt1 = h4Wt.isEmpty() ? Double.NaN : h4Wt.getLast().wt1();
        double h4wt2 = h4Wt.isEmpty() ? Double.NaN : h4Wt.getLast().wt2();
        double h4atr = Wilder.last(Wilder.atr(h4, SddSwingEngine.ATR_PERIOD));

        List<Supertrend.Point> h1St = Supertrend.compute(h1);
        Supertrend.Point stLast = h1St.isEmpty() ? null : h1St.getLast();
        double[] h1Closes = Wilder.closes(h1);
        double rma33 = Wilder.last(Wilder.rma(h1Closes, SddSwingEngine.RMA_FAST));
        double rma133 = Wilder.last(Wilder.rma(h1Closes, SddSwingEngine.RMA_SLOW));
        List<WaveTrend.Point> h1Wt = WaveTrend.compute(h1);
        double h1wt1 = h1Wt.isEmpty() ? Double.NaN : h1Wt.getLast().wt1();

        double close = s.entry();

        String h4VsPivot = "n/a";
        try {
            PivotPoints.Levels pp = PivotPoints.previousCompleted(h4, h4.getLast().time(), zone);
            if (pp != null && h4atr > 0) {
                double dist = (close - pp.pp()) / h4atr;
                h4VsPivot = String.format("%s PP by %.2fx ATR", dist >= 0 ? "above" : "below", Math.abs(dist));
            } else if (pp != null) {
                h4VsPivot = close >= pp.pp() ? "above PP" : "below PP";
            }
        } catch (RuntimeException ignored) {
            // BTC / insufficient sessions -> "n/a"
        }

        double stopDist = Math.abs(s.entry() - s.stopLevel());
        double targetDist = Math.abs(s.targetLevel() - s.entry());
        double stopAtr = h4atr > 0 ? stopDist / h4atr : Double.NaN;
        double stopPct = close != 0 ? stopDist / Math.abs(close) * 100.0 : Double.NaN;
        double rr = stopDist > 0 ? targetDist / stopDist : Double.NaN;

        return new SwingSignalContext(
                trendLabel(h4, buy),
                h4wt1, h4wt2, zone(h4wt1), h4atr, h4VsPivot,
                stLast == null ? 0 : stLast.trend(),
                stLast == null ? Double.NaN : stLast.line(),
                rma33, rma133, rmaStackLabel(close, rma33, rma133, buy), h1wt1,
                stopDist, stopAtr, stopPct, rr);
    }

    private static String zone(double wt1) {
        if (Double.isNaN(wt1)) {
            return "n/a";
        }
        if (wt1 >= WaveTrend.OVERBOUGHT_EXTREME) {
            return "overbought extreme";
        }
        if (wt1 >= WaveTrend.OVERBOUGHT) {
            return "overbought";
        }
        if (wt1 <= WaveTrend.OVERSOLD_EXTREME) {
            return "oversold extreme";
        }
        if (wt1 <= WaveTrend.OVERSOLD) {
            return "oversold";
        }
        return "neutral";
    }

    private static String trendLabel(List<Candle> h4, boolean buy) {
        SwingScan.H4Trend t = SddSwingEngine.h4Trend(h4);
        if (t == SwingScan.H4Trend.FLAT) {
            return "FLAT (HA / RMA33 disagree)";
        }
        boolean withTrade = (t == SwingScan.H4Trend.UP) == buy;
        return t + (withTrade ? " (with the trade)" : " (against the trade)");
    }

    private static String rmaStackLabel(double close, double rma33, double rma133, boolean buy) {
        if (Double.isNaN(rma33) || Double.isNaN(rma133)) {
            return "n/a";
        }
        boolean stacked = buy
                ? close > rma33 && rma33 > rma133
                : close < rma33 && rma33 < rma133;
        if (stacked) {
            return buy ? "stacked long (close > RMA33 > RMA133)" : "stacked short (close < RMA33 < RMA133)";
        }
        return "not stacked";
    }
}
