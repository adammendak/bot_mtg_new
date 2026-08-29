package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class CandleFixtures {

    private CandleFixtures() {
    }

    public static List<Candle> rising(Instant start, Duration step, int count, double seed, double increment) {
        List<Candle> out = new ArrayList<>(count);
        Instant t = start;
        double px = seed;
        for (int i = 0; i < count; i++) {
            double open = px;
            double close = px + increment;
            out.add(new Candle(t, open, close + 0.2, open - 0.1, close, 10));
            px = close;
            t = t.plus(step);
        }
        return out;
    }

    public static List<Candle> falling(Instant start, Duration step, int count, double seed, double increment) {
        List<Candle> out = new ArrayList<>(count);
        Instant t = start;
        double px = seed;
        for (int i = 0; i < count; i++) {
            double open = px;
            double close = px - increment;
            out.add(new Candle(t, open, open + 0.1, close - 0.2, close, 10));
            px = close;
            t = t.plus(step);
        }
        return out;
    }
}
