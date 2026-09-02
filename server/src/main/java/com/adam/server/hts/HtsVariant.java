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
 *   <li>{@link #HA4} — HA-hunt cloud, H4 hunt / M15 entry → {@code demo} book
 *       ("Account m15"); XAU / US100 / USDJPY, long only</li>
 *   <li>{@link #HA12} — HA-hunt cloud, H12 hunt / H1 entry → {@code swing} book
 *       ("Account H1"); XAU / US100, long only</li>
 * </ul>
 *
 * <p>{@link #CORE} and {@link #SWING} (the original ribbon H4/M15 and D1/H1
 * models) are {@link #parked() parked} — kept in the enum for history but no
 * longer scanned; {@link #HA4} / {@link #HA12} took over their demo books.
 *
 * <p>Ribbon variants ({@link Strategy#RIBBON}) run {@link HtsEngine};
 * HA-hunt variants ({@link Strategy#HA_HUNT}) run {@link HaHuntEngine} with a
 * cloud-hold exit.
 */
public enum HtsVariant {

    CORE(Resolution.H4, Resolution.M15, Books.DEMO, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    SWING(Resolution.D1, Resolution.H1, Books.SWING, Duration.ofDays(240), Duration.ofDays(28), 60, false),
    FAST(Resolution.H1, Resolution.M5, Books.HTS, Duration.ofDays(28), Duration.ofDays(6), 5, false),
    CORE_LIVE(Resolution.H4, Resolution.M15, Books.LIVE, Duration.ofDays(80), Duration.ofDays(10), 15, true),
    CORE_OKX(Resolution.H4, Resolution.M15, Books.OKX, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    FAST_OKX(Resolution.H1, Resolution.M5, Books.OKX, Duration.ofDays(28), Duration.ofDays(6), 5, false),

    /** H4-HA-hunt cloud, M15 execution, ATR stop on H1, slow RMA 100. */
    HA4(Books.DEMO, Resolution.M15, 15, 4, 1, 100, List.of("XAU", "US100", "USDJPY")),
    /** H12-HA-hunt cloud, H1 execution, ATR stop on H4, slow RMA 144. */
    HA12(Books.SWING, Resolution.H1, 60, 12, 4, 144, List.of("XAU", "US100"));

    /** Entry model: {@link HtsEngine} ribbon, or {@link HaHuntEngine} HA-hunt cloud. */
    public enum Strategy { RIBBON, HA_HUNT }

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
    private final int slowLen;
    private final List<String> universe;
    private final boolean longOnly;

    /** Ribbon variant. */
    HtsVariant(Resolution htf, Resolution ltf, String book, Duration htfLookback, Duration ltfLookback,
               int ltfMinutes, boolean live) {
        this(Strategy.RIBBON, htf, ltf, book, htfLookback, ltfLookback, ltfMinutes, live,
                0, 0, 0, List.of(), false);
    }

    /** HA-hunt cloud variant. {@code entryTf} is what the broker is asked for; hunt/stop TFs are resampled from H1. */
    HtsVariant(String book, Resolution entryTf, int ltfMinutes, int huntHours, int atrHours,
               int slowLen, List<String> universe) {
        this(Strategy.HA_HUNT, null, entryTf, book, Duration.ofDays(60), Duration.ofDays(20), ltfMinutes, false,
                huntHours, atrHours, slowLen, universe, true);
    }

    HtsVariant(Strategy strategy, Resolution htf, Resolution ltf, String book, Duration htfLookback,
               Duration ltfLookback, int ltfMinutes, boolean live, int huntHours, int atrHours,
               int slowLen, List<String> universe, boolean longOnly) {
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
        this.slowLen = slowLen;
        this.universe = universe;
        this.longOnly = longOnly;
    }

    public Strategy strategy() {
        return strategy;
    }

    /**
     * Parked: kept in the enum but not scanned or traded. CORE / SWING ribbon
     * gave zero signals through the forward test and were replaced by the
     * HA-hunt variants on the same demo books.
     */
    public boolean parked() {
        return this == CORE || this == SWING;
    }

    /** Real-money account (the {@code live} book) — extra guards + separate enable flag. */
    public boolean live() {
        return live;
    }

    /**
     * True in the 5-minute scan slot right after this model's LTF bar closes, so
     * the every-5-min scheduler only hits Capital for a variant when there is a
     * fresh bar: FAST every pass, CORE/HA4 on the M15 boundary, SWING/HA12 on the hour.
     */
    public boolean dueAtMinute(int minuteOfHour) {
        return minuteOfHour % ltfMinutes < 5;
    }

    public Resolution htf() {
        return htf;
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
        return strategy == Strategy.HA_HUNT
                ? name() + " H" + huntHours + "-hunt/" + ltf
                : name() + " " + htf + "/" + ltf;
    }

    // ---- HA-hunt accessors ----
    public int huntHours() {
        return huntHours;
    }

    public int atrHours() {
        return atrHours;
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
