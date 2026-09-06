package com.adam.server.hts;

import com.adam.server.broker.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HA_OKX funding-rate crowding gate ({@link HtsScanService#fundingCrowded}).
 * Defaults: long-max +0.0005, short-min -0.0003 (fraction per ~8h period).
 */
class HtsScanServiceFundingTest {

    private static final double LONG_MAX = 0.0005;
    private static final double SHORT_MIN = -0.0003;

    @Test
    void longIsBlockedOnlyWhenFundingIsAboveTheLongMax() {
        assertThat(HtsScanService.fundingCrowded(Direction.BUY, 0.0009, LONG_MAX, SHORT_MIN)).isTrue();
        assertThat(HtsScanService.fundingCrowded(Direction.BUY, 0.0005, LONG_MAX, SHORT_MIN)).isFalse(); // at the line
        assertThat(HtsScanService.fundingCrowded(Direction.BUY, 0.0001, LONG_MAX, SHORT_MIN)).isFalse();
        assertThat(HtsScanService.fundingCrowded(Direction.BUY, -0.0020, LONG_MAX, SHORT_MIN)).isFalse(); // negative never blocks a long
    }

    @Test
    void shortIsBlockedOnlyWhenFundingIsBelowTheShortMin() {
        assertThat(HtsScanService.fundingCrowded(Direction.SELL, -0.0009, LONG_MAX, SHORT_MIN)).isTrue();
        assertThat(HtsScanService.fundingCrowded(Direction.SELL, -0.0003, LONG_MAX, SHORT_MIN)).isFalse(); // at the line
        assertThat(HtsScanService.fundingCrowded(Direction.SELL, 0.0010, LONG_MAX, SHORT_MIN)).isFalse(); // positive never blocks a short
    }
}
