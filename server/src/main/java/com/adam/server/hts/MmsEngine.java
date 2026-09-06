package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.AtrEnvelope;
import com.adam.server.sdd.Stochastic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * MastermindZX MMS mean-reversion engine ({@link HtsVariant#MMS}): fade an ATR
 * envelope after a closed-bar band touch and the first reactive candle the other
 * way. Both long and short. Stop is a fixed percent of price (no trail, no
 * break-even). Target is the opposite band.
 *
 * <p>Rules (closed bars only — the still-forming bar is dropped):
 * <ol>
 *   <li>SHORT: a closed bar reaches the upper band → wait → first reactive
 *       down candle (close &lt; open) opens SHORT ×1.</li>
 *   <li>LONG: a closed bar reaches the lower band → first reactive up candle
 *       opens LONG ×1.</li>
 *   <li>TP = opposite band (the monitor closes the base when that band is
 *       touched). SL = {@link #SL_PCT} of entry price.</li>
 * </ol>
 *
 * <p>Site source: <a href="https://mastermindzx.pl/">mastermindzx.pl</a>.
 * Published BTCUSDT backtests on that site are monthly-optimised — this engine
 * does not encode win-rate claims. Optional add-on / H1 Stochastic filters
 * default <b>off</b> ({@link Params#addOnEnabled()}, {@link Params#stochFilterEnabled()}).
 */
@Component
public class MmsEngine {

    private static final Logger log = LoggerFactory.getLogger(MmsEngine.class);

    /** Envelope lookback — standard TMA / BB period. */
    public static final int ATR_PERIOD = 20;
    /** Band width in ATRs. */
    public static final double ATR_MULT = 2.0;
    /** Site default SL (~2% of price). Backtests on the site often use 1–1.9%. */
    public static final double SL_PCT = 0.02;
    /** ×1 after a winning opposite-band TP; ×0.1 after a full SL. */
    public static final double RISK_UNIT_FULL = 1.0;
    public static final double RISK_UNIT_DELEVER = 0.1;
    /** How many closed bars after a touch we still accept the first reaction. */
    public static final int REACTION_WINDOW = 8;
    public static final int STOCH_LEN = 14;
    public static final int STOCH_K = 3;
    public static final int STOCH_D = 3;
    public static final double STOCH_OB = 80.0;
    public static final double STOCH_OS = 20.0;

    /**
     * Tunable inputs (bot + Pine). Defaults match the site's "standard settings"
     * note; add-on / stoch stay off until explicitly enabled.
     */
    public record Params(
            int atrPeriod,
            double atrMult,
            double slPct,
            AtrEnvelope.Mode mode,
            boolean addOnEnabled,
            boolean stochFilterEnabled
    ) {
        public static Params defaults() {
            return new Params(ATR_PERIOD, ATR_MULT, SL_PCT, AtrEnvelope.Mode.TMA_ATR, false, false);
        }
    }

    private final Params params;

    public MmsEngine() {
        this(Params.defaults());
    }

    public MmsEngine(Params params) {
        this.params = params == null ? Params.defaults() : params;
    }

    public Params params() {
        return params;
    }

    /**
     * @param entryTf entry-timeframe candles (M15 default), ascending; may include a forming bar
     * @param h1      closed H1 candles — only used when the optional stoch add-on filter is on
     */
    public HtsScan evaluate(HtsVariant v, String code, String epic,
                            List<Candle> entryTf, List<Candle> h1, Instant now) {
        if (v == null || v.strategy() != HtsVariant.Strategy.MMS) {
            return null;
        }
        if (code == null || !v.universe().contains(code)) {
            return null;
        }
        List<Candle> bars = closedOnly(entryTf, v.ltfMinutes(), now);
        int need = 2 * params.atrPeriod() + 2;
        if (bars.size() < need) {
            return null;
        }
        AtrEnvelope.Series env = AtrEnvelope.of(bars, params.atrPeriod(), params.atrMult(), params.mode());
        int i = bars.size() - 1;
        if (!env.ready(i)) {
            return null;
        }
        Boolean longDir = reactionEntry(bars, env, i);
        if (longDir == null) {
            return null;
        }
        if (params.addOnEnabled() && params.stochFilterEnabled()
                && !stochAllows(h1, longDir, now, v.ltfMinutes() >= 60 ? v.ltfMinutes() : 60)) {
            return null;
        }
        Candle bar = bars.get(i);
        double entry = bar.close();
        if (entry <= 0) {
            return null;
        }
        double slDist = entry * params.slPct();
        double stop = longDir ? entry - slDist : entry + slDist;
        double target = longDir ? env.upper()[i] : env.lower()[i];
        log.info("MMS [{}] {} {} entry {} stop {} target {} ({} ± {}×ATR)",
                v.name(), code, longDir ? "LONG" : "SHORT",
                round(entry), round(stop), round(target), params.mode(), params.atrMult());
        return new HtsScan(v, bar.time(), code, epic,
                longDir ? Direction.BUY : Direction.SELL, entry, stop, target, longDir);
    }

    /**
     * Opposite-band TP: last closed bar touches the far envelope.
     * LONG exits when the high reaches the upper band; SHORT when the low
     * reaches the lower band. No trailing, no instant break-even.
     */
    public boolean oppositeBandHit(HtsVariant v, List<Candle> entryTf, boolean positionIsBuy, Instant now) {
        if (v == null || v.strategy() != HtsVariant.Strategy.MMS || entryTf == null) {
            return false;
        }
        List<Candle> bars = closedOnly(entryTf, v.ltfMinutes(), now);
        if (bars.size() < 2 * params.atrPeriod()) {
            return false;
        }
        AtrEnvelope.Series env = AtrEnvelope.of(bars, params.atrPeriod(), params.atrMult(), params.mode());
        int i = bars.size() - 1;
        if (!env.ready(i)) {
            return false;
        }
        Candle bar = bars.get(i);
        return positionIsBuy ? bar.high() >= env.upper()[i] : bar.low() <= env.lower()[i];
    }

    /**
     * Sequential delever from the site: after a full SL, size drops to ×0.1
     * until a winning opposite-band TP restores ×1.
     */
    public static double riskUnitAfter(String closeReason, Double rMultiple) {
        if (closeReason != null) {
            String r = closeReason.trim().toUpperCase();
            if ("STOP".equals(r)) {
                return RISK_UNIT_DELEVER;
            }
            if ("TARGET".equals(r) || "BAND".equals(r)) {
                return RISK_UNIT_FULL;
            }
        }
        if (rMultiple != null && rMultiple <= -0.85) {
            return RISK_UNIT_DELEVER;
        }
        return RISK_UNIT_FULL;
    }

    /**
     * First reactive candle after a band touch, on the just-closed bar {@code i}.
     * Touch and reaction are different bars (wait for the touch bar to close,
     * then the next interval's first opposite-colour body).
     *
     * @return {@code TRUE} long, {@code FALSE} short, {@code null} no entry
     */
    static Boolean reactionEntry(List<Candle> bars, AtrEnvelope.Series env, int i) {
        if (i < 1 || !env.ready(i)) {
            return null;
        }
        Candle last = bars.get(i);
        boolean reactUp = last.close() > last.open();
        boolean reactDown = last.close() < last.open();
        if (!reactUp && !reactDown) {
            return null;
        }
        int from = Math.max(0, i - REACTION_WINDOW);
        Integer upperTouch = null;
        Integer lowerTouch = null;
        for (int j = i - 1; j >= from; j--) {
            if (!env.ready(j)) {
                break;
            }
            Candle c = bars.get(j);
            boolean bear = c.close() < c.open();
            boolean bull = c.close() > c.open();
            if (upperTouch == null && reactDown && c.high() >= env.upper()[j]) {
                upperTouch = j;
            }
            if (lowerTouch == null && reactUp && c.low() <= env.lower()[j]) {
                lowerTouch = j;
            }
            // An earlier same-direction reaction already consumed that touch.
            if (upperTouch == null && bear && reactDown) {
                break;
            }
            if (lowerTouch == null && bull && reactUp) {
                break;
            }
            if (upperTouch != null || lowerTouch != null) {
                break;
            }
        }
        if (upperTouch != null && reactDown) {
            return Boolean.FALSE;
        }
        if (lowerTouch != null && reactUp) {
            return Boolean.TRUE;
        }
        return null;
    }

    /**
     * Drop the still-forming bar: Capital timestamps at bar open, so a scan
     * inside the current interval must not treat that candle as closed.
     */
    static List<Candle> closedOnly(List<Candle> bars, int tfMinutes, Instant now) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        if (now == null || tfMinutes <= 0) {
            return bars;
        }
        Candle last = bars.getLast();
        if (last.time() == null) {
            return bars;
        }
        Instant closeAt = last.time().plusSeconds(tfMinutes * 60L);
        if (now.isBefore(closeAt)) {
            return bars.subList(0, bars.size() - 1);
        }
        return bars;
    }

    /**
     * Optional H1 Stochastic extreme filter for <em>adds</em> (default off).
     * Long add requires %K ≤ oversold; short add requires %K ≥ overbought.
     */
    boolean stochAllows(List<Candle> h1, boolean longDir, Instant now, int tfMinutes) {
        List<Candle> bars = closedOnly(h1, tfMinutes, now);
        if (bars.size() < STOCH_LEN + STOCH_K + STOCH_D) {
            return false;
        }
        Stochastic.Series st = Stochastic.of(bars, STOCH_LEN, STOCH_K, STOCH_D);
        int i = bars.size() - 1;
        if (!st.ready(i)) {
            return false;
        }
        double k = st.k()[i];
        return longDir ? k <= STOCH_OS : k >= STOCH_OB;
    }

    private static double round(double v) {
        return Math.round(v * 100000.0) / 100000.0;
    }
}
