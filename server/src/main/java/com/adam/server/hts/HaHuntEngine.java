package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
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
 * Live engine for the two "HA-hunt cloud" strategy variants
 * ({@link HtsVariant#HA4}, {@link HtsVariant#HA12}) — SDD-style entry, an HTF
 * Heikin-Ashi "hunt" regime gate, and a cloud-hold exit. Ribbon variants use
 * {@link HtsEngine}; this one is picked when {@code variant.strategy() == HA_HUNT}.
 *
 * <p><b>Entry</b> (on a just-closed entry-TF bar — M15 for HA4, H1 for HA12):
 * <ol>
 *   <li>entry-TF HA colour flipped on this bar; {@code direction} = the new colour;</li>
 *   <li>{@code direction} == the current hunt-regime colour (last closed H4/H12 HA bar);</li>
 *   <li>entry-TF RMA stacked ({@code close} vs {@code RMA33} vs {@code RMAslow});</li>
 *   <li>"WITH" confirm on the mid TF (H1 for HA4, H4 for HA12): HA colour matches
 *       {@code direction} OR close on the {@code direction} side of the RMAs;</li>
 *   <li>daily Pivot aligned (21:00 UTC roll; skipped for crypto);</li>
 *   <li>universe / side filter;</li>
 *   <li>at most 2 fills per hunt regime per instrument.</li>
 * </ol>
 * <b>Stop</b> = {@code entry ∓ 2.5 × ATR14} on the stop TF (H1 for HA4, H4 for
 * HA12). 1R = that distance. <b>Exit</b> = stop touch OR the last-closed hunt
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

        // --- 1+2: entry-TF HA flip on the just-closed bar ---
        List<HeikenAshi.Bar> eHa = HeikenAshi.from(entryTf);
        int last = eHa.size() - 1;
        boolean curBull = eHa.get(last).bullish();
        if (curBull == eHa.get(last - 1).bullish()) {
            return null;
        }
        boolean longDir = curBull;

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

        // --- 5: "WITH" confirm on the mid TF ---
        if (!withConfirm(v, h1, now, longDir, slow)) {
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
        List<Candle> stopBars = v.atrHours() == 1 ? h1 : Resample.toHours(h1, v.atrHours(), now);
        double atr = Wilder.last(Wilder.atr(stopBars, ATR_LEN));
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

    private boolean withConfirm(HtsVariant v, List<Candle> h1, Instant now, boolean longDir, int slow) {
        if (v.huntHours() == 4) {
            // HA4: H1 HA colour matches OR H1 close stacked with RMA33/RMAslow
            boolean h1Bull = HeikenAshi.from(h1).getLast().bullish();
            double[] c = Wilder.closes(h1);
            double f = Wilder.last(Wilder.rma(c, RMA_FAST));
            double s = Wilder.last(Wilder.rma(c, slow));
            boolean stk = !Double.isNaN(f) && !Double.isNaN(s)
                    && stacked(h1.getLast().close(), f, s, longDir);
            return h1Bull == longDir || stk;
        }
        // HA12: H4 HA colour matches OR H4 close on the direction side of RMA33(H4)
        List<Candle> h4 = Resample.toHours(h1, 4, now);
        if (h4.size() < RMA_FAST + 2) {
            return false;
        }
        boolean h4Bull = HeikenAshi.from(h4).getLast().bullish();
        double f = Wilder.last(Wilder.rma(Wilder.closes(h4), RMA_FAST));
        boolean side = !Double.isNaN(f)
                && (longDir ? h4.getLast().close() > f : h4.getLast().close() < f);
        return h4Bull == longDir || side;
    }

    private static boolean stacked(double close, double fast, double slow, boolean longDir) {
        return longDir ? (close > fast && fast > slow) : (close < fast && fast < slow);
    }

    private static double round(double v) {
        return Math.round(v * 100000.0) / 100000.0;
    }
}
