package com.adam.server.swing;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.CandleFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SddSwingEngineTest {

    private final SddSwingEngine engine = new SddSwingEngine(props());
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private static AppProperties props() {
        AppProperties p = new AppProperties();
        p.setTimezone("Europe/Warsaw");
        return p;
    }

    @Test
    void h4TrendClassifiesUpDownFlat() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> rising = CandleFixtures.rising(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 100, 40, 1);
        List<Candle> falling = CandleFixtures.falling(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 100, 40, 1);
        assertThat(SddSwingEngine.h4Trend(rising)).isEqualTo(SwingScan.H4Trend.UP);
        assertThat(SddSwingEngine.h4Trend(falling)).isEqualTo(SwingScan.H4Trend.DOWN);
    }

    @Test
    void buySetupRequiresH1FlipRmaPpAndH4Agreement() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> h1 = bullishWithBuyFlip(now);
        List<Candle> h4 = CandleFixtures.rising(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 100, 40, 1);

        SwingScan scan = engine.evaluate(SwingSymbol.GER40, "DE40", h1, h4, now);

        assertThat(scan).isNotNull();
        assertThat(scan.direction()).isEqualTo(Direction.BUY);
        assertThat(scan.h4Trend()).isEqualTo(SwingScan.H4Trend.UP);
        assertThat(scan.entry()).isEqualTo(h1.getLast().close());
        assertThat(scan.stopLevel()).isLessThan(scan.entry());
        assertThat(scan.targetLevel()).isGreaterThan(scan.entry());
    }

    @Test
    void h4AgainstDirectionBlocksSetup() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> h1 = bullishWithBuyFlip(now);
        List<Candle> h4Down = CandleFixtures.falling(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 100, 40, 1);

        SwingScan scan = engine.evaluate(SwingSymbol.GER40, "DE40", h1, h4Down, now);

        assertThat(scan).isNull(); // H4 DOWN against BUY flip → no setup
    }

    @Test
    void noFlipReturnsNull() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, WARSAW).toInstant();
        List<Candle> h1 = CandleFixtures.rising(now.minus(Duration.ofHours(200)), Duration.ofHours(1), 190, 50, 0.4);
        List<Candle> h4 = CandleFixtures.rising(now.minus(Duration.ofHours(400)), Duration.ofHours(4), 100, 40, 1);

        SwingScan scan = engine.evaluate(SwingSymbol.GER40, "DE40", h1, h4, now);

        assertThat(scan).isNull(); // no HA flip on H1
    }

    @Test
    void insufficientBarsReturnNull() {
        SwingScan scan = engine.evaluate(SwingSymbol.US100, "US100", List.of(), List.of(), Instant.now());
        assertThat(scan).isNull();
    }

    /** Rising H1 history so RMA stacks, plus a red then green HA pair on the last two bars. */
    private static List<Candle> bullishWithBuyFlip(Instant now) {
        Instant lastClosed = now.minus(Duration.ofHours(1));
        Instant start = lastClosed.minus(Duration.ofHours(190));
        List<Candle> h1 = new ArrayList<>(CandleFixtures.rising(start, Duration.ofHours(1), 188, 50, 0.4));
        Candle prior = h1.getLast();
        double p = prior.close();
        Instant tRed = lastClosed.minus(Duration.ofHours(1));
        h1.add(new Candle(tRed, p, p, p - 8, p - 8, 1));
        h1.add(new Candle(lastClosed, p - 8, p + 12, p - 8, p + 12, 1));
        return h1;
    }
}
