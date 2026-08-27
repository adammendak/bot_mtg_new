package com.adam.server.broker.model;

import com.adam.server.broker.Direction;

import java.time.Instant;

public record Position(
        String dealId,
        String dealReference,
        String epic,
        Direction direction,
        double size,
        double level,
        Double stopLevel,
        Double profitLevel,
        double unrealizedPnl,
        String currency,
        Instant openedAt
) {
}
