package com.adam.server.broker.model;

public record Account(
        String id,
        String name,
        String currency,
        double balance,
        double available,
        double profitLoss,
        boolean preferred
) {
}
