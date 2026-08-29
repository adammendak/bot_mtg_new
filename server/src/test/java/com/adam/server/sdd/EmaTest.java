package com.adam.server.sdd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EmaTest {

    @Test
    void seedsWithFirstValueThenSmooths() {
        // period 3 -> alpha = 2 / 4 = 0.5
        double[] ema = Ema.of(new double[]{2, 4, 6}, 3);
        assertThat(ema[0]).isEqualTo(2.0);
        assertThat(ema[1]).isCloseTo(0.5 * 4 + 0.5 * 2, within(1e-9)); // 3.0
        assertThat(ema[2]).isCloseTo(0.5 * 6 + 0.5 * 3, within(1e-9)); // 4.5
    }

    @Test
    void constantInputStaysConstant() {
        double[] ema = Ema.of(new double[]{5, 5, 5, 5, 5}, 4);
        for (double v : ema) {
            assertThat(v).isEqualTo(5.0);
        }
    }

    @Test
    void emptyOrBadPeriodIsAllNaN() {
        assertThat(Ema.of(new double[0], 5)).isEmpty();
        double[] bad = Ema.of(new double[]{1, 2, 3}, 0);
        assertThat(bad).containsExactly(Double.NaN, Double.NaN, Double.NaN);
    }
}
