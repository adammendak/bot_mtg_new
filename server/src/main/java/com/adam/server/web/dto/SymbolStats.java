package com.adam.server.web.dto;

/**
 * Per-symbol performance statistics for a book, derived from the broker's
 * closed-trade transactions: how often the symbol won, the average R-multiple
 * per trade, and the profit factor (gross win / gross loss). {@code enabled}
 * reflects the local soft-disable for weaker symbols.
 */
public record SymbolStats(
        String symbol,
        String epic,
        int trades,
        int wins,
        int losses,
        double winRate,
        double avgWin,
        double avgLoss,
        double expectancy,
        double profitFactor,
        boolean enabled
) {
}
