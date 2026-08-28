package com.adam.server.history;

import java.util.List;

/**
 * Daily equity history for one book plus drawdown metrics (#15): the deepest
 * equity drawdown seen, the current drawdown from the running peak, and how
 * many days the deepest drawdown took to recover (null when still in it).
 */
public record HistoryResponse(
        String book,
        String currency,
        boolean connected,
        List<DailyEquityPoint> points,
        Double maxDrawdownPct,
        Double currentDrawdownPct,
        Integer recoveryDays
) {
}
