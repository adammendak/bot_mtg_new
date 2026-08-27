package com.adam.server.sdd;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SddEngineTest {

    private final SddEngine engine = new SddEngine(ZoneId.of("Europe/Warsaw"));
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    void stackedRequiresCloseAndRmaAlignment() {
        assertThat(SddEngine.stacked(10, 8, 5, true)).isTrue();
        assertThat(SddEngine.stacked(7, 8, 5, true)).isFalse();
        assertThat(SddEngine.stacked(1, 3, 5, false)).isTrue();
        assertThat(SddEngine.stacked(4, 3, 5, false)).isFalse();
    }

    @Test
    void h4IsNoteNotAndFilter() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> m15 = bullishWithBuyFlip(now);
        List<Candle> h1 = CandleFixtures.rising(now.minus(Duration.ofHours(200)), Duration.ofHours(1), 180, 50, 0.4);
        List<Candle> h4Against = CandleFixtures.falling(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 80, 200, 1.5);
        SddScan scan = engine.evaluate(SddSymbol.GER40, "DE40", m15, h1, h4Against, now);
        assertThat(scan.h4Note()).contains("H4");
        if (scan.fullStack()) {
            assertThat(scan.setup().ha()).isTrue();
            assertThat(scan.setup().rma()).isTrue();
            assertThat(scan.setup().h1()).isTrue();
        }
    }

    @Test
    void btcSkipsPivotFilter() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> m15 = bullishWithBuyFlip(now);
        List<Candle> h1 = CandleFixtures.rising(now.minus(Duration.ofHours(200)), Duration.ofHours(1), 180, 50, 0.4);
        List<Candle> h4 = CandleFixtures.rising(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 80, 40, 1);
        SddScan btc = engine.evaluate(SddSymbol.BTC, "BITCOIN", m15, h1, h4, now);
        assertThat(btc.setup().pp()).isTrue();
        assertThat(btc.failed()).doesNotContain("pp");
    }

    @Test
    void fullStackBuyRequiresHaFlipRmaH1AndPp() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> m15 = bullishWithBuyFlip(now);
        List<Candle> h1 = CandleFixtures.rising(now.minus(Duration.ofHours(200)), Duration.ofHours(1), 180, 50, 0.4);
        List<Candle> h4 = CandleFixtures.rising(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 80, 40, 1);
        SddScan scan = engine.evaluate(SddSymbol.GER40, "DE40", m15, h1, h4, now);
        assertThat(scan.direction()).isEqualTo(Direction.BUY);
        assertThat(scan.flip()).as(scan.reason()).isTrue();
        assertThat(scan.setup().ha()).isTrue();
        assertThat(scan.setup().rma()).as(scan.reason()).isTrue();
        assertThat(scan.setup().h1()).as(scan.reason()).isTrue();
        assertThat(scan.setup().pp()).as(scan.reason()).isTrue();
        assertThat(scan.fullStack()).as(scan.reason()).isTrue();
        assertThat(scan.actionable()).isTrue();
        assertThat(scan.atrH1()).isGreaterThan(0);
        assertThat(scan.stop()).isEqualTo(scan.entry() - 2.5 * scan.atrH1());
        assertThat(scan.oneR()).isEqualTo(scan.atrH1());
        assertThat(scan.h4Note()).contains("h1Supporting=");
    }

    @Test
    void noFlipIsNotFullStackEvenIfRmaAligned() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> m15 = CandleFixtures.rising(now.minus(Duration.ofMinutes(15 * 220)), Duration.ofMinutes(15), 200, 80, 0.3);
        List<Candle> h1 = CandleFixtures.rising(now.minus(Duration.ofHours(200)), Duration.ofHours(1), 180, 50, 0.4);
        List<Candle> h4 = CandleFixtures.rising(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 80, 40, 1);
        SddScan scan = engine.evaluate(SddSymbol.GER40, "DE40", m15, h1, h4, now);
        assertThat(scan.flip()).isFalse();
        assertThat(scan.fullStack()).isFalse();
        assertThat(scan.failed()).contains("ha");
        assertThat(scan.actionable()).isFalse();
    }

    @Test
    void insufficientBarsFailClosed() {
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        SddScan scan = engine.evaluate(SddSymbol.US100, "US100", List.of(), List.of(), List.of(), now);
        assertThat(scan.fullStack()).isFalse();
        assertThat(scan.failed()).contains("insufficient_m15", "insufficient_h1");
    }

    /**
     * Rising M15 history so RMA stacks, plus a red then green HA pair on the last two bars.
     * Includes a prior Warsaw session so PP sits below the last close.
     */
    private static List<Candle> bullishWithBuyFlip(Instant now) {
        Instant lastClosed = now.minus(Duration.ofMinutes(15));
        Instant start = lastClosed.minus(Duration.ofMinutes(15L * 220));
        List<Candle> m15 = new ArrayList<>(CandleFixtures.rising(start, Duration.ofMinutes(15), 218, 80, 0.35));
        Instant prevSession = ZonedDateTime.of(2026, 8, 24, 22, 0, 0, 0, WARSAW).toInstant();
        m15.add(0, new Candle(prevSession, 40, 50, 30, 45, 1));
        Candle prior = m15.getLast();
        double p = prior.close();
        Instant tRed = lastClosed.minus(Duration.ofMinutes(15));
        m15.add(new Candle(tRed, p, p, p - 40, p - 40, 1));
        m15.add(new Candle(lastClosed, p - 40, p + 50, p - 40, p + 50, 1));
        return m15;
    }
}
