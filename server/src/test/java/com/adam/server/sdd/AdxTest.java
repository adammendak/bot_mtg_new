package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdxTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    private static Candle bar(int i, double c) {
        return new Candle(T0.plusSeconds(i * 3600L), c, c + 1, c - 1, c, 1);
    }

    @Test
    void strongUptrendGivesHighAdxAndPlusDiOnTop() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            candles.add(bar(i, 100 + i)); // clean monotonic uptrend
        }
        List<Adx.Point> adx = Adx.compute(candles);
        assertThat(adx).hasSameSizeAs(candles);
        Adx.Point last = adx.getLast();
        assertThat(last.adx()).isGreaterThan(Adx.TREND_THRESHOLD);
        assertThat(last.plusDi()).isGreaterThan(last.minusDi());
        assertThat(last.trending()).isTrue();
    }

    @Test
    void flatMarketGivesLowAdx() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            candles.add(bar(i, 100));
        }
        List<Adx.Point> adx = Adx.compute(candles);
        Adx.Point last = adx.getLast();
        // no directional movement → DX/ADX collapse toward 0
        assertThat(last.adx()).isLessThan(Adx.TREND_THRESHOLD);
        assertThat(last.trending()).isFalse();
    }

    @Test
    void warmupBarsAreNaN() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candles.add(bar(i, 100 + i));
        }
        assertThat(Adx.compute(candles).get(0).adx()).isNaN();
    }
}
