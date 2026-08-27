package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class HeikenAshi {

    private HeikenAshi() {
    }

    public record Bar(Instant time, double open, double high, double low, double close) {
        public boolean bullish() {
            return close >= open;
        }
    }

    public static List<Bar> from(List<Candle> candles) {
        List<Bar> out = new ArrayList<>(candles.size());
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double haClose = (c.open() + c.high() + c.low() + c.close()) / 4.0;
            double haOpen;
            if (i == 0) {
                haOpen = (c.open() + c.close()) / 2.0;
            } else {
                Bar prev = out.get(i - 1);
                haOpen = (prev.open() + prev.close()) / 2.0;
            }
            double haHigh = Math.max(c.high(), Math.max(haOpen, haClose));
            double haLow = Math.min(c.low(), Math.min(haOpen, haClose));
            out.add(new Bar(c.time(), haOpen, haHigh, haLow, haClose));
        }
        return out;
    }
}
