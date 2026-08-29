package com.adam.server.web.dto;

/**
 * One replayed SDD-SWING trade — the per-trade granularity the portfolio equity
 * simulator (tools/equity_simulator.py) needs: aggregate win rate / avg R is not
 * enough to compound a single shared account or measure max drawdown.
 *
 * <p>{@code rMultiple} is expressed in <b>units of the stop distance</b>: a full
 * stop-out is exactly {@code -1.0}, a target hit is {@code +targetAtr/stopMult},
 * and a trade still open at the end of the look-ahead window is marked to market
 * ({@code (last close - entry) / stopDistance}, signed by direction).
 */
public record SwingTradeRow(
        String entryTime,   // ISO local date-time, UTC (yyyy-MM-dd'T'HH:mm:ss)
        String exitTime,    // same format; window end when the trade never resolved
        String symbol,
        String direction,   // LONG / SHORT
        String result,      // WIN / LOSS / OPEN
        double rMultiple
) {
}
