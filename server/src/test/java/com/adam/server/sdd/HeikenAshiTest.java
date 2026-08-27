package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeikenAshiTest {

    @Test
    void firstBarUsesMidpointOpenAndTypicalClose() {
        Candle c = new Candle(Instant.parse("2026-08-26T10:00:00Z"), 10, 14, 8, 12, 1);
        HeikenAshi.Bar ha = HeikenAshi.from(List.of(c)).getFirst();
        assertThat(ha.open()).isEqualTo(11.0);
        assertThat(ha.close()).isEqualTo(11.0);
        assertThat(ha.high()).isEqualTo(14.0);
        assertThat(ha.low()).isEqualTo(8.0);
        assertThat(ha.bullish()).isTrue();
    }

    @Test
    void subsequentOpenIsAverageOfPreviousHaOpenAndClose() {
        Candle a = new Candle(Instant.parse("2026-08-26T10:00:00Z"), 10, 12, 9, 11, 1);
        Candle b = new Candle(Instant.parse("2026-08-26T10:15:00Z"), 11, 16, 10, 15, 1);
        List<HeikenAshi.Bar> ha = HeikenAshi.from(List.of(a, b));
        double expectedOpen = (ha.getFirst().open() + ha.getFirst().close()) / 2.0;
        assertThat(ha.get(1).open()).isEqualTo(expectedOpen);
        assertThat(ha.get(1).close()).isEqualTo((11 + 16 + 10 + 15) / 4.0);
        assertThat(ha.get(1).bullish()).isTrue();
    }

    @Test
    void bearishBarFlipsColor() {
        Candle green = new Candle(Instant.parse("2026-08-26T10:00:00Z"), 10, 14, 10, 14, 1);
        Candle red = new Candle(Instant.parse("2026-08-26T10:15:00Z"), 14, 14, 6, 6, 1);
        List<HeikenAshi.Bar> ha = HeikenAshi.from(List.of(green, red));
        assertThat(ha.getFirst().bullish()).isTrue();
        assertThat(ha.get(1).bullish()).isFalse();
    }
}
