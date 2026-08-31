package com.adam.server.broker.model;

/**
 * Instrument dealing rules needed to make an order acceptable to the broker: the
 * deal-size step / minimum, the price precision for stop and limit levels, and
 * the minimum (and optional maximum) stop distance. Distances are normalised to
 * price points. Unknown values are left at the "do not adjust" sentinels so a
 * permissive broker (paper, or a failed lookup) never changes the order.
 */
public record MarketRules(
        String epic,
        double minDealSize,           // also the size increment; 0 = unknown, do not adjust
        int priceDecimalPlaces,       // -1 = unknown, do not round
        double minStopDistancePoints, // 0 = unknown
        double maxStopDistancePoints, // 0 = unknown / no cap
        boolean tradeable             // false only when the broker says the market is CLOSED / OFFLINE / etc.
) {

    /**
     * Rules without a market-status signal (paper broker, failed lookup, or a
     * caller that only cares about size/precision) — assume tradeable so a
     * missing status never blocks execution.
     */
    public MarketRules(String epic, double minDealSize, int priceDecimalPlaces,
                       double minStopDistancePoints, double maxStopDistancePoints) {
        this(epic, minDealSize, priceDecimalPlaces, minStopDistancePoints, maxStopDistancePoints, true);
    }

    public static MarketRules permissive(String epic) {
        return new MarketRules(epic, 0, -1, 0, 0, true);
    }

    /** Round a price (stop / limit level) to the instrument precision. */
    public double roundPrice(double level) {
        if (priceDecimalPlaces < 0) {
            return level;
        }
        double f = Math.pow(10, priceDecimalPlaces);
        return Math.round(level * f) / f;
    }

    /** Snap a deal size to the instrument step, never below the minimum. */
    public double roundSize(double size) {
        if (minDealSize <= 0) {
            return size;
        }
        double snapped = Math.round(size / minDealSize) * minDealSize;
        if (snapped < minDealSize) {
            snapped = minDealSize;
        }
        double f = Math.pow(10, sizeDecimals());
        return Math.round(snapped * f) / f;
    }

    private int sizeDecimals() {
        if (minDealSize <= 0 || minDealSize >= 1) {
            return 0;
        }
        int d = 0;
        double v = minDealSize;
        while (v < 1 && d < 8) {
            v *= 10;
            d++;
        }
        return d;
    }
}
