package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.Band;
import com.adam.server.sdd.Resample;
import com.adam.server.sdd.Supertrend;
import com.adam.server.sdd.Wilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live engine for every {@link HtsVariant.Strategy#ST_V3} variant — PR #140's
 * "v3 bakeoff" Pine config, generalised across three timeframe pairings
 * ({@link HtsVariant#M5_ST_V3} M45/M5, {@link HtsVariant#M15_ST_V3} /
 * {@link HtsVariant#M15_ST_V3B} H1/M15, {@link HtsVariant#H1_ST_V3} H4/H1 —
 * the HTF span in minutes is {@code v.atrMinutes()}, repurposed for this
 * strategy since ST_V3 has no HA-hunt "mid TF" of its own):
 *
 * <ol>
 *   <li>HTF Supertrend (ATR 7, factor 2.0 — PR #144/#146's locked research
 *       default, <b>not</b> {@link Supertrend}'s own factor 3.0), resampled
 *       from the entry-TF feed, is BOTH the direction bias and the initial
 *       stop-loss (its own band level on the last closed HTF bar).</li>
 *   <li>Entry trigger: the first entry-TF closed bar whose close is beyond
 *       its fast RMA-of-high/low band ({@link Band}, fast 33 / slow 144), in
 *       the Supertrend's direction.</li>
 *   <li>HTF structure gate: HTF close stacked vs its own RMA33/144, OR the
 *       entry-TF close already beyond the closed HTF fast band, in the
 *       Supertrend direction.</li>
 *   <li>Universe / fill cap: at most 2 fills per Supertrend regime (resets
 *       when the closed HTF Supertrend flips).</li>
 * </ol>
 *
 * <p>Exit ({@link HtsTradeService#stV3Exit}, not this class): TP1 = 2R on
 * half the position, then the remaining half's stop jumps once to breakeven
 * and sits there — no trail — until the entry-TF's own fast RMA band starts
 * crossing to the opposite side of its slow band (band-cross exit; see that
 * method's javadoc for why this replaced the original HA-flip runner).
 *
 * <p>Backtest (12&nbsp;mo IS 2025-09→2026-09 + 12&nbsp;mo OOS 2024-10→2025-09,
 * no fees, band-cross exit, 10 tickers): H1/M15 is the strongest and most
 * consistent pairing — every one of XAU/BTC/US100/GER40/EURUSD/US500/US30/
 * XAG/J225/USDJPY positive in BOTH windows, combined PF 2.10 IS / 1.65 OOS.
 * M45/M5 also broadly positive (every ticker both windows, combined PF 1.87
 * IS / 1.98 OOS) but noisier. H4/H1 fires far less often (~1/4 the signals)
 * and is less consistent per-symbol despite a strong combined OOS (PF 2.04) —
 * kept as a smaller-universe satellite, not the primary pick.
 */
@Component
public class StV3Engine {

    private static final Logger log = LoggerFactory.getLogger(StV3Engine.class);
    static final int RMA_FAST = HtsEngine.FAST_LEN;   // 33
    static final int RMA_SLOW = HtsEngine.SLOW_LEN;   // 144
    static final int ST_ATR_LEN = 7;
    static final double ST_FACTOR = 2.0;
    private static final int MAX_FILLS_PER_REGIME = 2;

    /** variant|symbol -> current Supertrend regime (trend +1/-1) + fills taken in it. */
    private final Map<String, Regime> regimes = new ConcurrentHashMap<>();

    private static final class Regime {
        int trend;
        int fills;

        Regime(int trend) {
            this.trend = trend;
        }
    }

    /**
     * @param entryTf closed entry-TF candles, ascending — the HTF Supertrend +
     *                structure are resampled from this same feed ({@code
     *                Resample.toMinutes(entryTf, v.atrMinutes(), now)}), no
     *                separate broker fetch
     * @return the entry signal, or {@code null} when any gate fails
     */
    public HtsScan evaluate(HtsVariant v, String code, String epic, List<Candle> entryTf, Instant now) {
        if (v.strategy() != HtsVariant.Strategy.ST_V3) {
            return null;
        }
        if (entryTf == null || entryTf.size() < RMA_SLOW + 2) {
            return null;
        }
        if (!v.universe().contains(code)) {
            return null;
        }

        List<Candle> htf = Resample.toMinutes(entryTf, v.atrMinutes(), now);
        if (htf.size() < ST_ATR_LEN + 2) {
            return null;
        }

        List<Supertrend.Point> st = Supertrend.compute(htf, ST_ATR_LEN, ST_FACTOR);
        Supertrend.Point stLast = st.getLast();
        if (stLast.trend() == 0 || Double.isNaN(stLast.line())) {
            return null;
        }
        boolean stBull = stLast.trend() == 1;

        Regime reg = regimes.computeIfAbsent(v.name() + "|" + code, k -> new Regime(stLast.trend()));
        if (reg.trend != stLast.trend()) {
            reg.trend = stLast.trend();
            reg.fills = 0; // Supertrend flipped — a new regime opened
        }

        // --- entry trigger: fresh entry-TF close beyond its fast band, direction == Supertrend ---
        Band.Series fast = Band.rma(entryTf, RMA_FAST);
        int i = entryTf.size() - 1;
        if (!fast.ready(i) || !fast.ready(i - 1)) {
            return null;
        }
        double close = entryTf.get(i).close();
        double prevClose = entryTf.get(i - 1).close();
        boolean beyondUp = close > fast.upper()[i];
        boolean beyondDn = close < fast.lower()[i];
        boolean prevUp = prevClose > fast.upper()[i - 1];
        boolean prevDn = prevClose < fast.lower()[i - 1];
        boolean freshUp = beyondUp && !prevUp;
        boolean freshDn = beyondDn && !prevDn;

        boolean buy = stBull;
        if (!(buy ? freshUp : freshDn)) {
            return null;
        }

        // --- HTF structure gate: HTF stacked vs its own RMA, OR the entry-TF close
        // already beyond the closed HTF fast band, in the Supertrend direction ---
        double[] htfClose = Wilder.closes(htf);
        double hFast = Wilder.last(Wilder.rma(htfClose, RMA_FAST));
        double hSlow = Wilder.last(Wilder.rma(htfClose, RMA_SLOW));
        double htfLastClose = htf.getLast().close();
        Band.Series htfFast = Band.rma(htf, RMA_FAST);
        int hi = htf.size() - 1;
        boolean structOk;
        if (buy) {
            structOk = (!Double.isNaN(hFast) && !Double.isNaN(hSlow) && htfLastClose > hFast && hFast > hSlow)
                    || (htfFast.ready(hi) && close > htfFast.upper()[hi]);
        } else {
            structOk = (!Double.isNaN(hFast) && !Double.isNaN(hSlow) && htfLastClose < hFast && hFast < hSlow)
                    || (htfFast.ready(hi) && close < htfFast.lower()[hi]);
        }
        if (!structOk) {
            return null;
        }

        if (reg.fills >= MAX_FILLS_PER_REGIME) {
            return null;
        }

        // --- stop = the HTF Supertrend's own band level; 1R = |entry - that line| ---
        double stop = stLast.line();
        double dist = Math.abs(close - stop);
        if (dist <= 0 || (buy && stop >= close) || (!buy && stop <= close)) {
            return null;
        }
        double target = buy ? close + 2 * dist : close - 2 * dist;

        reg.fills++;
        log.info("ST_V3 [{}] {} {} fill {}/{} in HTF-ST regime ({}), entry {} stop {}",
                v.name(), code, buy ? "LONG" : "SHORT", reg.fills, MAX_FILLS_PER_REGIME,
                stBull ? "bull" : "bear", round(close), round(stop));
        return new HtsScan(v, entryTf.getLast().time(), code, epic,
                buy ? Direction.BUY : Direction.SELL, close, stop, target, stBull);
    }

    private static double round(double x) {
        return Math.round(x * 100000.0) / 100000.0;
    }
}
