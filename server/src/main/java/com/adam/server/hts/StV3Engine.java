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
 * Live engine for {@link HtsVariant#M15_ST_V3} — PR #140's locked "v3 bakeoff"
 * Pine config (M15/H1 pairing — the M45/M5 pairing backtested worse once a
 * TP1/runner double-counting bug in the research tool was fixed, see the
 * class javadoc on {@link HtsVariant#M15_ST_V3}), ported 1:1:
 *
 * <ol>
 *   <li>H1 Supertrend (ATR 10, factor 2.0 — the research default, <b>not</b>
 *       {@link Supertrend}'s own factor 3.0 — ATR 12/factor 3.0 backtested to
 *       ~breakeven, PF 1.01 IS, clearly worse), resampled from the M15 feed,
 *       is BOTH the direction bias and the initial stop-loss (its own band
 *       level on the last closed H1 bar).</li>
 *   <li>Entry trigger: the first M15 closed bar whose close is beyond the M15
 *       fast RMA-of-high/low band ({@link Band}, fast 33 / slow 144), in the
 *       Supertrend's direction.</li>
 *   <li>H1 structure gate: H1 close stacked vs its own RMA33/144, OR the M15
 *       close already beyond the closed H1 fast band, in the Supertrend
 *       direction.</li>
 *   <li>Universe / fill cap: at most 2 fills per Supertrend regime (resets
 *       when the closed H1 Supertrend flips).</li>
 * </ol>
 *
 * <p>Exit ({@link HtsTradeService#manageRunner}, not this class): TP1 = 2R on
 * half the position, then the remaining half's stop jumps once to breakeven
 * and sits there — no trail, no slow-band exit — until a confirmed M15
 * Heikin-Ashi colour flip against the position flattens the runner.
 *
 * <p>Backtest (12&nbsp;mo IS 2025-09→2026-09 + 12&nbsp;mo OOS 2024-10→2025-09,
 * no fees, BTC/XAU/US100 only — the {@link HtsVariant#M5_ST_V3} pairing and
 * the wider GER40/EURUSD/US500/US30 set were weaker/inconsistent, see the
 * variant javadoc): every one of BTC/XAU/US100 positive in BOTH windows — XAU
 * PF 1.31 IS / 1.12 OOS, BTC 1.31/1.22, US100 1.37/1.35; combined PF 1.33 IS /
 * 1.23 OOS.
 */
@Component
public class StV3Engine {

    private static final Logger log = LoggerFactory.getLogger(StV3Engine.class);
    static final int RMA_FAST = HtsEngine.FAST_LEN;   // 33
    static final int RMA_SLOW = HtsEngine.SLOW_LEN;   // 144
    static final int ST_ATR_LEN = 10;
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
     * @param entryTf closed M15 candles, ascending — the H1 Supertrend + structure
     *                are resampled from this same feed, no separate broker fetch
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

        List<Candle> htf = Resample.toHours(entryTf, 1, now);
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

        // --- entry trigger: fresh M15 close beyond the M15 fast band, direction == Supertrend ---
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

        // --- H1 structure gate: H1 stacked vs its own RMA, OR the M15 close already
        // beyond the closed H1 fast band, in the Supertrend direction ---
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

        // --- stop = the H1 Supertrend's own band level; 1R = |entry - that line| ---
        double stop = stLast.line();
        double dist = Math.abs(close - stop);
        if (dist <= 0 || (buy && stop >= close) || (!buy && stop <= close)) {
            return null;
        }
        double target = buy ? close + 2 * dist : close - 2 * dist;

        reg.fills++;
        log.info("ST_V3 [{}] {} {} fill {}/{} in H1-ST regime ({}), entry {} stop {}",
                v.name(), code, buy ? "LONG" : "SHORT", reg.fills, MAX_FILLS_PER_REGIME,
                stBull ? "bull" : "bear", round(close), round(stop));
        return new HtsScan(v, entryTf.getLast().time(), code, epic,
                buy ? Direction.BUY : Direction.SELL, close, stop, target, stBull);
    }

    private static double round(double x) {
        return Math.round(x * 100000.0) / 100000.0;
    }
}
