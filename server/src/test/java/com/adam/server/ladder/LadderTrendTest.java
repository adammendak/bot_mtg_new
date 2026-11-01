package com.adam.server.ladder;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LadderTrendTest {

    private static final Instant NOW = Instant.parse("2026-11-03T07:00:00Z");

    private static List<Candle> closes(double... closes) {
        List<Candle> out = new ArrayList<>();
        Instant first = NOW.minus(Duration.ofDays(closes.length));
        for (int i = 0; i < closes.length; i++) {
            out.add(new Candle(first.plus(Duration.ofDays(i)), closes[i], closes[i], closes[i], closes[i], 0));
        }
        return out;
    }

    @Test
    void previousCloseAboveTheAverageAllowsEntries() {
        assertThat(LadderTrend.allow(closes(10, 11, 12, 13, 14), NOW, 5)).isTrue();
    }

    @Test
    void previousCloseBelowTheAverageBlocksEntries() {
        assertThat(LadderTrend.allow(closes(14, 13, 12, 11, 10), NOW, 5)).isFalse();
    }

    @Test
    void notEnoughCompletedCandlesIsUnknown() {
        assertThat(LadderTrend.allow(closes(10, 11, 12), NOW, 5)).isNull();
    }

    @Test
    void theCandleOfTheRunningDayIsIgnored() {
        List<Candle> d = closes(10, 11, 12, 13, 14);
        d.add(new Candle(NOW.minus(Duration.ofHours(2)), 1, 1, 1, 1, 0));    // today's partial candle, far below
        assertThat(LadderTrend.allow(d, NOW, 5)).isTrue();
    }
}
