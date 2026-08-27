package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Classic floor pivot from the previous Warsaw session. Session rolls at 21:00 Europe/Warsaw.
 */
public final class PivotPoints {

    public static final LocalTime ROLL = LocalTime.of(21, 0);

    private PivotPoints() {
    }

    public record Levels(double pp, double r1, double s1, LocalDate sessionDay) {
    }

    public static LocalDate sessionDay(Instant time, ZoneId zone) {
        ZonedDateTime z = time.atZone(zone);
        if (z.toLocalTime().compareTo(ROLL) >= 0) {
            return z.toLocalDate().plusDays(1);
        }
        return z.toLocalDate();
    }

    public static Levels previousCompleted(List<Candle> candles, Instant asOf, ZoneId zone) {
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        LocalDate current = sessionDay(asOf, zone);
        Map<LocalDate, Ohlc> byDay = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate day = sessionDay(c.time(), zone);
            byDay.compute(day, (k, existing) -> {
                if (existing == null) {
                    return new Ohlc(c.open(), c.high(), c.low(), c.close());
                }
                existing.high = Math.max(existing.high, c.high());
                existing.low = Math.min(existing.low, c.low());
                existing.close = c.close();
                return existing;
            });
        }
        LocalDate prev = null;
        for (LocalDate day : byDay.keySet()) {
            if (day.isBefore(current)) {
                prev = day;
            }
        }
        if (prev == null) {
            return null;
        }
        Ohlc ohlc = byDay.get(prev);
        double pp = (ohlc.high + ohlc.low + ohlc.close) / 3.0;
        double r1 = 2 * pp - ohlc.low;
        double s1 = 2 * pp - ohlc.high;
        return new Levels(pp, r1, s1, prev);
    }

    /**
     * BUY requires close above PP; SELL requires close below PP.
     */
    public static boolean aligned(double close, double pp, boolean buy) {
        return buy ? close > pp : close < pp;
    }

    public static Map<LocalDate, Ohlc> sessions(List<Candle> candles, ZoneId zone) {
        Map<LocalDate, Ohlc> byDay = new HashMap<>();
        for (Candle c : candles) {
            LocalDate day = sessionDay(c.time(), zone);
            byDay.compute(day, (k, existing) -> {
                if (existing == null) {
                    return new Ohlc(c.open(), c.high(), c.low(), c.close());
                }
                existing.high = Math.max(existing.high, c.high());
                existing.low = Math.min(existing.low, c.low());
                existing.close = c.close();
                return existing;
            });
        }
        return byDay;
    }

    public static final class Ohlc {
        double open;
        double high;
        double low;
        double close;

        Ohlc(double open, double high, double low, double close) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }
}
