package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupertrendTest {

    private static List<Candle> series(double... closes) {
        List<Candle> out = new ArrayList<>();
        Instant t = Instant.parse("2026-08-26T00:00:00Z");
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(new Candle(t.plusSeconds(i * 3600L), c, c + 1, c - 1, c, 1));
        }
        return out;
    }

    @Test
    void warmupBarsHaveNoTrendThenSeedsUp() {
        List<Candle> candles = series(10, 11, 12, 13, 14);
        List<Supertrend.Point> st = Supertrend.compute(candles, 3, 1.0);

        assertThat(st).hasSameSizeAs(candles);
        assertThat(st.get(0).trend()).isZero();
        assertThat(st.get(0).line()).isNaN();
        assertThat(st.get(1).trend()).isZero();
        assertThat(st.get(2).trend()).isEqualTo(1); // first ATR-defined bar seeds up
    }

    @Test
    void trendLineTrailsBelowPriceInAnUptrend() {
        List<Candle> candles = series(10, 11, 12, 13, 14);
        List<Supertrend.Point> st = Supertrend.compute(candles, 3, 1.0);

        Supertrend.Point last = st.getLast();
        assertThat(last.trend()).isEqualTo(1);
        assertThat(last.line()).isLessThan(candles.getLast().close());
        assertThat(last.flipUp()).isFalse();
        assertThat(last.flipDown()).isFalse();
    }

    @Test
    void sharpDropBelowTheBandFlipsTrendDown() {
        // rally then a crash on bar 5
        List<Candle> candles = series(10, 11, 12, 13, 14, 8, 7);
        List<Supertrend.Point> st = Supertrend.compute(candles, 3, 1.0);

        assertThat(st.get(4).trend()).isEqualTo(1);
        assertThat(st.get(5).trend()).isEqualTo(-1);
        assertThat(st.get(5).flipDown()).isTrue();
        assertThat(st.get(5).flipUp()).isFalse();
        assertThat(st.get(5).line()).isGreaterThan(candles.get(5).close()); // line above price in a downtrend
        assertThat(st.get(6).trend()).isEqualTo(-1); // trend persists
        assertThat(st.get(6).flipDown()).isFalse();
    }
}
