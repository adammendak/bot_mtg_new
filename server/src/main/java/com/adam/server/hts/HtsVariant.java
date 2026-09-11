package com.adam.server.hts;

import com.adam.server.broker.Books;
import com.adam.server.broker.Resolution;

import java.time.Duration;
import java.util.List;

/**
 * The HTS strategy variants run side by side, each on its own Capital.com
 * sub-account. As of the ST_V3 rollout, every account runs a Supertrend
 * band-cross variant ({@link Strategy#ST_V3}, {@link StV3Engine}) — the
 * HA-hunt cloud family ({@link #HA4}, {@link #HA12}, {@link #HA1}) is parked
 * on real accounts (only {@link #HA_OKX} on the crypto-only OKX book keeps
 * running its shape) so all four demo books forward-test the same idea:
 *
 * <ul>
 *   <li>{@link #M5_ST_V3} — M5 entry / M45-Supertrend filter+stop →
 *       {@code hts} book ("Account m5"), took it from {@link #HA1} (parked,
 *       no backtest evidence). Universe: {@link #M15_ST_V3B}'s
 *       {@code SATELLITE_UNIVERSE} (see below) — same five tickers.</li>
 *   <li>{@link #M15_ST_V3B} — M15 entry / H1-Supertrend filter+stop →
 *       {@code demo} book ("Account m15"), took it from {@link #HA4} (parked).
 *       Universe: US100/XAU/BTC/GER40/EURUSD — the SAME five tickers as
 *       {@link #M5_ST_V3} and {@link #H1_ST_V3} on purpose: one week of
 *       forward-testing gives a direct per-ticker RR comparison across the
 *       three timeframe pairings instead of three disjoint samples. Also
 *       deliberately overlaps {@link #M15_ST_V3}'s own (wider) universe —
 *       same strategy, two accounts, more forward-test data.</li>
 *   <li>{@link #H1_ST_V3} — H1 entry / H4-Supertrend filter+stop →
 *       {@code swing} book ("Account H1"), took it from {@link #HA12}
 *       (parked). Universe: the same five tickers as {@link #M5_ST_V3} /
 *       {@link #M15_ST_V3B} — the weakest, thinnest-sampled pairing of the
 *       three; kept as a smaller satellite.</li>
 *   <li>{@link #M15_ST_V3} — same shape as {@link #M15_ST_V3B}, on the
 *       {@code mms} book ("Account MMS") {@link #MMS} vacated (parked, no
 *       backtest edge). Universe: all ten backtested tickers (XAU/BTC/US100/
 *       GER40/EURUSD/US500/US30/XAG/J225/USDJPY) — the "broad" book, the
 *       strongest and most consistent of the three pairings on the full set.</li>
 *   <li>{@link #FAST} — ribbon, H1 / M5, parked (zero-edge forward test).</li>
 *   <li>{@link #CORE_LIVE} — ribbon, H4 / M15 → {@code live} book ("bot trading konto"),
 *       <b>real money</b>. <b>Parked / detached</b> — no strategy trades the
 *       live book any more; {@code HTS_LIVE_EXECUTION_ENABLED} (separate from
 *       the demo flag) also defaults to off as a second guard.</li>
 *   <li>{@link #CORE_OKX} / {@link #FAST_OKX} — ribbon → {@code okx} book (crypto, SWAP), parked.</li>
 *   <li>{@link #HA_OKX} — HA-hunt cloud, H4/M15/H1 shape, on the {@code okx}
 *       book: ETH + XRP linear USDT perpetual swaps, long only, plus a
 *       funding-rate crowding skip. Still active — the ST_V3 rollout is
 *       Capital-only so far.</li>
 * </ul>
 *
 * <p>{@link #CORE}, {@link #SWING}, {@link #HA4X}, {@link #FAST},
 * {@link #CORE_LIVE}, {@link #CORE_OKX}, {@link #FAST_OKX}, {@link #MMS},
 * {@link #HA1}, {@link #HA4} and {@link #HA12} are {@link #parked() parked}
 * — kept in the enum but not scanned, each superseded by the ST_V3 variant
 * that now runs its book (see the bullets above for exactly which). Only
 * {@link #HA_OKX} among the pre-ST_V3 lineup is still live, on the
 * crypto-only OKX book the rollout does not touch. {@code live()} is false
 * for every ST_V3 variant.
 *
 * <p>Ribbon variants ({@link Strategy#RIBBON}) run {@link HtsEngine};
 * HA-hunt variants ({@link Strategy#HA_HUNT}) run {@link HaHuntEngine} with a
 * cloud-hold exit. Within HA-hunt, {@link EntryTrigger} picks how the entry-TF
 * direction is decided (steps 3–7 — hunt gate, RMA-stacked, WITH confirm, daily
 * pivot, universe/side, fill cap — are identical either way).
 * MMS ({@link Strategy#MMS}) runs {@link MmsEngine}: fade a TMA/ATR envelope
 * after a closed-bar band touch + first reactive candle; opposite-band or
 * 1:1 TP; mandatory %-of-price SL (no trail); sequential delever after a
 * full base SL (add-on wick stops do not cut the unit). ST_V3
 * ({@link Strategy#ST_V3}) runs {@link StV3Engine}: HTF Supertrend is both
 * the direction filter and the stop; entry is a fresh entry-TF close beyond
 * its own fast RMA band, gated by an HTF structure check; exit (in
 * {@link HtsTradeService#stV3Exit}) is TP1 2:1 on half, breakeven on the
 * rest, full exit when the entry-TF's fast band crosses to the opposite side
 * of its slow band. See each {@code M*_ST_V3*}/{@code H1_ST_V3} constant's
 * own javadoc for the backtest behind its specific timeframe pairing and
 * universe pick.
 */
