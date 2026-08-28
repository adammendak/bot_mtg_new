package com.adam.server.scan;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskExposureTest {

    private static Position pos(String epic, Direction dir, double size, double level, Double stop) {
        return new Position("d1", "r1", epic, dir, size, level, stop, null, 0.0, "PLN", null);
    }

    @Test
    void worstCaseIsZeroWithoutStop() {
        assertThat(RiskExposure.worstCasePln(pos("US100", Direction.BUY, 1.0, 20000, null))).isEqualTo(0.0);
    }

    @Test
    void buyWorstCaseIsEntryMinusStopTimesSize() {
        assertThat(RiskExposure.worstCasePln(pos("US100", Direction.BUY, 2.0, 20000, 19900.0)))
                .isEqualTo(100.0 * 2.0);
    }

    @Test
    void sellWorstCaseIsStopMinusEntryTimesSize() {
        assertThat(RiskExposure.worstCasePln(pos("GER40", Direction.SELL, 1.0, 18000, 18100.0)))
                .isEqualTo(100.0);
    }

    @Test
    void sameSideCorrelatedLegsAreConcentrated() {
        // US100 BUY risk 100 + GER40 BUY risk 50 in the same group, same direction.
        List<Position> positions = List.of(
                pos("US100", Direction.BUY, 1.0, 20000, 19900.0),
                pos("GER40", Direction.BUY, 1.0, 18000, 17950.0)
        );
        double[] out = RiskExposure.compute(positions);
        assertThat(out[0]).isEqualTo(150.0);   // correlated = full sum (concentration)
        assertThat(out[1]).isEqualTo(150.0);   // effective = full sum (same side adds up)
    }

    @Test
    void oppositeSideCorrelatedLegsHedge() {
        // US100 BUY risk 100 + GER40 SELL risk 50 -> opposite legs net out to 50.
        List<Position> positions = List.of(
                pos("US100", Direction.BUY, 1.0, 20000, 19900.0),
                pos("GER40", Direction.SELL, 1.0, 18000, 18050.0)
        );
        double[] out = RiskExposure.compute(positions);
        assertThat(out[0]).isEqualTo(150.0);   // still concentrated (two legs in group)
        assertThat(out[1]).isEqualTo(50.0);    // effective = |100 - 50| (hedged)
    }

    @Test
    void uncorrelatedMarketsAddUpInEffectiveRisk() {
        // US100 BUY risk 100 + XAU SELL risk 30 (different groups) -> no concentration, effective = 130.
        List<Position> positions = List.of(
                pos("US100", Direction.BUY, 1.0, 20000, 19900.0),
                pos("XAU", Direction.SELL, 1.0, 2400, 2430.0)
        );
        double[] out = RiskExposure.compute(positions);
        assertThat(out[0]).isEqualTo(0.0);     // different groups -> not concentrated
        assertThat(out[1]).isEqualTo(130.0);
    }

    @Test
    void emptyAndNullAreZero() {
        assertThat(RiskExposure.compute(List.of())).containsExactly(0.0, 0.0);
        assertThat(RiskExposure.compute(null)).containsExactly(0.0, 0.0);
    }
}
