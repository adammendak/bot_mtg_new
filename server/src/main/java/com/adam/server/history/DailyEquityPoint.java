package com.adam.server.history;

import java.time.LocalDate;

public record DailyEquityPoint(
        LocalDate date,
        Double equity,
        Double dayPnl,
        Double pctChange
) {
}
