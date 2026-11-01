package com.adam.server.ladder;

import com.adam.server.broker.model.Candle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Trend filter of ladder variant B (docs/LADDER-BOT.md): the previous COMPLETED daily close must be above the simple
 * average of the last {@code n} completed daily closes (the previous close included), as in the backtest
 * ({@code tools/long_tp_improve_bt.py}, regime filter).
 */
public final class LadderTrend {

    private LadderTrend() {
    }

    /**
     * @return true = uptrend (entries allowed), false = blocked, null = fewer than {@code n} completed daily candles
     */
    public static Boolean allow(List<Candle> d1, Instant now, int n) {
        if (d1 == null || n <= 0) {
            return null;
        }
        List<Candle> done = new ArrayList<>();
        for (Candle c : d1) {
            if (c.time() != null && !c.time().plus(Duration.ofHours(24)).isAfter(now)) {   // fully elapsed day candle
                done.add(c);
            }
        }
        done.sort(Comparator.comparing(Candle::time));
        if (done.size() < n) {
            return null;
        }
        List<Candle> last = done.subList(done.size() - n, done.size());
        double sum = 0;
        for (Candle c : last) {
            sum += c.close();
        }
        double prev = last.get(last.size() - 1).close();
        return prev > sum / n;
    }
}
