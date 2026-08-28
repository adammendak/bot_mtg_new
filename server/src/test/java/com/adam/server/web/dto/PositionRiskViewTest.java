package com.adam.server.web.dto;

import com.adam.server.broker.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PositionRiskViewTest {

    @Test
    void buyRiskIsEntryMinusStopTimesSize() {
        assertThat(PositionRiskView.riskOf(Direction.BUY, 20000, 19900.0, 2.0)).isEqualTo(200.0);
    }

    @Test
    void sellRiskIsStopMinusEntryTimesSize() {
        assertThat(PositionRiskView.riskOf(Direction.SELL, 18000, 18100.0, 1.5)).isEqualTo(150.0);
    }

    @Test
    void noStopIsNull() {
        assertThat(PositionRiskView.riskOf(Direction.BUY, 20000, null, 1.0)).isNull();
    }

    @Test
    void invertedStopYieldsZeroNotNegative() {
        assertThat(PositionRiskView.riskOf(Direction.BUY, 20000, 20010.0, 1.0)).isEqualTo(0.0);
    }
}
