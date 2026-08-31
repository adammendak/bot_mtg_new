package com.adam.server.broker.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRulesTest {

    private MarketRules withStep(int dp, double step) {
        return new MarketRules("X", 0.001, dp, 0, 0, true, 0, null, 0, step);
    }

    @Test
    void roundPriceSnapsToThePriceStepWhenKnown() {
        MarketRules r = withStep(2, 0.05);
        assertThat(r.roundPrice(78381.2083)).isEqualTo(78381.20);   // .21 (2dp) would be rejected
        assertThat(r.roundPrice(78381.23)).isEqualTo(78381.25);
        assertThat(r.roundPrice(78381.00)).isEqualTo(78381.00);
    }

    @Test
    void roundPriceFallsBackToDecimalPlacesWithoutAStep() {
        MarketRules r = withStep(1, 0);
        assertThat(r.roundPrice(26519.05)).isEqualTo(26519.1);
    }

    @Test
    void roundPriceIsIdentityWhenPrecisionIsUnknown() {
        MarketRules r = new MarketRules("X", 0, -1, 0, 0);
        assertThat(r.roundPrice(123.456789)).isEqualTo(123.456789);
    }
}
