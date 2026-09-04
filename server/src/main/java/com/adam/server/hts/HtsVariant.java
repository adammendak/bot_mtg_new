package com.adam.server.hts;

import com.adam.server.broker.Books;
import com.adam.server.broker.Resolution;

import java.time.Duration;
import java.util.List;

/**
 * The HTS strategy variants run side by side, each on its own Capital.com
 * sub-account:
 *
 * <ul>
 *   <li>{@link #FAST} — ribbon, H1 / M5 → {@code hts} book ("Account m5"), demo</li>
 *   <li>{@link #CORE_LIVE} — ribbon, H4 / M15 → {@code live} book ("bot trading konto"),
 *       <b>real money</b>, 1 % of account risk; gated by
 *       {@code HTS_LIVE_EXECUTION_ENABLED} (separate from the demo flag)</li>
 *   <li>{@link #CORE_OKX} / {@link #FAST_OKX} — ribbon → {@code okx} book (crypto, SWAP), 24/7</li>
 *   <li>{@link #HA4} — HA-hunt cloud, H4 hunt / M15 entry, "HA flip + stack" trigger
 *       → {@code demo} book ("Account m15"); XAU / US100 / USDJPY / GER40, long only.
 *       GER40 is in the universe on request — the H4/M15 backtest found it
 *       net-negative and it is not backed by evidence, unlike the other three.</li>
 *   <li>{@link #HA4X} — same H4 hunt / M15 entry / stop / universe as {@link #HA4},
 *       but the "M15 band cross" trigger → {@code swing} book ("Account H1"); a
 *       side-by-side comparison of the two entry triggers on comparable accounts</li>
 *   <li>{@link #HA1} — H1-HA-hunt cloud, M5 entry, ATR stop / WITH confirm on
 *       M15 (resampled from the M5 feed — no separate broker fetch) →
 *       {@code hts} book ("Account m5"); XAU / US100 / USDJPY, long only.
 *       <b>No backtest evidence</b> — FAST's M5 band-edge stop churns on this
 *       book (avg hold 5–9 min on every symbol but BTC), this swaps in an
 *       ATR-based stop on the same book to see if that structurally holds up.</li>
 * </ul>
 *
 * <p>{@link #CORE}, {@link #SWING}, {@link #HA12} and {@link #FAST} are
 * {@link #parked() parked} — kept in the enum for history but no longer
 * scanned. CORE/SWING (ribbon) gave zero signals through the forward test;
 * HA12 (H12 hunt / H1 entry) gave zero signals in its first two days and was
 * replaced by {@link #HA4X} on the same ("Account H1") book to compare entry
 * triggers instead; FAST churned every non-BTC symbol on M5 and was replaced
 * by {@link #HA1} on the same ("Account m5") book.
 *
 * <p>Ribbon variants ({@link Strategy#RIBBON}) run {@link HtsEngine};
 * HA-hunt variants ({@link Strategy#HA_HUNT}) run {@link HaHuntEngine} with a
 * cloud-hold exit. Within HA-hunt, {@link EntryTrigger} picks how the entry-TF
 * direction is decided (steps 3–7 — hunt gate, RMA-stacked, WITH confirm, daily
 * pivot, universe/side, fill cap — are identical either way).
 */
public enum HtsVariant {

    CORE(Resolution.H4, Resolution.M15, Books.DEMO, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    SWING(Resolution.D1, Resolution.H1, Books.SWING, Duration.ofDays(240), Duration.ofDays(28), 60, false),
    FAST(Resolution.H1, Resolution.M5, Books.HTS, Duration.ofDays(28), Duration.ofDays(6), 5, false),
    CORE_LIVE(Resolution.H4, Resolution.M15, Books.LIVE, Duration.ofDays(80), Duration.ofDays(10), 15, true),
    CORE_OKX(Resolution.H4, Resolution.M15, Books.OKX, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    FAST_OKX(Resolution.H1, Resolution.M5, Books.OKX, Duration.ofDays(28), Duration.ofDays(6), 5, false),

    /** H4-HA-hunt cloud, M15 execution, ATR stop on H1, slow RMA 100. HA-flip entry. */
    HA4(Books.DEMO, Resolution.M15, 15, 4, 1, 100, List.of("XAU", "US100", "USDJPY", "GER40"), EntryTrigger.HA_FLIP),
    /** H12-HA-hunt cloud, H1 execution, ATR stop on H4, slow RMA 144. Parked — see class javadoc. */
    HA12(Books.SWING, Resolution.H1, 60, 12, 4, 144, List.of("XAU", "US100"), EntryTrigger.HA_FLIP),
    /** Same H4/M15/H1 shape as {@link #HA4}, band-cross entry — for comparison on "Account H1". */
    HA4X(Books.SWING, Resolution.M15, 15, 4, 1, 100, List.of("XAU", "US100", "USDJPY", "GER40"), EntryTrigger.BAND_CROSS),
    /** H1-HA-hunt cloud, M5 entry, ATR stop/WITH on M15 (resampled from M5). HA-flip entry. Unvalidated — see class javadoc. */
    HA1(Books.HTS, Resolution.M5, 5, 1, 100, List.of("XAU", "US100", "USDJPY"), EntryTrigger.HA_FLIP, 15,
            Duration.ofDays(6));

    /** Entry model: {@link HtsEngine} ribbon, or {@link HaHuntEngine} HA-hunt cloud. */
    public enum Strategy { RIBBON, HA_HUNT }

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
     * Whether per-signal e-mail is sent for this variant. Only the HA-hunt
     * strategies mail — they are sparse and each fill matters. FAST (M5) and
     * the OKX crypto variants signal too often to mail; CORE_LIVE is silent in
     * practice and its fills are visible on the dashboard / trades feed.
     */
    public boolean mailsSignals() {
        return strategy == Strategy.HA_HUNT;
    }

    /**
     * Parked: kept in the enum but not scanned or traded. CORE / SWING ribbon
     * and HA12 gave zero signals through the forward test; FAST churned every
     * non-BTC M5 symbol (avg hold 5-9 min) on its band-edge stop. All replaced.
     */
    public boolean parked() {
        return this == CORE || this == SWING || this == HA12 || this == FAST;
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
        if (strategy != Strategy.HA_HUNT) {
            return name() + " " + htf + "/" + ltf;
        }
        String trigger = entryTrigger == EntryTrigger.BAND_CROSS ? " band-cross" : "";
        return name() + " H" + huntHours + "-hunt/" + ltf + trigger;
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
     * Whether this model trades {@code symbolCode}. FAST acts on the M5 close and
     * on BTC and EURUSD the fast-band stop there is a tiny fraction of price
     * (~0.15&nbsp;% for BTC, ~0.04&nbsp;% for EURUSD), so it scalps and churns
     * (stops out within minutes, draining the m5 sub-account's free margin).
     * Both stay on the higher-timeframe models; every other FAST symbol and
     * every other model is unaffected.
     */
    public boolean tradesSymbol(String symbolCode) {
        if (this != FAST) {
            return true;
        }
        return !("BTC".equalsIgnoreCase(symbolCode) || "EURUSD".equalsIgnoreCase(symbolCode));
    }
}
