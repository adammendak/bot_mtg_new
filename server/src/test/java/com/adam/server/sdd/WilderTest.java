package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WilderTest {

    @Test
    void rmaFirstValueIsSmaThenWilderSmooths() {
        double[] src = {1, 2, 3, 4, 5, 6};
        double[] rma = Wilder.rma(src, 3);
        assertThat(rma[0]).isNaN();
        assertThat(rma[1]).isNaN();
        assertThat(rma[2]).isEqualTo(2.0);
        assertThat(rma[3]).isCloseTo((2.0 * 2 + 4) / 3.0, within(1e-9));
        assertThat(rma[4]).isCloseTo((rma[3] * 2 + 5) / 3.0, within(1e-9));
        assertThat(Wilder.last(rma)).isEqualTo(rma[5]);
    }

    @Test
    void atrUsesTrueRangeAndWilderRma() {
        List<Candle> candles = new ArrayList<>();
        Instant t = Instant.parse("2026-08-26T10:00:00Z");
        candles.add(new Candle(t, 10, 12, 9, 11, 1));
        candles.add(new Candle(t.plusSeconds(3600), 11, 13, 10, 12, 1));
        candles.add(new Candle(t.plusSeconds(7200), 12, 15, 11, 14, 1));
        double[] atr = Wilder.atr(candles, 2);
        assertThat(atr[0]).isNaN();
        assertThat(atr[1]).isGreaterThan(0);
        assertThat(Wilder.last(atr)).isEqualTo(atr[atr.length - 1]);
    }
}
