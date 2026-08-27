package com.adam.server.broker.model;

import java.time.Instant;

public record Candle(
        Instant time,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
}
