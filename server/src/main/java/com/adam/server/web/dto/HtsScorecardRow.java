package com.adam.server.web.dto;

import java.time.Instant;

/**
 * Forward-test scorecard for one HTS timeframe model (E-4), aggregated from the
 * {@code hts_trades} table. One row per {@code HtsVariant} — the September
 * keep/drop decision (T11) reads these instead of a backtest.
 *
 * <p>{@code avgR} / {@code sumR} / {@code maxDrawdownR} are in units of the
 * entry→stop distance (a full stop-out ≈ −1.0). {@code maxDrawdownR} is the
 * deepest peak-to-trough drop of the cumulative-R curve over the closed trades
 * in time order.
 */
public record HtsScorecardRow(
        String variant,
        String htf,
        String ltf,
        String book,
        int openTrades,
        int closedTrades,
        int wins,
        int losses,
        double winRate,
        double avgR,
        double sumR,
        double expectancyR,
        double maxDrawdownR,
        Double realisedPnl,
        String pnlCcy,
        Instant lastTradeAt
) {
}
