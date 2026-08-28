package com.adam.server.web.dto;

/**
 * One row of the all-accounts overview dashboard. Carries the same data as
 * {@link AccountView} plus the book kind (DEMO / LIVE / MAIN), the strategy
 * attached to the book, whether execution is enabled for it, and a live count
 * of open positions with the summed unrealised P/L — all in a single response
 * so the overview page needs just one request.
 */
public record OverviewView(
        String id,
        String broker,
        String kind,
        String displayName,
        String accountName,
        String strategy,
        boolean executionEnabled,
        Double equity,
        Double available,
        Double dayPnl,
        String currency,
        boolean connected,
        String error,
        int positionsCount,
        Double positionsPnl
) {
}
