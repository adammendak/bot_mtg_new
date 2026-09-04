package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResampleTest {

    /** 24 hourly bars starting at a UTC midnight → six clean H4 buckets. */
    private List<Candle> h1Day(Instant start) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double base = 100 + i;
            out.add(new Candle(start.plusSeconds(i * 3600L), base, base + 2, base - 1, base + 1, 0));
        }
        return out;
    }

    @Test
    void bucketsH1IntoFourHourWindowsOnUtcBoundaries() {
        Instant midnight = Instant.parse("2026-09-01T00:00:00Z");
        // "now" well past the day so every bucket is closed
        List<Candle> h4 = Resample.toHours(h1Day(midnight), 4, midnight.plusSeconds(48 * 3600L));

        assertThat(h4).hasSize(6);
        assertThat(h4.getFirst().time()).isEqualTo(midnight);
        // first bucket = hours 0..3: open of h0, close of h3, high/low across the four
        assertThat(h4.getFirst().open()).isEqualTo(100.0);
        assertThat(h4.getFirst().close()).isEqualTo(104.0);            // base 103 + 1
        assertThat(h4.getFirst().high()).isEqualTo(105.0);             // base 103 + 2
        assertThat(h4.getFirst().low()).isEqualTo(99.0);               // base 100 - 1
        assertThat(h4.get(1).time()).isEqualTo(midnight.plusSeconds(4 * 3600L));
    }

    @Test
    void excludesTheStillOpenTrailingBucket() {
        Instant midnight = Instant.parse("2026-09-01T00:00:00Z");
        // now = 06:00 → the 04:00–07:59 bucket has not closed yet
        List<Candle> h4 = Resample.toHours(h1Day(midnight), 4, midnight.plusSeconds(6 * 3600L));

        assertThat(h4).hasSize(1);
        assertThat(h4.getFirst().time()).isEqualTo(midnight);
    }

    /** 60 five-minute bars starting at a UTC boundary → up to 20 M15 buckets. */
    private List<Candle> m5Hour(Instant start) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double base = 100 + i;
            out.add(new Candle(start.plusSeconds(i * 300L), base, base + 1, base - 0.5, base + 0.5, 0));
        }
        return out;
    }

    @Test
    void bucketsM5IntoFifteenMinuteWindows() {
        Instant midnight = Instant.parse("2026-09-01T00:00:00Z");
        // "now" = +2h -> only the 8 buckets fully closed by then (2h / 15min)
        List<Candle> m15 = Resample.toMinutes(m5Hour(midnight), 15, midnight.plusSeconds(2 * 3600L));

        assertThat(m15).hasSize(8);
        assertThat(m15.getFirst().time()).isEqualTo(midnight);
        assertThat(m15.get(1).time()).isEqualTo(midnight.plusSeconds(15 * 60L));
        // each bucket = 3 five-minute bars: open of the first, close of the third
        assertThat(m15.getFirst().open()).isEqualTo(100.0);
        assertThat(m15.getFirst().close()).isEqualTo(102.5); // base 102 + 0.5
    }

    @Test
    void twelveHourBucketsSplitTheDayInTwo() {
        Instant midnight = Instant.parse("2026-09-01T00:00:00Z");
        List<Candle> h12 = Resample.toHours(h1Day(midnight), 12, midnight.plusSeconds(48 * 3600L));

        assertThat(h12).hasSize(2);
        assertThat(h12.getFirst().open()).isEqualTo(100.0);
        assertThat(h12.getFirst().close()).isEqualTo(112.0);           // base 111 + 1
        assertThat(h12.get(1).time()).isEqualTo(midnight.plusSeconds(12 * 3600L));
    }
}
