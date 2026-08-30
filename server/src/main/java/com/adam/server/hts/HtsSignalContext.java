package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.Adx;
import com.adam.server.sdd.Band;
import com.adam.server.sdd.PivotPoints;
import com.adam.server.sdd.Wilder;

import java.time.ZoneId;
import java.util.List;

/**
 * The market snapshot around an HTS ("wstęgi") signal — both timeframes, the
 * same band / ADX picture the trader watches — so the notification reads like an
 * analyst note instead of a field dump. Purely descriptive: nothing here gates
 * the signal (the engine already decided), it colours it for the human.
 *
 * @param htfState        HTF band alignment: "up" / "down" / "flat"
 * @param htfSlowSlope    "rising" / "falling" / "flat" over the last SLOPE_BARS HTF bars
 * @param ltfBandGapAtr   fast→slow band separation as a multiple of the slow-band width
 * @param ltfSlowSlope    LTF slow-band slope, same words as {@code htfSlowSlope}
 * @param pulledBackBars  how many bars ago price last touched the fast band (−1 = not in window)
 * @param adx             LTF ADX(14) at the signal bar
 * @param plusDi          LTF +DI
 * @param minusDi         LTF −DI
 * @param adxZone         "trend" (ADX ≥ 20) / "no-trend" (blue) / "n/a"
 * @param atr             LTF ATR(14)
 * @param vsPivot         close vs the previous session pivot, e.g. "above PP by 0.6x ATR"
 * @param stopDistancePrice  |entry − stop| in price
 * @param stopDistancePct    stop distance as a percent of price
 * @param stopBandEdge    the raw fast-band edge the structural stop sits behind
 * @param riskReward      TP1 distance / stop distance
 */
public record HtsSignalContext(
        String htfState,
        String htfSlowSlope,
        double ltfBandGapAtr,
        String ltfSlowSlope,
        int pulledBackBars,
        double adx,
        double plusDi,
        double minusDi,
        String adxZone,
        double atr,
        String vsPivot,
        double stopDistancePrice,
        double stopDistancePct,
        double stopBandEdge,
        double riskReward) {

    public static HtsSignalContext from(List<Candle> ltf, List<Candle> htf, HtsScan s, ZoneId zone) {
        boolean buy = s.direction() == Direction.BUY;

        Band.Series lFast = Band.rma(ltf, HtsEngine.FAST_LEN);
        Band.Series lSlow = Band.rma(ltf, HtsEngine.SLOW_LEN);
        Band.Series hFast = Band.rma(htf, HtsEngine.FAST_LEN);
        Band.Series hSlow = Band.rma(htf, HtsEngine.SLOW_LEN);
        int i = ltf.size() - 1;
        int h = htf.size() - 1;

        String htfState = "flat";
        if (hFast.ready(h) && hSlow.ready(h)) {
            if (hFast.lower()[h] > hSlow.upper()[h]) {
                htfState = "up";
            } else if (hFast.upper()[h] < hSlow.lower()[h]) {
                htfState = "down";
            }
        }

        double slowWidth = lSlow.ready(i) ? lSlow.upper()[i] - lSlow.lower()[i] : Double.NaN;
        double sep = buy ? lFast.lower()[i] - lSlow.upper()[i] : lSlow.lower()[i] - lFast.upper()[i];
        double gapAtr = slowWidth > 0 ? sep / slowWidth : Double.NaN;

        List<Adx.Point> adxPts = Adx.compute(ltf);
        Adx.Point a = adxPts.isEmpty() ? null : adxPts.getLast();
        double adx = a == null ? Double.NaN : a.adx();
        String adxZone = a == null || Double.isNaN(adx) ? "n/a"
                : (adx >= Adx.TREND_THRESHOLD ? "trend" : "no-trend (blue)");

        double atr = Wilder.last(Wilder.atr(ltf, Adx.PERIOD));

        String vsPivot = "n/a";
        try {
            PivotPoints.Levels pp = PivotPoints.previousCompleted(ltf, ltf.getLast().time(), zone);
            if (pp != null && atr > 0) {
                double dist = (s.entry() - pp.pp()) / atr;
                vsPivot = String.format("%s PP by %.2fx ATR", dist >= 0 ? "above" : "below", Math.abs(dist));
            } else if (pp != null) {
                vsPivot = s.entry() >= pp.pp() ? "above PP" : "below PP";
            }
        } catch (RuntimeException ignored) {
            // BTC / insufficient sessions -> "n/a"
        }

        double stopDist = Math.abs(s.entry() - s.stopLevel());
        double targetDist = Math.abs(s.targetLevel() - s.entry());
        double stopPct = s.entry() != 0 ? stopDist / Math.abs(s.entry()) * 100.0 : Double.NaN;
        double rr = stopDist > 0 ? targetDist / stopDist : Double.NaN;
        double bandEdge = buy ? lFast.lower()[i] : lFast.upper()[i];

        return new HtsSignalContext(
                htfState,
                slope(hSlow, h, HtsEngine.SLOPE_BARS),
                gapAtr,
                slope(lSlow, i, HtsEngine.SLOPE_BARS),
                pulledBackBars(ltf, lFast, buy, i),
                adx,
                a == null ? Double.NaN : a.plusDi(),
                a == null ? Double.NaN : a.minusDi(),
                adxZone,
                atr,
                vsPivot,
                stopDist,
                stopPct,
                bandEdge,
                rr);
    }

    private static String slope(Band.Series s, int i, int lookback) {
        if (!s.ready(i) || !s.ready(i - lookback)) {
            return "n/a";
        }
        double mid = (s.upper()[i] + s.lower()[i]) / 2.0;
        double midPrev = (s.upper()[i - lookback] + s.lower()[i - lookback]) / 2.0;
        double d = mid - midPrev;
        double eps = Math.abs(mid) * 1e-4;
        if (d > eps) {
            return "rising";
        }
        if (d < -eps) {
            return "falling";
        }
        return "flat";
    }

    private static int pulledBackBars(List<Candle> ltf, Band.Series fast, boolean buy, int i) {
        for (int k = i - 1; k >= Math.max(0, i - HtsEngine.PULLBACK_BARS); k--) {
            if (!fast.ready(k)) {
                continue;
            }
            if (buy ? ltf.get(k).low() <= fast.upper()[k] : ltf.get(k).high() >= fast.lower()[k]) {
                return i - k;
            }
        }
        return -1;
    }
}
