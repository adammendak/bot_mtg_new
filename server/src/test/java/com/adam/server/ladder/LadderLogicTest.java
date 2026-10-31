package com.adam.server.ladder;

import com.adam.server.ladder.LadderLogic.Decision;
import com.adam.server.ladder.LadderLogic.Params;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LadderLogicTest {

    private static final Params GER = new Params("GER40", "DE40", 0.5, 0.5, 10, 0.2, LocalTime.of(7, 5), 15);
    // Tuesday 2026-11-03 and Saturday 2026-11-07
    private static final Instant TUE_0706 = Instant.parse("2026-11-03T07:06:00Z");

    private static Decision decide(List<Double> open, double bid, Instant now, LocalDate lastDay, double eq, double start) {
        return LadderLogic.decide(GER, open, bid, now, lastDay, eq, start, 25);
    }

    @Test
    void notionalMultipleIsGainOverTakeProfit() {
        assertThat(GER.notionalMultiple()).isEqualTo(0.4);
    }

    @Test
    void flatBuysOnceInTheOpenWindow() {
        Decision d = decide(List.of(), 24000, TUE_0706, null, 10000, 10000);
        assertThat(d.buy()).isTrue();
        assertThat(d.reason()).isEqualTo("DAILY_OPEN");
    }

    @Test
    void flatDoesNothingOutsideTheWindowOrWhenAlreadyDoneToday() {
        assertThat(decide(List.of(), 24000, Instant.parse("2026-11-03T07:04:00Z"), null, 10000, 10000).buy()).isFalse();
        assertThat(decide(List.of(), 24000, Instant.parse("2026-11-03T07:20:00Z"), null, 10000, 10000).buy()).isFalse();
        assertThat(decide(List.of(), 24000, TUE_0706, LocalDate.of(2026, 11, 3), 10000, 10000).buy()).isFalse();
    }

    @Test
    void flatDoesNothingOnTheWeekend() {
        assertThat(decide(List.of(), 24000, Instant.parse("2026-11-07T07:06:00Z"), null, 10000, 10000).buy()).isFalse();
    }

    @Test
    void laddersDownFromTheLowestOpenEntry() {
        List<Double> open = List.of(24000.0, 24100.0);
        // next level = 24000 * 0.995 = 23880
        assertThat(decide(open, 23900, TUE_0706, null, 10000, 10000).buy()).isFalse();
        Decision d = decide(open, 23880, TUE_0706, null, 10000, 10000);
        assertThat(d.buy()).isTrue();
        assertThat(d.reason()).startsWith("LADDER");
    }

    @Test
    void laddersAtAnyTimeOfDay() {
        assertThat(decide(List.of(24000.0), 23800, Instant.parse("2026-11-03T22:30:00Z"), null, 10000, 10000).buy()).isTrue();
    }

    @Test
    void neverExceedsThePositionCap() {
        List<Double> ten = java.util.Collections.nCopies(10, 24000.0);
        Decision d = decide(ten, 20000, TUE_0706, null, 10000, 10000);
        assertThat(d.buy()).isFalse();
        assertThat(d.reason()).contains("cap");
    }

    @Test
    void failsafeStopsNewEntriesWhenEquityIsDown25Percent() {
        Decision d = decide(List.of(24000.0), 23000, TUE_0706, null, 7500, 10000);
        assertThat(d.buy()).isFalse();
        assertThat(d.reason()).startsWith("FAILSAFE");
        assertThat(decide(List.of(24000.0), 23000, TUE_0706, null, 7600, 10000).buy()).isTrue();
    }

    @Test
    void failsafeIsOffWithoutAStartEquity() {
        assertThat(decide(List.of(24000.0), 23000, TUE_0706, null, 100, 0).buy()).isTrue();
    }
}
