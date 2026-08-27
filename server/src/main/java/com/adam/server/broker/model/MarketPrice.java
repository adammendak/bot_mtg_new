package com.adam.server.broker.model;

import java.time.Instant;

public record MarketPrice(String epic, double bid, double ask, Instant updatedAt) {
    public double mid() {
        return (bid + ask) / 2.0;
    }
}
