package com.adam.server.history;

import java.util.List;

public record HistoryResponse(
        String book,
        String currency,
        boolean connected,
        List<DailyEquityPoint> points
) {
}
