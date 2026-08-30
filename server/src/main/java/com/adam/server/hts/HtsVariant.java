package com.adam.server.hts;

import com.adam.server.broker.Books;
import com.adam.server.broker.Resolution;

import java.time.Duration;

/**
 * The three HTS ("wstęgi") timeframe models run side by side for the September
 * forward test, each on its own Capital.com demo sub-account:
 *
 * <ul>
 *   <li>{@link #CORE} — H4 / M15 → {@code demo} book ("Account m15"), demo</li>
 *   <li>{@link #SWING} — D1 / H1 → {@code swing} book ("Account H1"), demo</li>
 *   <li>{@link #FAST} — H1 / M5 → {@code hts} book ("Account m5"), demo</li>
 *   <li>{@link #CORE_LIVE} — H4 / M15 → {@code live} book ("bot trading konto"),
 *       <b>real money</b>, 1 % of account risk; gated by
 *       {@code HTS_LIVE_EXECUTION_ENABLED} (separate from the demo flag)</li>
 * </ul>
 *
 * Same {@link HtsEngine} for all (it is timeframe-generic); only the pair of
 * resolutions, the target book, and demo-vs-live differ.
 */
public enum HtsVariant {

    CORE(Resolution.H4, Resolution.M15, Books.DEMO, Duration.ofDays(80), Duration.ofDays(10), 15, false),
    SWING(Resolution.D1, Resolution.H1, Books.SWING, Duration.ofDays(240), Duration.ofDays(28), 60, false),
    FAST(Resolution.H1, Resolution.M5, Books.HTS, Duration.ofDays(28), Duration.ofDays(6), 5, false),
    CORE_LIVE(Resolution.H4, Resolution.M15, Books.LIVE, Duration.ofDays(80), Duration.ofDays(10), 15, true);

    private final Resolution htf;
    private final Resolution ltf;
    private final String book;
    private final Duration htfLookback;
    private final Duration ltfLookback;
    private final int ltfMinutes;
    private final boolean live;

    HtsVariant(Resolution htf, Resolution ltf, String book, Duration htfLookback, Duration ltfLookback,
               int ltfMinutes, boolean live) {
        this.htf = htf;
        this.ltf = ltf;
        this.book = book;
        this.htfLookback = htfLookback;
        this.ltfLookback = ltfLookback;
        this.ltfMinutes = ltfMinutes;
        this.live = live;
    }

    /** Real-money account (the {@code live} book) — extra guards + separate enable flag. */
    public boolean live() {
        return live;
    }

    /**
     * True in the 5-minute scan slot right after this model's LTF bar closes, so
     * the every-5-min scheduler only hits Capital for a variant when there is a
     * fresh bar: FAST every pass, CORE on the M15 boundary, SWING on the hour.
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

    /** Capital.com demo book this variant scans and (if enabled) trades. */
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
        return name() + " " + htf + "/" + ltf;
    }
}
