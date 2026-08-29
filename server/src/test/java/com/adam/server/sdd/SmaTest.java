package com.adam.server.sdd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SmaTest {

    @Test
    void nanUntilWindowFullThenRollingMean() {
        double[] sma = Sma.of(new double[]{1, 2, 3, 4, 5}, 3);
        assertThat(sma[0]).isNaN();
        assertThat(sma[1]).isNaN();
        assertThat(sma[2]).isCloseTo(2.0, within(1e-9)); // (1+2+3)/3
        assertThat(sma[3]).isCloseTo(3.0, within(1e-9)); // (2+3+4)/3
        assertThat(sma[4]).isCloseTo(4.0, within(1e-9)); // (3+4+5)/3
    }

    @Test
    void shorterThanPeriodIsAllNaN() {
        assertThat(Sma.of(new double[]{1, 2}, 3)).containsExactly(Double.NaN, Double.NaN);
    }
}
