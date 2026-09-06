package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AtrEnvelopeTest {

    @Test
    void smaIsTheMeanOfTheLastNCloses() {
        double[] src = {1, 2, 3, 4, 5};
        double[] sma = AtrEnvelope.sma(src, 3);
        assertThat(sma[0]).isNaN();
        assertThat(sma[1]).isNaN();
        assertThat(sma[2]).isCloseTo(2.0, within(1e-9));
        assertThat(sma[3]).isCloseTo(3.0, within(1e-9));
        assertThat(sma[4]).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void tmaIsSmaOfSmaAndDoesNotLookAhead() {
        double[] src = {10, 10, 10, 10, 10, 20};
        double[] tma = AtrEnvelope.tma(src, 3);
        // last value uses only src[0..5]; a lookahead TMA would pull a future 20 earlier
        assertThat(tma[2]).isNaN(); // SMA(SMA) needs 2*3-2 = 4 bars → index 4
        assertThat(tma[4]).isCloseTo(10.0, within(1e-9));
        assertThat(tma[5]).isGreaterThan(10.0);
    }

    @Test
    void tmaAtrBandsAreSymmetricAroundTheCentre() {
        Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            bars.add(new Candle(t0.plusSeconds(i * 900L), 100, 101, 99, 100, 0));
        }
        AtrEnvelope.Series env = AtrEnvelope.of(bars, 20, 2.0, AtrEnvelope.Mode.TMA_ATR);
        int i = bars.size() - 1;
        assertThat(env.ready(i)).isTrue();
        assertThat(env.centre()[i]).isCloseTo(100.0, within(1e-6));
        double half = env.upper()[i] - env.centre()[i];
        assertThat(half).isPositive();
        assertThat(env.lower()[i]).isCloseTo(env.centre()[i] - half, within(1e-9));
    }
}
