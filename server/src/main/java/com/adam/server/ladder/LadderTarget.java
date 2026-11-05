package com.adam.server.ladder;

import com.adam.server.broker.model.Candle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Take-profit rule "TP at the prior high" (research: docs/LADDER-TP-TARGETS-2026-11.md): {@code TP = max(static, ATH)}.
 * Near the all-time high the static TP is farther than the ATH, so it stays static; after a pullback the position waits
 * for the previous peak instead. The ATH is the highest high of the COMPLETED daily candles in the fetched window (the
 * 20- and 60-day highs gave the same result in the backtests, so a window shorter than the whole history is enough).
 */
public final class LadderTarget {

    private LadderTarget() {
    }

    /** Highest high of the fully elapsed daily candles, or null when there are none. */
    public static Double priorHigh(List<Candle> d1, Instant now) {
        if (d1 == null) {
            return null;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (Candle c : d1) {
            if (c.time() != null && !c.time().plus(Duration.ofHours(24)).isAfter(now) && c.high() > max) {
                max = c.high();
            }
        }
        return max == Double.NEGATIVE_INFINITY ? null : max;
    }

    /** {@code max(ask x (1 + tpPct/100), priorHigh)}; just the static TP when the prior high is unknown. */
    public static double takeProfit(double ask, double tpPct, Double priorHigh) {
        double fixed = ask * (1.0 + tpPct / 100.0);
        return priorHigh == null ? fixed : Math.max(fixed, priorHigh);
    }
}