public enum HtsVariant {

    CORE(Resolution.H4, Resolution.M15, Books.DEMO, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    SWING(Resolution.D1, Resolution.H1, Books.SWING, Duration.ofDays(240), Duration.ofDays(28), 60, false),
    FAST(Resolution.H1, Resolution.M5, Books.HTS, Duration.ofDays(28), Duration.ofDays(6), 5, false),
    CORE_LIVE(Resolution.H4, Resolution.M15, Books.LIVE, Duration.ofDays(80), Duration.ofDays(10), 15, true),
    CORE_OKX(Resolution.H4, Resolution.M15, Books.OKX, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    FAST_OKX(Resolution.H1, Resolution.M5, Books.OKX, Duration.ofDays(28), Duration.ofDays(6), 5, false),

    // Shared HA-hunt universe: backtest — XAU/XAG strongest, J225/USDJPY good,
    // US100 weak diversifier, GER40 net-negative (removed). See HaHunt.UNIVERSE.

    /** H4-HA-hunt cloud, M15 execution, ATR stop on H1, slow RMA 100. HA-flip entry. */
    HA4(Books.DEMO, Resolution.M15, 15, 4, 1, 100, HaHunt.UNIVERSE, EntryTrigger.HA_FLIP),
    /** H12-HA-hunt cloud, H1 execution, ATR stop on H4, slow RMA 144. Backtest's strongest config. */
    HA12(Books.SWING, Resolution.H1, 60, 12, 4, 144, HaHunt.UNIVERSE, EntryTrigger.HA_FLIP),
    /** Same H4/M15/H1 shape as {@link #HA4}, band-cross entry. Parked — lost the A/B on the numbers. */
    HA4X(Books.SWING, Resolution.M15, 15, 4, 1, 100, HaHunt.UNIVERSE, EntryTrigger.BAND_CROSS),
    /** H1-HA-hunt cloud, M5 entry, ATR stop/WITH on M15 (resampled from M5). HA-flip entry. Unvalidated — see class javadoc. */
    HA1(Books.HTS, Resolution.M5, 5, 1, 100, List.of("XAU", "US100", "USDJPY"), EntryTrigger.HA_FLIP, 15,
            Duration.ofDays(6)),
    /**
     * Same H4-hunt / M15-entry / H1-ATR-stop / slow-RMA-100 / cloud-hold shape as
     * {@link #HA4}, ported to the OKX {@code okx} book — <b>ETH + XRP</b> linear
     * USDT perpetual swaps, long only. Adds a funding-rate crowding skip (see
     * {@link HaHuntEngine} / {@code HtsScanService}): a long is not executed when
     * OKX funding is extreme-positive (crowded longs). OKX HA-hunt backtest
     * (12&nbsp;mo, no fees): BTC PF ~0.8 (net loser), ETH ~1.2, XRP ~1.6 —
     * dropped BTC, added XRP. Execution stays gated by
     * {@code OKX_LIVE_EXECUTION_ENABLED}.
     */
    HA_OKX(Books.OKX, Resolution.M15, 15, 4, 1, 100, HaHunt.OKX_UNIVERSE, EntryTrigger.HA_FLIP),

    /**
     * MastermindZX MMS mean-reversion — TMA ± ATR envelope on the entry TF
     * (default M15; prefer M10–M30 for algo, H1 for a manual base; exclude M5
     * as noise; H4 = trend/range context, D1 = bias/sizing — not entry TFs).
     * TP {@link com.adam.server.hts.MmsEngine.TpMode#OPPOSITE_BAND} (site) or
     * {@link com.adam.server.hts.MmsEngine.TpMode#FIXED_1R} (tester clips).
     * Mandatory SL, no trail. Optional one-bar add-on ×1 (default off).
     * Universe: BTC (Capital {@code BTCUSD} / OKX {@code BTC-USDT-SWAP} if
     * remapped), XAU/GOLD, US100/NQ. Both sides. <b>Parked</b> — no backtest
     * edge (PF 0.79-0.94, see {@code docs/MMS-STRATEGY.md}); {@link #M15_ST_V3}
     * took the {@code mms} book instead. Site: https://mastermindzx.pl/
     * (BTCUSDT monthly-optimised backtests; no WR copied into code).
     */
    MMS(Books.MMS, Resolution.M15, 15, Duration.ofDays(15), Mms.UNIVERSE),

    /**
     * M15 entry / H1-Supertrend filter+stop (ATR 7, factor 2.0 — PR
     * #144/#146's locked research default, not {@link com.adam.server.sdd.Supertrend}'s
     * own factor 3.0; ATR 10/factor 2.0 backtested to within noise of this on
     * the band-cross exit, no reason to diverge from the Pine lock). TP1 2:1
     * on half, then the remaining half's stop jumps once to breakeven and
     * sits there — no trail — until the M15 fast RMA band crosses to the
     * opposite side of its slow band ({@link HtsTradeService#stV3Exit}).
     * H1 structure gate required: H1 close stacked vs its own RMA33/144, OR
     * the M15 close already beyond the closed H1 fast band, in the
     * Supertrend direction. Cap 2 fills per Supertrend regime, no pyramiding
     * (backtested WORSE on this shape: PF 1.54/1.61 -&gt; 1.02/1.10, MaxDD
     * 13.5% -&gt; 55.9%). Both sides.
     *
     * <p>The band-cross exit replaced an earlier HA-flip runner (PR
     * #139/#140's original lock) after a proper 10-ticker backtest: HA-flip
     * scored PF 1.33 IS / 1.23 OOS on BTC/XAU/US100 only; band-cross scores
     * PF 2.10 IS / 1.65 OOS across ALL TEN tickers tested (XAU/BTC/US100/
     * GER40/EURUSD/US500/US30/XAG/J225/USDJPY) — every single one positive
     * in BOTH windows (12&nbsp;mo IS 2025-09→2026-09 + 12&nbsp;mo OOS
     * 2024-10→2025-09, no fees), zero flips. XAU alone: PF 3.10 IS / 2.45
     * OOS. Trade-off: fatter tail than HA-flip (median trade ≈ -0.5R — most
     * trades are small losses) carried by rare large trend catches (seen up
     * to +35R, held 6-11 days) because the exit rarely fires while a trend
     * is still expanding — a confirmed swing-style preference, not a bug.
     * See {@code pine/M15_FINAL.pine}'s header for the full comparison.
     * Universe is all ten tested tickers — the "broad" book. Took the
     * {@code mms} book ("Account MMS") from {@link #MMS} (no edge, parked).
     */
    M15_ST_V3(Resolution.M15, Books.MMS, Duration.ofDays(10), 15, 60, StV3.FULL_UNIVERSE),

    /**
     * Concentrated satellite copy of {@link #M15_ST_V3} (same H1/M15
     * band-cross shape) on its own book/universe so the same strategy can be
     * forward-tested on two accounts at once — deliberately overlapping
     * tickers with {@link #M15_ST_V3}, not a partition. Universe is
     * {@code StV3.SATELLITE_UNIVERSE} (US100/XAU/BTC/GER40/EURUSD) — the
     * SAME five tickers as {@link #M5_ST_V3} and {@link #H1_ST_V3}, chosen
     * deliberately so a week of forward-testing gives a direct per-ticker
     * RR comparison across all three timeframe pairings instead of three
     * disjoint samples. Took {@link #HA4}'s {@code demo} book
     * ("Account m15") — HA4 is parked (see {@link #parked()}).
     */
    M15_ST_V3B(Resolution.M15, Books.DEMO, Duration.ofDays(10), 15, 60, StV3.SATELLITE_UNIVERSE),

    /**
     * M5 entry / M45-Supertrend filter+stop — same shape as {@link #M15_ST_V3}
     * one timeframe rung down. Backtest (12mo IS/OOS, no fees, band-cross
     * exit): every one of the 10 tested tickers positive in both windows,
     * combined PF 1.87 IS / 1.98 OOS — broadly positive but noisier than
     * M15/H1 per-symbol. Universe is {@code StV3.SATELLITE_UNIVERSE}
     * (US100/XAU/BTC/GER40/EURUSD) — same five tickers as
     * {@link #M15_ST_V3B} and {@link #H1_ST_V3}, for the cross-timeframe RR
     * comparison. Took {@link #HA1}'s {@code hts} book ("Account m5") — HA1
     * is parked.
     */
    M5_ST_V3(Resolution.M5, Books.HTS, Duration.ofDays(12), 5, 45, StV3.SATELLITE_UNIVERSE),

    /**
     * H1 entry / H4-Supertrend filter+stop — one timeframe rung up from
     * {@link #M15_ST_V3}. Fires far less often than M15/H1 (~1/4 the
     * signals) and is noisier per-symbol (thin per-symbol samples, ~25-50
     * trades/12mo) despite a strong combined OOS (PF 2.04) vs a weak
     * combined IS (PF 1.56) — kept as a smaller satellite, not the primary
     * pick. Universe is {@code StV3.SATELLITE_UNIVERSE}
     * (US100/XAU/BTC/GER40/EURUSD) — same five tickers as
     * {@link #M5_ST_V3} and {@link #M15_ST_V3B}, for the cross-timeframe RR
     * comparison. Took {@link #HA12}'s {@code swing} book
     * ("Account H1") — HA12 is parked.
     */
    H1_ST_V3(Resolution.H1, Books.SWING, Duration.ofDays(35), 60, 240, StV3.SATELLITE_UNIVERSE);

    /** Entry model: {@link HtsEngine} ribbon, {@link HaHuntEngine} HA-hunt, {@link MmsEngine}, or {@link StV3Engine}. */
    public enum Strategy { RIBBON, HA_HUNT, MMS, ST_V3 }

    /**
     * How an HA-hunt variant decides entry-TF direction (the hunt gate, RMA
     * stacked check, WITH confirm, pivot, universe/side and fill cap are the
     * same for both):
     * <ul>
     *   <li>{@link #HA_FLIP} — entry-TF Heikin-Ashi colour flips on the just-closed bar.</li>
     *   <li>{@link #BAND_CROSS} — entry-TF close closes beyond the fast RMA band,
     *       fast band clear of the slow band, on the first bar this becomes true
     *       (not a persisting state).</li>
     * </ul>
     */
    public enum EntryTrigger { HA_FLIP, BAND_CROSS }

    /** Holder so the shared lists can be referenced from the enum constants above. */
    private static final class HaHunt {
        static final java.util.List<String> UNIVERSE = java.util.List.of("XAU", "XAG", "J225", "USDJPY", "US100");
        /** OKX perps — codes match {@link com.adam.server.broker.okx.OkxSymbol}. */
        static final java.util.List<String> OKX_UNIVERSE = java.util.List.of("ETH", "XRP");
    }

    /** MastermindZX MMS: BTC + gold + Nasdaq (Capital epics BTCUSD / GOLD / US100). */
    private static final class Mms {
        static final java.util.List<String> UNIVERSE = java.util.List.of("BTC", "XAU", "US100");
    }

    /** ST_V3 universes — see each enum constant's javadoc for the backtest behind its pick. */
    private static final class StV3 {
        /** {@link HtsVariant#M15_ST_V3} — every ticker backtested, all ten. */
        static final java.util.List<String> FULL_UNIVERSE = java.util.List.of(
                "XAU", "BTC", "US100", "GER40", "EURUSD", "US500", "US30", "XAG", "J225", "USDJPY");
        /**
         * The SAME five tickers on every satellite book ({@link HtsVariant#M5_ST_V3},
         * {@link HtsVariant#M15_ST_V3B}, {@link HtsVariant#H1_ST_V3}) — deliberate,
         * so a week of forward-testing gives a direct per-ticker RR comparison
         * across the three timeframe pairings (M45/M5 vs H1/M15 vs H4/H1) instead
         * of three disjoint samples.
         */
        static final java.util.List<String> SATELLITE_UNIVERSE = java.util.List.of(
                "US100", "XAU", "BTC", "GER40", "EURUSD");
    }

    private final Strategy strategy;
    private final Resolution htf;
    private final Resolution ltf;
    private final String book;
    private final Duration htfLookback;
    private final Duration ltfLookback;
    private final int ltfMinutes;
    private final boolean live;
    // HA-hunt only:
    private final int huntHours;
    private final int atrHours;
    /** ATR/WITH timeframe in minutes instead of hours (resampled from the entry TF, not H1) — 0 = use atrHours. */
    private final int atrMinutes;
    private final int slowLen;
    private final List<String> universe;
    private final boolean longOnly;
    private final EntryTrigger entryTrigger;

    /** Ribbon variant. */
    HtsVariant(Resolution htf, Resolution ltf, String book, Duration htfLookback, Duration ltfLookback,
               int ltfMinutes, boolean live) {
        this(Strategy.RIBBON, htf, ltf, book, htfLookback, ltfLookback, ltfMinutes, live,
                0, 0, 0, 0, List.of(), false, EntryTrigger.HA_FLIP);
    }

    /**
     * HA-hunt cloud variant, ATR/WITH timeframe in whole hours. {@code entryTf}
     * is what the broker is asked for; hunt/stop TFs are resampled from H1.
     */
    HtsVariant(String book, Resolution entryTf, int ltfMinutes, int huntHours, int atrHours,
               int slowLen, List<String> universe, EntryTrigger entryTrigger) {
        this(Strategy.HA_HUNT, null, entryTf, book, Duration.ofDays(60), Duration.ofDays(20), ltfMinutes, false,
                huntHours, atrHours, 0, slowLen, universe, true, entryTrigger);
    }

    /**
     * HA-hunt cloud variant whose ATR/WITH timeframe is finer than an hour
     * (e.g. M15), resampled from the entry-TF feed instead of H1 — for a hunt
     * TF that is itself only H1 (a bare hour), there is no room for a whole-hour
     * "mid" timeframe between the hunt and the entry. Takes its own entry-TF
     * lookback (a fine entry TF like M5 needs far fewer calendar days of
     * history than M15/H1 do, and fetching 20 days of M5 per symbol per scan
     * would be a lot of avoidable Capital API calls).
     */
    HtsVariant(String book, Resolution entryTf, int ltfMinutes, int huntHours,
               int slowLen, List<String> universe, EntryTrigger entryTrigger, int atrMinutes,
               Duration ltfLookback) {
        this(Strategy.HA_HUNT, null, entryTf, book, Duration.ofDays(60), ltfLookback, ltfMinutes, false,
                huntHours, 0, atrMinutes, slowLen, universe, true, entryTrigger);
    }

    /**
     * MMS mean-reversion: entry TF only (no hunt resample), both sides,
     * {@code live=false}. Parked separately — this constructor does not enable
     * execution.
     */
    HtsVariant(String book, Resolution entryTf, int ltfMinutes, Duration ltfLookback, List<String> universe) {
        this(Strategy.MMS, null, entryTf, book, Duration.ofDays(20), ltfLookback, ltfMinutes, false,
                0, 0, 0, 0, universe, false, EntryTrigger.HA_FLIP);
    }

    /**
     * ST_V3 variant: entry-TF band-cross gated by an HTF Supertrend that is
     * both the direction filter and the stop-loss, TP1 2:1 half + BE +
     * band-cross runner (all fixed in {@link StV3Engine} — this shape only
     * carries the book/lookback/universe/HTF span; {@code stHtfMinutes} is
     * stored in the otherwise-unused (for this strategy) {@code atrMinutes}
     * field: 45 = M45, 60 = H1, 240 = H4).
     */
    HtsVariant(Resolution entryTf, String book, Duration ltfLookback, int ltfMinutes, int stHtfMinutes,
               List<String> universe) {
        this(Strategy.ST_V3, null, entryTf, book, Duration.ofDays(60), ltfLookback, ltfMinutes, false,
                0, 0, stHtfMinutes, 0, universe, false, EntryTrigger.HA_FLIP);
    }

    HtsVariant(Strategy strategy, Resolution htf, Resolution ltf, String book, Duration htfLookback,
               Duration ltfLookback, int ltfMinutes, boolean live, int huntHours, int atrHours, int atrMinutes,
               int slowLen, List<String> universe, boolean longOnly, EntryTrigger entryTrigger) {
        this.strategy = strategy;
        this.htf = htf;
        this.ltf = ltf;
        this.book = book;
        this.htfLookback = htfLookback;
        this.ltfLookback = ltfLookback;
        this.ltfMinutes = ltfMinutes;
        this.live = live;
        this.huntHours = huntHours;
        this.atrHours = atrHours;
        this.atrMinutes = atrMinutes;
        this.slowLen = slowLen;
        this.universe = universe;
        this.longOnly = longOnly;
        this.entryTrigger = entryTrigger;
    }

    public Strategy strategy() {
        return strategy;
    }

    public EntryTrigger entryTrigger() {
        return entryTrigger;
    }

    /**
     * Whether per-signal e-mail is sent for this variant. Mail is restricted to
     * <b>H1-entry</b> variants only (currently {@link #HA12}) — M15/M5 entries
     * fire too often for an inbox; each still shows up on the dashboard / trades
     * feed. This also silences {@link #MMS} (M15 entry) regardless of
     * {@code app.mms.mail-enabled} — that toggle no longer has an effect while
     * MMS stays on M15.
     */
    public boolean mailsSignals() {
        return (strategy == Strategy.HA_HUNT || strategy == Strategy.MMS) && ltf == Resolution.H1;
    }

    /**
     * Parked: kept in the enum but not scanned or traded. CORE / SWING ribbon
     * gave zero signals; FAST churned every non-BTC M5 symbol (avg hold 5-9 min)
     * on its band-edge stop; HA4X (band-cross entry) lost the HA-flip vs
     * band-cross A/B on the backtest (PF ~1.2 IS / ~0.8 recent, MaxDD ~30%).
     * {@link #CORE_LIVE} (the only real-money variant) is parked too — the
     * live account is detached, every strategy now trades demo/swing/hts books
     * only; {@code HTS_LIVE_EXECUTION_ENABLED} stays as a second guard.
     *
     * <p>{@link #CORE_OKX} / {@link #FAST_OKX} (ribbon on the real-money OKX
     * book) are parked too: once {@code OKX_LIVE_EXECUTION_ENABLED} is armed for
     * {@link #HA_OKX}, FAST_OKX's M5 ribbon would churn LTC/BTC every 5&nbsp;min
     * (the same reason {@link #FAST} is parked) and CORE_OKX ribbon has the same
     * zero-edge history as CORE. Only {@link #HA_OKX} trades the OKX book now.
     * {@link #MMS} is parked too — no backtest edge — and {@link #M15_ST_V3}
     * took its {@code mms} book.
     *
     * <p>The ST_V3 rollout parks the whole HA-hunt-cloud-on-Capital lineup —
     * {@link #HA1}, {@link #HA4}, {@link #HA12} — each superseded on its own
     * book by an ST_V3 variant (see the class javadoc's bullet list for
     * exactly which). {@link #HA_OKX} is unaffected — the OKX crypto book is
     * outside this rollout.
     */
    public boolean parked() {
        return this == CORE || this == SWING || this == HA4X || this == FAST
                || this == CORE_OKX || this == FAST_OKX || this == CORE_LIVE || this == MMS
                || this == HA1 || this == HA4 || this == HA12;
    }

    /** Real-money account (the {@code live} book) — extra guards + separate enable flag. */
    public boolean live() {
        return live;
    }

    /**
     * True in the 5-minute scan slot right after this model's LTF bar closes, so
     * the every-5-min scheduler only hits Capital for a variant when there is a
     * fresh bar: FAST every pass, CORE/HA4/HA4X on the M15 boundary, SWING/HA12 on the hour.
     */
    public boolean dueAtMinute(int minuteOfHour) {
        return minuteOfHour % ltfMinutes < 5;
    }

    public Resolution htf() {
        return htf;
    }

    /**
     * Non-null higher-timeframe label for persistence / display. HA-hunt variants
     * have no {@link Resolution} htf (the hunt runs on a resampled H4/H12 series),
     * so this returns {@code "H4"} / {@code "H12"} for them.
     */
    public String htfLabel() {
        if (strategy == Strategy.MMS) {
            return ltf != null ? ltf.name() : "M15";
        }
        if (strategy == Strategy.ST_V3) {
            return switch (atrMinutes) {
                case 45 -> "M45";
                case 240 -> "H4";
                default -> "H1";
            };
        }
        return htf != null ? htf.name() : "H" + huntHours;
    }

    public Resolution ltf() {
        return ltf;
    }

    /** Capital.com book this variant scans and (if enabled) trades. */
    public String book() {
        return book;
    }

    public Duration htfLookback() {
        return htfLookback;
    }

    public Duration ltfLookback() {
        return ltfLookback;
    }

    public String label() {
        if (strategy == Strategy.MMS) {
            return name() + " " + ltf + " TMA-ATR";
        }
        if (strategy == Strategy.ST_V3) {
            return name() + " " + htfLabel() + "-ST/" + ltf;
        }
        if (strategy != Strategy.HA_HUNT) {
            return name() + " " + htf + "/" + ltf;
        }
        String trigger = entryTrigger == EntryTrigger.BAND_CROSS ? " band-cross" : "";
        return name() + " H" + huntHours + "-hunt/" + ltf + trigger;
    }

    /** Entry-TF bar length in minutes (M15 = 15, H1 = 60). Used to drop a forming bar. */
    public int ltfMinutes() {
        return ltfMinutes;
    }

    // ---- HA-hunt accessors ----
    public int huntHours() {
        return huntHours;
    }

    public int atrHours() {
        return atrHours;
    }

    /** ATR/WITH timeframe in minutes, resampled from the entry TF — 0 means "use atrHours instead". */
    public int atrMinutes() {
        return atrMinutes;
    }

    public int slowLen() {
        return slowLen;
    }

    public List<String> universe() {
        return universe;
    }

    public boolean longOnly() {
        return longOnly;
    }

    /**
     * Whether this model trades {@code symbolCode}.
     *
     * <ul>
     *   <li>{@link #FAST} skips BTC / EURUSD — the M5 fast-band stop there is a
     *       tiny fraction of price (~0.15&nbsp;% BTC, ~0.04&nbsp;% EURUSD), so it
     *       scalps and churns the m5 sub-account. Both stay on the HTF models.</li>
     *   <li>{@link #CORE_LIVE} skips GER40 — on the <b>real-money</b> book it
     *       stacked and re-entered GER40 every M15 bar (ids 85–102, ~10&nbsp;min
     *       each) and GER40 is net-negative in the HA-hunt backtest across every
     *       variant. The other CORE_LIVE names are unaffected.</li>
     * </ul>
     */
    public boolean tradesSymbol(String symbolCode) {
        if (this == FAST) {
            return !("BTC".equalsIgnoreCase(symbolCode) || "EURUSD".equalsIgnoreCase(symbolCode));
        }
        if (this == CORE_LIVE) {
            return !"GER40".equalsIgnoreCase(symbolCode);
        }
        if (strategy == Strategy.MMS || strategy == Strategy.ST_V3) {
            if (symbolCode == null) {
                return false;
            }
            for (String code : universe) {
                if (code.equalsIgnoreCase(symbolCode)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
