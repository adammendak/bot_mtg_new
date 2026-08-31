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
        boolean tradeable,            // false only when the broker says the market is CLOSED / OFFLINE / etc.
        double marginFactor,          // fraction of notional required as margin (0.05 = 20:1); 0 = unknown
        String currency,              // the instrument's quote currency, e.g. "EUR"; null = unknown
        double pointValue,            // value of one price point per 1.0 size, in `currency`; 0 = unknown
        double priceStep              // min price increment (e.g. 0.05); 0 = use decimal places only
) {

    /**
     * Rules without a market-status signal (paper broker, failed lookup, or a
     * caller that only cares about size/precision) — assume tradeable so a
     * missing status never blocks execution.
     */
    public MarketRules(String epic, double minDealSize, int priceDecimalPlaces,
                       double minStopDistancePoints, double maxStopDistancePoints) {
        this(epic, minDealSize, priceDecimalPlaces, minStopDistancePoints, maxStopDistancePoints, true, 0, null, 0);
    }

    /** Rules with a tradeable flag but no margin / currency / point-value info. */
    public MarketRules(String epic, double minDealSize, int priceDecimalPlaces,
                       double minStopDistancePoints, double maxStopDistancePoints, boolean tradeable) {
        this(epic, minDealSize, priceDecimalPlaces, minStopDistancePoints, maxStopDistancePoints, tradeable, 0, null, 0, 0);
    }

    /** Rules with margin + currency but no point value (defaults point value to 1). */
    public MarketRules(String epic, double minDealSize, int priceDecimalPlaces,
                       double minStopDistancePoints, double maxStopDistancePoints, boolean tradeable,
                       double marginFactor, String currency) {
        this(epic, minDealSize, priceDecimalPlaces, minStopDistancePoints, maxStopDistancePoints,
                tradeable, marginFactor, currency, 0, 0);
    }

    /** Rules with everything but a price step (step defaults to decimal-place rounding). */
    public MarketRules(String epic, double minDealSize, int priceDecimalPlaces,
                       double minStopDistancePoints, double maxStopDistancePoints, boolean tradeable,
                       double marginFactor, String currency, double pointValue) {
        this(epic, minDealSize, priceDecimalPlaces, minStopDistancePoints, maxStopDistancePoints,
                tradeable, marginFactor, currency, pointValue, 0);
    }

    public static MarketRules permissive(String epic) {
        return new MarketRules(epic, 0, -1, 0, 0, true, 0, null, 0, 0);
    }

    /**
     * Round a price (stop / limit level) to what the broker accepts: to the
     * instrument's price step when known (e.g. 0.05 for BTC/USD — a 2-decimal
     * value like 78381.21 is otherwise rejected), else to the decimal places.
     */
    public double roundPrice(double level) {
        if (priceStep > 0) {
            double snapped = Math.round(level / priceStep) * priceStep;
            // kill binary-float tails (78381.200000001) — clamp to a sane precision
            int dp = priceDecimalPlaces >= 0 ? priceDecimalPlaces : Math.max(0, stepDecimals());
            double f = Math.pow(10, dp);
            return Math.round(snapped * f) / f;
        }
        if (priceDecimalPlaces < 0) {
            return level;
        }
        double f = Math.pow(10, priceDecimalPlaces);
        return Math.round(level * f) / f;
    }

    private int stepDecimals() {
        if (priceStep <= 0 || priceStep >= 1) {
            return 0;
        }
        int d = 0;
        double v = priceStep;
        while (v < 1 && d < 8) {
            v *= 10;
            d++;
        }
        return d;
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
