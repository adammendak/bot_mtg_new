package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.Band;
import com.adam.server.sdd.HeikenAshi;
import com.adam.server.sdd.PivotPoints;
import com.adam.server.sdd.Resample;
import com.adam.server.sdd.Wilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live engine for the "HA-hunt cloud" strategy variants ({@link HtsVariant#HA4},
 * {@link HtsVariant#HA12}, {@link HtsVariant#HA4X}) — SDD-style entry, an HTF
 * Heikin-Ashi "hunt" regime gate, and a cloud-hold exit. Ribbon variants use
 * {@link HtsEngine}; this one is picked when {@code variant.strategy() == HA_HUNT}.
 *
 * <p><b>Entry</b> (on a just-closed entry-TF bar — M15 for HA4/HA4X, H1 for HA12):
 * <ol>
 *   <li>entry-TF direction, per {@link HtsVariant.EntryTrigger}: HA colour flip
 *       on this bar, or a fresh band cross;</li>
 *   <li>{@code direction} == the current hunt-regime colour (last closed H4/H12 HA bar);</li>
 *   <li>entry-TF RMA stacked ({@code close} vs {@code RMA33} vs {@code RMAslow});</li>
 *   <li>"WITH" confirm on the mid TF (H1 for HA4/HA4X, H4 for HA12): HA colour
 *       matches {@code direction} OR close on the {@code direction} side of the RMAs;</li>
 *   <li>daily Pivot aligned (21:00 UTC roll; skipped for crypto);</li>
 *   <li>universe / side filter;</li>
 *   <li>at most 2 fills per hunt regime per instrument.</li>
 * </ol>
 * <b>Stop</b> = {@code entry ∓ 2.5 × ATR14} on the stop TF (H1 for HA4/HA4X, H4
 * for HA12). 1R = that distance. <b>Exit</b> = stop touch OR the last-closed hunt
 * HA colour flips against the position ({@link #cloudHoldExit}).
 */
@Component
public class HaHuntEngine {

    private static final Logger log = LoggerFactory.getLogger(HaHuntEngine.class);
    private static final int RMA_FAST = 33;
    private static final int ATR_LEN = 14;
    private static final double STOP_ATR_MULT = 2.5;
    private static final int MAX_FILLS_PER_REGIME = 2;
    private static final ZoneId PIVOT_ZONE = ZoneId.of("UTC"); // 21:00 UTC session roll

    /** variant|symbol -> current hunt regime colour + fills taken in it. In-memory; resets on restart. */
    private final Map<String, Regime> regimes = new ConcurrentHashMap<>();

    private static final class Regime {
        boolean bull;
        int fills;

        Regime(boolean bull) {
            this.bull = bull;
        }
    }

    /**
     * @param entryTf closed entry-timeframe candles (M15 for HA4, H1 for HA12), ascending
     * @param h1      closed raw H1 candles, ascending — resampled here for the hunt / stop / WITH TFs
     * @return the entry signal, or {@code null} when any gate fails
     */
    public HtsScan evaluate(HtsVariant v, String code, String epic,
                            List<Candle> entryTf, List<Candle> h1, Instant now, boolean skipPivot) {
        if (v.strategy() != HtsVariant.Strategy.HA_HUNT) {
            return null;
        }
        int huntH = v.huntHours();
        int slow = v.slowLen();
        if (entryTf == null || h1 == null || entryTf.size() < slow + 2 || h1.size() < slow + 2) {
            return null;
        }

        // --- hunt regime: last closed HTF (H4/H12) HA colour ---
        List<Candle> hunt = Resample.toHours(h1, huntH, now);
        if (hunt.size() < 2) {
            return null;
        }
        boolean huntBull = HeikenAshi.from(hunt).getLast().bullish();
        Regime reg = regimes.computeIfAbsent(v.name() + "|" + code, k -> new Regime(huntBull));
        if (reg.bull != huntBull) {
            reg.bull = huntBull;
            reg.fills = 0; // colour flipped — a new hunt opened
        }

        // --- 1+2: entry-TF direction — trigger mode picks how it's decided ---
        Boolean dir = v.entryTrigger() == HtsVariant.EntryTrigger.BAND_CROSS
                ? bandCrossDirection(entryTf, slow)
                : haFlipDirection(entryTf);
        if (dir == null) {
            return null;
        }
        boolean longDir = dir;

        // --- 3: hunt gate ---
        if (longDir != huntBull) {
            return null;
        }

        // --- 4: entry-TF RMA stacked ---
        double[] eClose = Wilder.closes(entryTf);
        double eFast = Wilder.last(Wilder.rma(eClose, RMA_FAST));
        double eSlow = Wilder.last(Wilder.rma(eClose, slow));
        double entryClose = entryTf.getLast().close();
        if (Double.isNaN(eFast) || Double.isNaN(eSlow) || !stacked(entryClose, eFast, eSlow, longDir)) {
            return null;
        }

        // --- 5: "WITH" confirm on the mid TF (also the stop TF — computed once, step 5 + the stop below) ---
        List<Candle> midBars = midTimeframe(v, entryTf, h1, now);
        if (!withConfirm(midBars, longDir, slow)) {
            return null;
        }

        // --- 6: daily Pivot ---
        if (!skipPivot) {
            PivotPoints.Levels pp = PivotPoints.previousCompleted(h1, now, PIVOT_ZONE);
            if (pp == null || !PivotPoints.aligned(entryClose, pp.pp(), longDir)) {
                return null;
            }
        }

        // --- 7: universe. Side is NOT filtered here: a short on a long-only
        // variant is still emitted so it is e-mailed / logged, but the scan does
        // not hand it to the execution gate (observe-only). ---
        if (!v.universe().contains(code)) {
            return null;
        }

        // --- hunt fill cap ---
        if (reg.fills >= MAX_FILLS_PER_REGIME) {
            return null;
        }

        // --- stop = entry ∓ 2.5 × ATR14(stop TF) ---
        double atr = Wilder.last(Wilder.atr(midBars, ATR_LEN));
        if (Double.isNaN(atr) || atr <= 0) {
            return null;
        }
        double dist = STOP_ATR_MULT * atr;
        double stop = longDir ? entryClose - dist : entryClose + dist;
        double target = longDir ? entryClose + 2 * dist : entryClose - 2 * dist;

        reg.fills++;
        log.info("HA-hunt [{}] {} {} fill {}/{} in regime ({}), entry {} stop {} (ATR {})",
                v.name(), code, longDir ? "LONG" : "SHORT", reg.fills, MAX_FILLS_PER_REGIME,
                huntBull ? "bull" : "bear", round(entryClose), round(stop), round(atr));
        return new HtsScan(v, entryTf.getLast().time(), code, epic,
                longDir ? Direction.BUY : Direction.SELL, entryClose, stop, target, huntBull);
    }

    /**
     * Cloud-hold exit read for the position monitor: {@code true} when the
     * last-closed hunt HA colour has flipped against the open position.
     */
    public boolean cloudHoldExit(HtsVariant v, List<Candle> h1, boolean positionIsBuy, Instant now) {
        if (v.strategy() != HtsVariant.Strategy.HA_HUNT || h1 == null || h1.size() < 2) {
            return false;
        }
        List<Candle> hunt = Resample.toHours(h1, v.huntHours(), now);
        if (hunt.size() < 2) {
            return false;
        }
        boolean huntBull = HeikenAshi.from(hunt).getLast().bullish();
        return positionIsBuy ? !huntBull : huntBull;
    }

    /** HA_FLIP trigger: entry-TF Heikin-Ashi colour changed on the just-closed bar. */
    static Boolean haFlipDirection(List<Candle> entryTf) {
        List<HeikenAshi.Bar> eHa = HeikenAshi.from(entryTf);
        int last = eHa.size() - 1;
        boolean curBull = eHa.get(last).bullish();
        return curBull == eHa.get(last - 1).bullish() ? null : curBull;
    }

    /**
     * BAND_CROSS trigger: entry-TF close closes beyond the fast RMA band (fast
     * band clear of the slow band), on the first bar this becomes true — not a
     * persisting state, otherwise it would re-signal every bar the price stays
     * extended.
     */
    static Boolean bandCrossDirection(List<Candle> entryTf, int slow) {
        int i = entryTf.size() - 1;
        if (i < 1) {
            return null;
        }
        Band.Series fast = Band.rma(entryTf, RMA_FAST);
        Band.Series slowBand = Band.rma(entryTf, slow);
        if (!fast.ready(i) || !slowBand.ready(i) || !fast.ready(i - 1) || !slowBand.ready(i - 1)) {
            return null;
        }
        double close = entryTf.get(i).close();
        double prevClose = entryTf.get(i - 1).close();
        boolean longNow = close > fast.upper()[i] && fast.lower()[i] > slowBand.upper()[i];
        boolean shortNow = close < fast.lower()[i] && fast.upper()[i] < slowBand.lower()[i];
        boolean longPrev = prevClose > fast.upper()[i - 1] && fast.lower()[i - 1] > slowBand.upper()[i - 1];
        boolean shortPrev = prevClose < fast.lower()[i - 1] && fast.upper()[i - 1] < slowBand.lower()[i - 1];
        if (longNow && !longPrev) {
            return Boolean.TRUE;
        }
        if (shortNow && !shortPrev) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * The mid timeframe used for both the WITH confirm and the ATR stop — one
     * step between the entry TF and the hunt TF. Whole-hour hunts (H4, H12)
     * resample it from the raw H1 feed (H1 itself, or an H4 resample); an H1
     * hunt has no room for a whole-hour mid step, so it resamples a finer one
     * (M15) from the entry-TF (M5) feed instead — see {@link HtsVariant#atrMinutes()}.
     */
    private static List<Candle> midTimeframe(HtsVariant v, List<Candle> entryTf, List<Candle> h1, Instant now) {
        if (v.atrMinutes() > 0) {
            return Resample.toMinutes(entryTf, v.atrMinutes(), now);
        }
        return v.atrHours() == 1 ? h1 : Resample.toHours(h1, v.atrHours(), now);
    }

    /** Mid-TF HA colour matches {@code direction}, OR mid-TF close is RMA-stacked with it. */
    private static boolean withConfirm(List<Candle> midBars, boolean longDir, int slow) {
        if (midBars.size() < slow + 2) {
            return false;
        }
        boolean midBull = HeikenAshi.from(midBars).getLast().bullish();
        double[] c = Wilder.closes(midBars);
        double f = Wilder.last(Wilder.rma(c, RMA_FAST));
        double s = Wilder.last(Wilder.rma(c, slow));
        boolean stk = !Double.isNaN(f) && !Double.isNaN(s)
                && stacked(midBars.getLast().close(), f, s, longDir);
        return midBull == longDir || stk;
    }

    private static boolean stacked(double close, double fast, double slow, boolean longDir) {
        return longDir ? (close > fast && fast > slow) : (close < fast && fast < slow);
    }

    private static double round(double v) {
        return Math.round(v * 100000.0) / 100000.0;
    }
}
