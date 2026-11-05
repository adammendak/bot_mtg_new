package com.adam.server.ladder;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LadderTargetTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private static Candle day(String date, double high) {
        return new Candle(Instant.parse(date + "T00:00:00Z"), high, high, high, high, 0);
    }

    @Test
    void priorHighIgnoresTheCandleOfTheDayInProgress() {
        List<Candle> d1 = List.of(day("2026-11-01", 100), day("2026-11-02", 120), day("2026-11-03", 999));
        assertThat(LadderTarget.priorHigh(d1, NOW)).isEqualTo(120.0);
    }

    @Test
    void priorHighIsNullWithoutCompletedCandles() {
        assertThat(LadderTarget.priorHigh(List.of(), NOW)).isNull();
        assertThat(LadderTarget.priorHigh(null, NOW)).isNull();
        assertThat(LadderTarget.priorHigh(List.of(day("2026-11-03", 999)), NOW)).isNull();
    }

    @Test
    void takeProfitIsTheFartherOfStaticAndPriorHigh() {
        assertThat(LadderTarget.takeProfit(100, 0.5, 103.0)).isEqualTo(103.0);
        assertThat(LadderTarget.takeProfit(100, 0.5, 100.2)).isCloseTo(100.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(LadderTarget.takeProfit(100, 0.5, null)).isCloseTo(100.5, org.assertj.core.data.Offset.offset(1e-9));
    }
}
