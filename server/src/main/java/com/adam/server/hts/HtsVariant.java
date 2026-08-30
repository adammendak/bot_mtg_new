package com.adam.server.hts;

import com.adam.server.broker.Books;
import com.adam.server.broker.Resolution;

import java.time.Duration;

/**
 * The three HTS ("wstęgi") timeframe models run side by side for the September
 * forward test, each on its own Capital.com demo sub-account:
 *
 * <ul>
 *   <li>{@link #CORE} — H4 context / M15 entry → {@code demo} book ("Account m15")</li>
 *   <li>{@link #SWING} — D1 context / H1 entry → {@code swing} book ("Account H1")</li>
 *   <li>{@link #FAST} — H1 context / M5 entry → {@code hts} book ("Account m5")</li>
 * </ul>
 *
 * Same {@link HtsEngine} for all three (it is timeframe-generic); only the pair
 * of resolutions and the target book differ.
 */
public enum HtsVariant {

    CORE(Resolution.H4, Resolution.M15, Books.DEMO, Duration.ofDays(140), Duration.ofDays(12)),
    SWING(Resolution.D1, Resolution.H1, Books.SWING, Duration.ofDays(260), Duration.ofDays(30)),
    FAST(Resolution.H1, Resolution.M5, Books.HTS, Duration.ofDays(30), Duration.ofDays(4));

    private final Resolution htf;
    private final Resolution ltf;
    private final String book;
    private final Duration htfLookback;
    private final Duration ltfLookback;

    HtsVariant(Resolution htf, Resolution ltf, String book, Duration htfLookback, Duration ltfLookback) {
        this.htf = htf;
        this.ltf = ltf;
        this.book = book;
        this.htfLookback = htfLookback;
        this.ltfLookback = ltfLookback;
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
