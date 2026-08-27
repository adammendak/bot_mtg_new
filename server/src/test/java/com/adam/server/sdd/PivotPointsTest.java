package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PivotPointsTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    void sessionRollsAtWarsaw2100() {
        Instant before = ZonedDateTime.of(2026, 8, 26, 20, 59, 0, 0, WARSAW).toInstant();
        Instant after = ZonedDateTime.of(2026, 8, 26, 21, 0, 0, 0, WARSAW).toInstant();
        assertThat(PivotPoints.sessionDay(before, WARSAW)).isEqualTo(LocalDate.of(2026, 8, 26));
        assertThat(PivotPoints.sessionDay(after, WARSAW)).isEqualTo(LocalDate.of(2026, 8, 27));
    }

    @Test
    void previousCompletedSessionYieldsClassicFloorPivots() {
        ZoneId zone = WARSAW;
        Instant prevOpen = ZonedDateTime.of(2026, 8, 24, 21, 0, 0, 0, zone).toInstant();
        Instant prevClose = ZonedDateTime.of(2026, 8, 25, 20, 45, 0, 0, zone).toInstant();
        Instant current = ZonedDateTime.of(2026, 8, 26, 10, 0, 0, 0, zone).toInstant();
        List<Candle> candles = List.of(
                new Candle(prevOpen, 100, 110, 90, 105, 1),
                new Candle(prevClose, 105, 120, 95, 108, 1),
                new Candle(current, 108, 109, 107, 108.5, 1)
        );
        PivotPoints.Levels levels = PivotPoints.previousCompleted(candles, current, zone);
        assertThat(levels).isNotNull();
        assertThat(levels.sessionDay()).isEqualTo(LocalDate.of(2026, 8, 25));
        double pp = (120 + 90 + 108) / 3.0;
        assertThat(levels.pp()).isCloseTo(pp, within(1e-9));
        assertThat(levels.r1()).isCloseTo(2 * pp - 90, within(1e-9));
        assertThat(levels.s1()).isCloseTo(2 * pp - 120, within(1e-9));
        assertThat(PivotPoints.aligned(pp + 1, pp, true)).isTrue();
        assertThat(PivotPoints.aligned(pp - 1, pp, true)).isFalse();
        assertThat(PivotPoints.aligned(pp - 1, pp, false)).isTrue();
    }
}
