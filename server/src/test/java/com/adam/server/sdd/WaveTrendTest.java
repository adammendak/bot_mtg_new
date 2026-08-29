package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WaveTrendTest {

    private static final Instant T0 = Instant.parse("2026-08-26T00:00:00Z");

    private static Candle bar(int i, double close) {
        return new Candle(T0.plusSeconds(i * 3600L), close, close + 0.5, close - 0.5, close, 1);
    }

    @Test
    void flatMarketOscillatesAroundZero() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candles.add(bar(i, 100));
        }
        List<WaveTrend.Point> wt = WaveTrend.compute(candles);
        assertThat(wt).hasSameSizeAs(candles);
        for (int i = 0; i < wt.size(); i++) {
            assertThat(wt.get(i).wt1()).as("wt1[%d]", i).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1e-6));
            if (i >= 3) {
                assertThat(wt.get(i).wt2()).as("wt2[%d]", i).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1e-6));
            }
        }
    }

    @Test
    void wt1IsPositiveWhileRallyingAndNegativeWhileSelling() {
        List<Candle> candles = new ArrayList<>();
        int i = 0;
        for (int k = 0; k < 30; k++, i++) {
            candles.add(bar(i, 100 + k)); // 100 -> 129
        }
        for (int k = 0; k < 30; k++, i++) {
            candles.add(bar(i, 129 - k)); // 129 -> 100
        }
        List<WaveTrend.Point> wt = WaveTrend.compute(candles);

        assertThat(wt).hasSameSizeAs(candles);
        for (int j = WaveTrend.N2; j < wt.size(); j++) {
            assertThat(Double.isFinite(wt.get(j).wt1())).as("wt1[%d] finite", j).isTrue();
        }
        assertThat(wt.get(25).wt1()).as("mid-rally").isGreaterThan(0);
        assertThat(wt.get(55).wt1()).as("mid-selloff").isLessThan(0);
    }

    @Test
    void thresholdsAreOrdered() {
        assertThat(WaveTrend.OVERSOLD_EXTREME).isLessThan(WaveTrend.OVERSOLD);
        assertThat(WaveTrend.OVERSOLD).isLessThan(WaveTrend.OVERBOUGHT);
        assertThat(WaveTrend.OVERBOUGHT).isLessThan(WaveTrend.OVERBOUGHT_EXTREME);
    }
}
