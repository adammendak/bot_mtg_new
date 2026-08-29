package com.adam.server.swing;

import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Skeleton for the SDD-SWING strategy — the "place" the user asked to reserve.
 *
 * <p>Concept (not implemented yet):
 * <ul>
 *   <li>Higher timeframe than SDD-M15: entries are considered only after an
 *       <b>H1 candle closes</b> (instead of M15).</li>
 *   <li>The <b>H4 context</b> decides the bias: only trade in the direction of
 *       the prevailing H4 swing (UP / DOWN / FLAT).</li>
 *   <li>More swing-oriented: wider 1R in currency, structural stops above/below
 *       H4 swing points, slower cadence (a handful of trades a week instead of
 *       several a day).</li>
 * </ul>
 *
 * <p>Deliberately NOT wired to any broker account, scheduler, execution gate or
 * webhook. Feed it candles and it returns an empty signal list until the logic
 * is implemented. The {@link SwingSignalProvider} interface is the seam to plug
 * in later without touching the rest of the app.
 */
public final class SddSwingEngine {

    private final ZoneId zone;

    public SddSwingEngine(AppProperties properties) {
        this.zone = ZoneId.of(properties.getTimezone());
    }

    /**
     * Evaluate the swing setup for one symbol at {@code now}.
     *
     * @param h1Closed closed H1 candles up to and including the trigger bar
     * @param h4Closed closed H4 candles (context)
     * @return current swing scan for the symbol, or {@code null} when no setup
     * @implNote TODO: implement the H1-entry-under-H4-context rules.
     */
    public SwingScan evaluate(SwingSymbol symbol, String epic, List<Candle> h1Closed, List<Candle> h4Closed,
                              Instant now) {
        // No implementation yet — reserved seam. Always no setup.
        return null;
    }
}
