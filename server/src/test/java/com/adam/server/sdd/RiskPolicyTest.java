package com.adam.server.sdd;

import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.adam.server.broker.Direction.BUY;
import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyTest {

    private final RiskPolicy risk = new RiskPolicy(new AppProperties());

    @Test
    void liveRefusesWrongAccountAndHighEquity() {
        Account other = new Account("1", "demo", "PLN", 1000, 1000, 0, true);
        assertThat(risk.liveGate(other, true)).contains("bot trading konto");
        Account named = new Account("1", "bot trading konto", "PLN", 6000, 6000, 0, true);
        assertThat(risk.liveGate(named, true)).contains("5000");
        Account ok = new Account("1", "bot trading konto", "PLN", 4000, 4000, 0, true);
        assertThat(risk.liveGate(ok, true)).isNull();
        assertThat(risk.liveGate(ok, false)).isNull();
    }

    @Test
    void liveDashboardHidesHighEquityWrongNameAndFintokei() {
        Account tooBig = new Account("1", "bot trading konto", "PLN", 10_000, 10_000, 0, true);
        assertThat(risk.pickLiveAccount(List.of(tooBig)).visible()).isFalse();
        assertThat(risk.pickLiveAccount(List.of(tooBig)).hideReason()).contains("hidden");
        assertThat(risk.pickLiveAccount(List.of(tooBig)).hideReason()).doesNotContain("10000");

        Account wrong = new Account("2", "preferred 10k", "PLN", 4000, 4000, 0, true);
        assertThat(risk.pickLiveAccount(List.of(wrong)).hideReason()).contains("bot trading konto");

        Account fintokei = new Account("3", "Fintokei main", "PLN", 1000, 1000, 0, true);
        assertThat(risk.isFintokei(fintokei.name())).isTrue();
        assertThat(risk.pickLiveAccount(List.of(fintokei)).visible()).isFalse();

        Account ok = new Account("4", "bot trading konto", "PLN", 4000, 4000, 12, true);
        assertThat(risk.pickLiveAccount(List.of(fintokei, ok)).account()).isEqualTo(ok);
    }

    @Test
    void demoPickerSkipsFintokei() {
        Account fintokei = new Account("1", "Fintokei", "PLN", 100, 100, 0, true);
        Account demo = new Account("2", "demo", "PLN", 1000, 1000, 5, false);
        assertThat(risk.pickDemoAccount(List.of(fintokei, demo))).isEqualTo(demo);
    }

    @Test
    void dayHaltThresholds() {
        assertThat(risk.dayHalt(-10)).isNull();
        assertThat(risk.dayHalt(-30)).contains("halt");
        assertThat(risk.dayHalt(-50)).contains("hard halt");
    }

    @Test
    void neverFlattenProtectedNames() {
        assertThat(risk.neverFlatten("TQQQ")).isTrue();
        assertThat(risk.neverFlatten("CRCL")).isTrue();
        assertThat(risk.neverFlatten("SPOT")).isTrue();
        assertThat(risk.neverFlatten("SHOP")).isTrue();
        assertThat(risk.neverFlatten("DE40")).isFalse();
    }

    @Test
    void noPyramidWhileOpen() {
        Position open = new Position("d", "r", "DE40", BUY, 1, 1, 0.5, null, 0, "PLN", Instant.now());
        assertThat(risk.pyramidBlocked("DE40", List.of(open))).isTrue();
        assertThat(risk.pyramidBlocked("GOLD", List.of(open))).isFalse();
    }
}
