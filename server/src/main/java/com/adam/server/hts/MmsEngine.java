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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MastermindZX MMS mean-reversion engine ({@link HtsVariant#MMS}): fade an ATR
 * envelope after a closed-bar band touch and the first reactive candle the other
 * way. Both long and short. Stop is mandatory; no trail, no break-even.
 *
 * <p>TP is selectable ({@link TpMode}):
 * <ul>
 *   <li>{@link TpMode#OPPOSITE_BAND} — site prose: close / flip the base when
 *       the far envelope is touched.</li>
 *   <li>{@link TpMode#FIXED_1R} — MT5 Strategy Tester clips on BTCUSD: take
 *       profit at 1:1 from the stop distance. SL is either a % of price or
 *       beyond the piercing / reaction wick ({@link SlMode}).</li>
 * </ul>
 *
 * <p>Optional Stochastic: H1 extreme filter for <em>entries and adds</em>
 * ({@link Params#stochFilterEnabled()}), and an entry-TF %K/%D cross after
 * OB/OS ({@link Params#stochCrossEnabled()}) matching the BB + Stoch tester
 * clip. Both default <b>off</b>.
 *
 * <p>Closed bars only — the still-forming bar is dropped. Site:
 * <a href="https://mastermindzx.pl/">mastermindzx.pl</a>. Published BTCUSDT
 * backtests are monthly-optimised; this engine does not encode win-rate claims.
 */
@Component
public class MmsEngine {

    private static final Logger log = LoggerFactory.getLogger(MmsEngine.class);

    /** Envelope lookback — standard TMA / BB period. */
    public static final int ATR_PERIOD = 20;
    /** Band width in ATRs. Site BB M15 example uses period 41 / deviation 3.2. */
    public static final double ATR_MULT = 2.0;
    /** Site default SL (~2% of price). Backtests on the site often use 1–1.9%. */
    public static final double SL_PCT = 0.02;
    /** ×1 after a winning TP; ×0.1 after a full SL (site also mentions ×0.01 in BT talk). */
    public static final double RISK_UNIT_FULL = 1.0;
    public static final double RISK_UNIT_DELEVER = 0.1;
    public static final double RISK_UNIT_MICRO = 0.01;
    /** How many closed bars after a touch we still accept the first reaction. */
    public static final int REACTION_WINDOW = 8;
    public static final int STOCH_LEN = 14;
    public static final int STOCH_K = 3;
    public static final int STOCH_D = 3;
    public static final double STOCH_OB = 80.0;
    public static final double STOCH_OS = 20.0;
    /** Add-on extra risk vs the base entry: must be ≤ 1%, typically &lt; 0.5%. */
    public static final double ADDON_MAX_EXTRA_PCT = 0.01;
    public static final double ADDON_TYPICAL_EXTRA_PCT = 0.005;
    /** Stoch-filtered add-on uses a fixed 1% SL instead of the wick. */
    public static final double ADDON_STOCH_SL_PCT = 0.01;

    /** Site prose vs MT5 tester clips. */
    public enum TpMode { OPPOSITE_BAND, FIXED_1R }

    /** % of entry (site default) vs stop beyond the piercing / reaction wick. */
    public enum SlMode { PCT, WICK_EXTREME }

    /**
     * Tunable inputs (bot + Pine). Defaults match the site's written rules
     * (opposite-band TP, 2% SL, TMA envelope). Tester-clip presets live on
     * {@link #testerFixed1r()} / {@link #testerBbStoch()}.
     */
    public record Params(
            int atrPeriod,
            double atrMult,
            double slPct,
            AtrEnvelope.Mode mode,
            TpMode tpMode,
            SlMode slMode,
            boolean addOnEnabled,
            boolean stochFilterEnabled,
            boolean stochCrossEnabled,
            double addOnMaxExtraPct,
            double addOnStochSlPct,
            int reactionWindow
    ) {
        public static Params defaults() {
            return new Params(ATR_PERIOD, ATR_MULT, SL_PCT, AtrEnvelope.Mode.TMA_ATR,
                    TpMode.OPPOSITE_BAND, SlMode.PCT, false, false, false,
                    ADDON_MAX_EXTRA_PCT, ADDON_STOCH_SL_PCT, REACTION_WINDOW);
        }

        /** MT5 tester: 1:1 RR, SL beyond the piercing wick. */
        public static Params testerFixed1r() {
            return new Params(ATR_PERIOD, ATR_MULT, SL_PCT, AtrEnvelope.Mode.TMA_ATR,
                    TpMode.FIXED_1R, SlMode.WICK_EXTREME, false, false, false,
                    ADDON_MAX_EXTRA_PCT, ADDON_STOCH_SL_PCT, REACTION_WINDOW);
        }

        /** Tester clip: BB pierce + Stoch OB/OS + reversal close + %K/%D cross, 1:1 RR. */
        public static Params testerBbStoch() {
            return new Params(ATR_PERIOD, ATR_MULT, SL_PCT, AtrEnvelope.Mode.BB_ATR,
                    TpMode.FIXED_1R, SlMode.WICK_EXTREME, false, false, true,
                    ADDON_MAX_EXTRA_PCT, ADDON_STOCH_SL_PCT, REACTION_WINDOW);
        }

        /** Documented site BB M15 example (period 41, deviation 3.2, SL 1.7%) — not the live default. */
        public static Params siteBbM15Example() {
            return new Params(41, 3.2, 0.017, AtrEnvelope.Mode.BB_ATR,
                    TpMode.OPPOSITE_BAND, SlMode.PCT, false, false, false,
                    ADDON_MAX_EXTRA_PCT, ADDON_STOCH_SL_PCT, REACTION_WINDOW);
        }

        /** Build from {@code app.mms.*} env config (bad enum text falls back to the site default). */
        public static Params fromConfig(com.adam.server.config.AppProperties.Mms m) {
            if (m == null) {
                return defaults();
            }
            int win = m.getReactionWindow() > 0 ? m.getReactionWindow() : REACTION_WINDOW;
            return new Params(
                    m.getAtrPeriod() > 0 ? m.getAtrPeriod() : ATR_PERIOD,
                    m.getAtrMult() > 0 ? m.getAtrMult() : ATR_MULT,
                    m.getSlPct() > 0 ? m.getSlPct() : SL_PCT,
                    parseEnum(AtrEnvelope.Mode.class, m.getMode(), AtrEnvelope.Mode.TMA_ATR),
                    parseEnum(TpMode.class, m.getTpMode(), TpMode.OPPOSITE_BAND),
                    parseEnum(SlMode.class, m.getSlMode(), SlMode.PCT),
                    m.isAddOnEnabled(), m.isStochFilterEnabled(), m.isStochCrossEnabled(),
                    ADDON_MAX_EXTRA_PCT, ADDON_STOCH_SL_PCT, win);
        }

        private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Enum.valueOf(type, raw.trim().toUpperCase());
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    /** Closed-bar band-touch + reaction (touch and reaction are different bars). */
    record Setup(boolean longDir, int touchIdx, int reactIdx) {
    }

    private final Params params;
    /** Configured symbol subset (blank config = empty = no restriction). */
    private final java.util.Set<String> symbolSubset;
    /** variant|symbol → add-on book for this setup (in-memory; resets on restart). */
    private final Map<String, AddonMem> addons = new ConcurrentHashMap<>();

    private static final class AddonMem {
        Instant setupBar;
        Instant addonBar;
        boolean taken;
        boolean stopped; // add-on SL hit — do not retry until a new base setup
    }

    public MmsEngine() {
        this(Params.defaults(), java.util.Set.of());
    }

    /** Spring wiring: build {@link Params} + the symbol subset from {@code app.mms.*}. */
    @org.springframework.beans.factory.annotation.Autowired
    public MmsEngine(com.adam.server.config.AppProperties props) {
        this(Params.fromConfig(props == null ? null : props.getMms()),
                props == null ? java.util.Set.of() : props.getMms().symbolSet());
    }

    public MmsEngine(Params params) {
        this(params, java.util.Set.of());
    }

    public MmsEngine(Params params, java.util.Set<String> symbolSubset) {
        this.params = params == null ? Params.defaults() : params;
        this.symbolSubset = symbolSubset == null ? java.util.Set.of() : java.util.Set.copyOf(symbolSubset);
    }

    public Params params() {
        return params;
    }

    /** Whether the configured {@code app.mms.symbols} subset (if any) admits this code. */
    public boolean tradesSymbol(String code) {
        return code != null && (symbolSubset.isEmpty() || symbolSubset.contains(code.toUpperCase()));
    }

    /**
     * @param entryTf entry-timeframe candles (M15 default), ascending; may include a forming bar
     * @param h1      closed H1 candles — used when the optional H1 stoch filter is on
     */
    public HtsScan evaluate(HtsVariant v, String code, String epic,
                            List<Candle> entryTf, List<Candle> h1, Instant now) {
        if (v == null || v.strategy() != HtsVariant.Strategy.MMS) {
            return null;
        }
        if (code == null || !v.universe().contains(code)) {
            return null;
        }
        if (!algoTfOk(v.ltfMinutes())) {
            return null; // exclude M5; algo is M10–M30 / H1
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
        Setup setup = reactionSetup(bars, env, i, params.reactionWindow());
        if (setup == null) {
            return null;
        }
        boolean longDir = setup.longDir();
        if (params.stochFilterEnabled()
                && !stochAllows(h1, longDir, now, v.ltfMinutes() >= 60 ? v.ltfMinutes() : 60)) {
            return null;
        }
        if (params.stochCrossEnabled() && !stochCrossOk(bars, setup)) {
            return null;
        }
        Candle bar = bars.get(setup.reactIdx());
        double entry = bar.close();
        if (entry <= 0) {
            return null;
        }
        double stop = stopLevel(entry, longDir, bars, setup);
        double slDist = Math.abs(entry - stop);
        if (slDist <= 0) {
            return null;
        }
        double target = params.tpMode() == TpMode.FIXED_1R
                ? (longDir ? entry + slDist : entry - slDist)
                : (longDir ? env.upper()[i] : env.lower()[i]);
        rememberBase(v, code, bar.time());
        log.info("MMS [{}] {} {} entry {} stop {} target {} ({} {} / {} SL)",
                v.name(), code, longDir ? "LONG" : "SHORT",
                round(entry), round(stop), round(target),
                params.mode(), params.tpMode(), params.slMode());
        return new HtsScan(v, bar.time(), code, epic,
                longDir ? Direction.BUY : Direction.SELL, entry, stop, target, longDir);
    }

    /**
     * Optional add-on ×1 after one full confirming candle on a new interval.
     * Default off. Rejected when the add-on was already taken or its SL already
     * hit for this setup (wait for an entirely new base). Base SL is unchanged.
     */
    public HtsScan evaluateAdd(HtsVariant v, String code, String epic,
                               List<Candle> entryTf, List<Candle> h1, Instant now,
                               double baseEntry, boolean longDir, Instant baseBar, Double baseTarget) {
        if (!params.addOnEnabled() || v == null || v.strategy() != HtsVariant.Strategy.MMS) {
            return null;
        }
        if (code == null || !v.universe().contains(code) || !algoTfOk(v.ltfMinutes())) {
            return null;
        }
        if (addonBlocked(v, code)) {
            return null;
        }
        List<Candle> bars = closedOnly(entryTf, v.ltfMinutes(), now);
        int i = confirmingBar(bars, baseBar, longDir);
        if (i < 0) {
            return null;
        }
        if (params.stochFilterEnabled()
                && !stochAllows(h1, longDir, now, v.ltfMinutes() >= 60 ? v.ltfMinutes() : 60)) {
            return null;
        }
        Candle bar = bars.get(i);
        double wick = longDir ? bar.low() : bar.high();
        double stop = addonStop(baseEntry, longDir, wick, params.stochFilterEnabled(),
                params.addOnMaxExtraPct(), params.addOnStochSlPct());
        if (Double.isNaN(stop) || bar.close() <= 0) {
            return null;
        }
        double entry = bar.close();
        double slDist = Math.abs(entry - stop);
        if (slDist <= 0) {
            return null;
        }
        AtrEnvelope.Series env = AtrEnvelope.of(bars, params.atrPeriod(), params.atrMult(), params.mode());
        double target;
        if (params.tpMode() == TpMode.FIXED_1R) {
            target = longDir ? entry + slDist : entry - slDist;
        } else if (baseTarget != null) {
            target = baseTarget;
        } else if (env.ready(i)) {
            target = longDir ? env.upper()[i] : env.lower()[i];
        } else {
            return null;
        }
        rememberAddon(v, code, bar.time());
        log.info("MMS [{}] {} ADD {} entry {} stop {} (wick extra ≤ {}%)",
                v.name(), code, longDir ? "LONG" : "SHORT",
                round(entry), round(stop), round(params.addOnMaxExtraPct() * 100.0));
        return new HtsScan(v, bar.time(), code, epic,
                longDir ? Direction.BUY : Direction.SELL, entry, stop, target, longDir);
    }

    /** Algo entry TFs: M10–M30 and H1. M5 is noise; H4/D1 are context only. */
    static boolean algoTfOk(int ltfMinutes) {
        return ltfMinutes >= 10 && ltfMinutes <= 60;
    }

    /**
     * Index of the first closed bar after {@code baseBar} that is a same-colour
     * confirming candle, or {@code -1}.
     */
    static int confirmingBar(List<Candle> bars, Instant baseBar, boolean longDir) {
        if (bars == null || baseBar == null || bars.size() < 2) {
            return -1;
        }
        int base = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).time() != null && bars.get(i).time().equals(baseBar)) {
                base = i;
                break;
            }
        }
        int next = base >= 0 ? base + 1 : -1;
        if (next < 0) {
            for (int i = 0; i < bars.size(); i++) {
                if (bars.get(i).time() != null && bars.get(i).time().isAfter(baseBar)) {
                    next = i;
                    break;
                }
            }
        }
        if (next < 0 || next != bars.size() - 1) {
            return -1; // only the just-closed confirming bar
        }
        Candle c = bars.get(next);
        boolean up = c.close() > c.open();
        boolean down = c.close() < c.open();
        return (longDir ? up : down) ? next : -1;
    }

    public boolean addonBlocked(HtsVariant v, String code) {
        AddonMem m = addons.get(key(v, code));
        return m != null && (m.taken || m.stopped);
    }

    void rememberBase(HtsVariant v, String code, Instant bar) {
        AddonMem m = new AddonMem();
        m.setupBar = bar;
        addons.put(key(v, code), m);
    }

    void rememberAddon(HtsVariant v, String code, Instant bar) {
        AddonMem m = addons.computeIfAbsent(key(v, code), k -> new AddonMem());
        m.taken = true;
        m.addonBar = bar;
    }

    /**
     * Add-on SL hit → never retry adds on this setup; keep the base SL.
     * A new {@link #evaluate} base resets the book.
     */
    public void onClosed(HtsVariant v, String code, Instant bar, String reason) {
        if (v == null || code == null || reason == null) {
            return;
        }
        AddonMem m = addons.get(key(v, code));
        if (m == null || m.addonBar == null || bar == null || !m.addonBar.equals(bar)) {
            return;
        }
        if ("STOP".equalsIgnoreCase(reason.trim())) {
            m.stopped = true;
        }
    }

    private static String key(HtsVariant v, String code) {
        return v.name() + "|" + code;
    }

    /**
     * Opposite-band TP: last closed bar touches the far envelope.
     * LONG exits when the high reaches the upper band; SHORT when the low
     * reaches the lower band. Used when {@link TpMode#OPPOSITE_BAND}.
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
     * FIXED_1R TP: last closed bar reaches the stored 1:1 target. No trail.
     */
    public boolean fixed1rHit(HtsVariant v, List<Candle> entryTf, boolean positionIsBuy,
                              Double target, Instant now) {
        if (v == null || v.strategy() != HtsVariant.Strategy.MMS || target == null || entryTf == null) {
            return false;
        }
        List<Candle> bars = closedOnly(entryTf, v.ltfMinutes(), now);
        if (bars.isEmpty()) {
            return false;
        }
        Candle bar = bars.getLast();
        return positionIsBuy ? bar.high() >= target : bar.low() <= target;
    }

    /**
     * Sequential delever from the site: after a full SL, size drops to ×0.1
     * until a winning TP restores ×1. Site BT talk also mentions ×0.01 —
     * that micro unit is not applied automatically.
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
     * Add-on SL at the local wick. Rejected ({@code NaN}) when the extra move
     * vs the base entry is ≤ 0 or &gt; {@code maxExtraPct} (site: ≤ 1%, typically
     * &lt; 0.5%). Stoch-filtered adds use a fixed 1% SL instead.
     */
    public static double addonStop(double baseEntry, boolean longDir, double localWick,
                                   boolean stochFiltered, double maxExtraPct, double stochSlPct) {
        if (baseEntry <= 0) {
            return Double.NaN;
        }
        if (stochFiltered) {
            return longDir ? baseEntry * (1.0 - stochSlPct) : baseEntry * (1.0 + stochSlPct);
        }
        double extra = longDir ? (baseEntry - localWick) / baseEntry : (localWick - baseEntry) / baseEntry;
        if (extra <= 0 || extra > maxExtraPct) {
            return Double.NaN;
        }
        return localWick;
    }

    /**
     * True when the stored stop is an add-on wick / stoch 1% SL (extra ≤
     * {@link #ADDON_MAX_EXTRA_PCT}), not the full ~2% base stop. Used so an
     * add-on STOP does not cut the sequential risk unit.
     */
    public static boolean addonSizedStop(Double entry, Double stop) {
        if (entry == null || stop == null || entry <= 0) {
            return false;
        }
        return Math.abs(entry - stop) / entry <= ADDON_MAX_EXTRA_PCT + 1e-12;
    }

    double stopLevel(double entry, boolean longDir, List<Candle> bars, Setup setup) {
        if (params.slMode() == SlMode.WICK_EXTREME && setup != null) {
            Candle touch = bars.get(setup.touchIdx());
            Candle react = bars.get(setup.reactIdx());
            double extreme = longDir
                    ? Math.min(touch.low(), react.low())
                    : Math.max(touch.high(), react.high());
            boolean beyond = longDir ? extreme < entry : extreme > entry;
            if (beyond) {
                return extreme;
            }
        }
        double slDist = entry * params.slPct();
        return longDir ? entry - slDist : entry + slDist;
    }

    /**
     * First reactive candle after a band touch, on the just-closed bar {@code i}.
     *
     * @return {@code TRUE} long, {@code FALSE} short, {@code null} no entry
     */
    static Boolean reactionEntry(List<Candle> bars, AtrEnvelope.Series env, int i) {
        Setup s = reactionSetup(bars, env, i, REACTION_WINDOW);
        return s == null ? null : s.longDir();
    }

    static Setup reactionSetup(List<Candle> bars, AtrEnvelope.Series env, int i) {
        return reactionSetup(bars, env, i, REACTION_WINDOW);
    }

    static Setup reactionSetup(List<Candle> bars, AtrEnvelope.Series env, int i, int window) {
        if (i < 1 || !env.ready(i)) {
            return null;
        }
        Candle last = bars.get(i);
        boolean reactUp = last.close() > last.open();
        boolean reactDown = last.close() < last.open();
        if (!reactUp && !reactDown) {
            return null;
        }
        int from = Math.max(0, i - window);
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
            return new Setup(false, upperTouch, i);
        }
        if (lowerTouch != null && reactUp) {
            return new Setup(true, lowerTouch, i);
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
     * Optional H1 Stochastic extreme filter for entries and adds (default off).
     * Long requires %K ≤ oversold; short requires %K ≥ overbought.
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

    /**
     * Tester-clip entry-TF filter (default off): Stoch was OB/OS on the touch
     * (or the bar before the reaction) and %K crosses %D on the closed
     * reversal bar. Closed-bar stand-in for "enter next open".
     */
    boolean stochCrossOk(List<Candle> bars, Setup setup) {
        if (setup == null || bars == null) {
            return false;
        }
        Stochastic.Series st = Stochastic.of(bars, STOCH_LEN, STOCH_K, STOCH_D);
        int i = setup.reactIdx();
        if (!st.ready(i) || !st.ready(i - 1) || !st.ready(setup.touchIdx())) {
            return false;
        }
        double kTouch = st.k()[setup.touchIdx()];
        double kPrev = st.k()[i - 1];
        boolean extreme = setup.longDir()
                ? (kTouch <= STOCH_OS || kPrev <= STOCH_OS)
                : (kTouch >= STOCH_OB || kPrev >= STOCH_OB);
        if (!extreme) {
            return false;
        }
        return setup.longDir()
                ? Stochastic.crossUp(st, i)
                : Stochastic.crossDown(st, i);
    }

    private static double round(double v) {
        return Math.round(v * 100000.0) / 100000.0;
    }
}
