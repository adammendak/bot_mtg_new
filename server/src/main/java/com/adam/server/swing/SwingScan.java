package com.adam.server.swing;

import com.adam.server.broker.Direction;

import java.time.Instant;

/**
 * A swing signal: entry taken after an H1 candle closes in the direction of the
 * H4 context. Structure is defined up front so the dashboard/backtest could
 * render it later, but the engine does not emit signals yet.
 *
 * @param timestamp   H1 bar close that triggered the entry consideration
 * @param symbol      instrument code (GER40, XAU, …)
 * @param epic        Capital.com epic
 * @param direction   BUY / SELL
 * @param entry       intended entry level (filled at H1 close)
 * @param stopLevel   stop placement (structural, above/below H4 swing)
 * @param targetLevel  1R target
 * @param h4Trend      H4 context at decision time: UP / DOWN / FLAT
 */
public record SwingScan(
        Instant timestamp,
        String symbol,
        String epic,
        Direction direction,
        double entry,
        double stopLevel,
        double targetLevel,
        H4Trend h4Trend
) {
    public enum H4Trend {
        UP,
        DOWN,
        FLAT
    }
}
