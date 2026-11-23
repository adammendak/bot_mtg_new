package com.adam.server.sdd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KalmanTest {

    /** c[i] = 100 + 0.05 i + sin(i/7) for 400 bars, then 160 bars of c[-1] - 0.4 + 0.3 sin(i/3). */
    static double[] series() {
        double[] c = new double[560];
        for (int i = 0; i < 400; i++) {
            c[i] = 100 + 0.05 * i + Math.sin(i / 7.0);
        }
        for (int i = 0; i < 160; i++) {
            c[400 + i] = c[399 + i] - 0.4 + 0.3 * Math.sin(i / 3.0);
        }
        return c;
    }

    @Test
    void matchesTheReferenceImplementationOfTheTradingViewIndicator() {
        // reference values from the line-by-line python port of the Pine kalman_filter helper
        double[] s = Kalman.filter(series(), 18);
        double[] l = Kalman.filter(series(), 144);
        assertThat(s[10]).isCloseTo(101.01421667429912, within(1e-9));
        assertThat(l[10]).isCloseTo(100.74843234572832, within(1e-9));
        assertThat(s[300]).isCloseTo(113.97414558446035, within(1e-9));
        assertThat(l[300]).isCloseTo(112.66000456455104, within(1e-9));
        assertThat(s[559]).isCloseTo(59.530484408744364, within(1e-9));
        assertThat(l[559]).isCloseTo(74.64638188421064, within(1e-9));
    }

    @Test
    void firstBarHasNoSeedAndEmptyInputIsEmpty() {
        double[] f = Kalman.filter(new double[]{5, 6, 7}, 18);
        assertThat(Double.isNaN(f[0])).isTrue();
        // the error term already advanced on bar 0: gain0 = 1/(1+0.18), err = (1-gain0) + Q/18
        double err = (1 - 1.0 / 1.18) + 0.1 / 18;
        double gain1 = err / (err + 0.18);
        assertThat(f[1]).isCloseTo(5.0 + gain1 * (6.0 - 5.0), within(1e-12));
        assertThat(Kalman.filter(new double[0], 18)).isEmpty();
    }
}
