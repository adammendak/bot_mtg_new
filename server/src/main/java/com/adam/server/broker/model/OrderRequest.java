package com.adam.server.broker.model;

import com.adam.server.broker.Direction;

public record OrderRequest(
        String epic,
        Direction direction,
        double size,
        Double level,
        String type,
        Double stopLevel,
        Double stopDistance,
        Double profitLevel,
        boolean trailingStop
) {
    public static OrderRequest market(String epic, Direction direction, double size, double stopLevel) {
        return new OrderRequest(epic, direction, size, null, "MARKET", stopLevel, null, null, false);
    }

    public static OrderRequest working(
            String epic,
            Direction direction,
            double size,
            double level,
            String type,
            Double stopLevel
    ) {
        return new OrderRequest(epic, direction, size, level, type, stopLevel, null, null, false);
    }
}
