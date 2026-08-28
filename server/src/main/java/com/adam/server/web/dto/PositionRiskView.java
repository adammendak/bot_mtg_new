package com.adam.server.web.dto;

import com.adam.server.broker.Direction;

/**
 * One open position plus its cash risk in the position currency: 1R in
 * currency = |entry − stop| × size (only defined when a stop level is set).
 * The dashboard shows this as "1R" so every position's exact money at risk is
 * visible at a glance.
 */
public record PositionRiskView(
        String dealId,
        String epic,
        Direction direction,
        double size,
        double level,
        Double stopLevel,
        double unrealizedPnl,
        String currency,
        Double riskPln
) {

    /** Worst-case cash risk if this position's stop is hit; null when no stop level. */
    public static Double riskOf(Direction direction, double level, Double stopLevel, double size) {
        if (stopLevel == null) {
            return null;
        }
        double distance = Direction.BUY == direction
                ? level - stopLevel
                : stopLevel - level;
        return distance > 0 ? distance * size : 0.0;
    }
}
