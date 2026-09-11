package com.adam.server.hts;

import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StV3EngineTest {

    private static List<Candle> flatM15(int n, double price, Instant start) {
        List<Candle> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Instant t = start.plus(15L * i, ChronoUnit.MINUTES);
            out.add(new Candle(t, price, price, price, price, 0));
        }
        return out;
    }

    @Test
    void tooFewBarsReturnsNull() {
        StV3Engine engine = new StV3Engine();
        List<Candle> thin = flatM15(10, 100.0, Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(engine.evaluate(HtsVariant.M15_ST_V3, "XAU", "GOLD", thin, Instant.now())).isNull();
    }

    @Test
    void nullEntryTfReturnsNull() {
        StV3Engine engine = new StV3Engine();
        assertThat(engine.evaluate(HtsVariant.M15_ST_V3, "XAU", "GOLD", null, Instant.now())).isNull();
    }

    @Test
    void symbolOutsideTheUniverseReturnsNull() {
        StV3Engine engine = new StV3Engine();
        // ETH is not an SddSymbol at all, so certainly outside every ST_V3
        // universe — must short-circuit even with otherwise-enough bars,
        // rather than fall through to the gates.
        List<Candle> bars = flatM15(200, 100.0, Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(engine.evaluate(HtsVariant.M15_ST_V3, "ETH", "ETHUSD", bars, Instant.now())).isNull();
    }

    @Test
    void flatPriceNeverFiresAFreshBandCross() {
        // A perfectly flat series never closes beyond the fast RMA band (it IS
        // the band), so this must stay null indefinitely — a sanity check that
        // the entry gate doesn't fire on a degenerate/no-volatility series.
        // Exercises all three HTF spans (M45/H1/H4) via each variant's own
        // atrMinutes(), not just the H1 one.
        StV3Engine engine = new StV3Engine();
        List<Candle> bars = flatM15(400, 100.0, Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(engine.evaluate(HtsVariant.M15_ST_V3, "XAU", "GOLD", bars, Instant.now())).isNull();
        assertThat(engine.evaluate(HtsVariant.M5_ST_V3, "XAU", "GOLD", bars, Instant.now())).isNull();
        assertThat(engine.evaluate(HtsVariant.H1_ST_V3, "BTC", "BTCUSD", bars, Instant.now())).isNull();
    }
}
