package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring / regression guard for {@link HaHuntEngine} — the strategy edge itself
 * is validated by the {@code tools/h4_ha_cloud_bt.py} backtest, not here.
 */
class HaHuntEngineTest {

    private final HaHuntEngine engine = new HaHuntEngine();
    private final Instant t0 = Instant.parse("2026-06-01T00:00:00Z");

    /** {@code n} hourly candles; close moves by {@code step}/bar from {@code start}. */
    private List<Candle> h1(int n, double start, double step) {
        List<Candle> out = new ArrayList<>(n);
        double c = start;
        for (int i = 0; i < n; i++) {
            double open = c;
            c += step;
            double hi = Math.max(open, c) + 0.5;
            double lo = Math.min(open, c) - 0.5;
            out.add(new Candle(t0.plusSeconds(i * 3600L), open, hi, lo, c, 0));
        }
        return out;
    }

    /** M15 uptrend then a shallow pullback, then one strong up bar → HA flips bear→bull on the last bar. */
    private List<Candle> m15PullbackThenFlipUp(int n, Instant end) {
        List<Candle> out = new ArrayList<>(n);
        double c = 100;
        for (int i = 0; i < n; i++) {
            double step;
            if (i >= n - 6 && i < n - 1) {
                step = -1.5;               // the pullback
            } else if (i == n - 1) {
                step = 12;                 // the flip-up bar
            } else {
                step = 1.0;                // the prevailing uptrend
            }
            double open = c;
            c += step;
            double hi = Math.max(open, c) + 0.3;
            double lo = Math.min(open, c) - 0.3;
            out.add(new Candle(end.minusSeconds((n - i) * 900L), open, hi, lo, c, 0));
        }
        return out;
    }

    @Test
    void emitsALongSignalOnAPullbackFlipInsideABullHunt() {
        Instant now = t0.plusSeconds(320 * 3600L);
        List<Candle> h1Up = h1(320, 50, 1.0);                 // steady uptrend → H4/H1 HA bull, RMAs stacked
        List<Candle> m15 = m15PullbackThenFlipUp(220, now);

        HtsScan s = engine.evaluate(HtsVariant.HA4, "XAU", "GOLD", m15, h1Up, now, true);

        assertThat(s).isNotNull();
        assertThat(s.direction()).isEqualTo(Direction.BUY);
        assertThat(s.stopLevel()).isLessThan(s.entry());          // long stop below entry
        assertThat(s.targetLevel()).isGreaterThan(s.entry());
        assertThat(s.htfUp()).isTrue();                            // hunt regime bull
    }

    /** Mirror of the long helper: downtrend then a shallow bounce, then one hard down bar. */
    private List<Candle> m15BounceThenFlipDown(int n, Instant end) {
        List<Candle> out = new ArrayList<>(n);
        double c = 400;
        for (int i = 0; i < n; i++) {
            double step;
            if (i >= n - 6 && i < n - 1) {
                step = 1.5;
            } else if (i == n - 1) {
                step = -12;
            } else {
                step = -1.0;
            }
            double open = c;
            c += step;
            double hi = Math.max(open, c) + 0.3;
            double lo = Math.min(open, c) - 0.3;
            out.add(new Candle(end.minusSeconds((n - i) * 900L), open, hi, lo, c, 0));
        }
        return out;
    }

    @Test
    void emitsAShortSignalOnABounceFlipInsideABearHunt() {
        Instant now = t0.plusSeconds(320 * 3600L);
        List<Candle> h1Down = h1(320, 400, -1.0);              // downtrend → hunt bear, RMAs stacked short
        List<Candle> m15 = m15BounceThenFlipDown(220, now);

        HtsScan s = engine.evaluate(HtsVariant.HA4, "XAU", "GOLD", m15, h1Down, now, true);

        assertThat(s).isNotNull();
        assertThat(s.direction()).isEqualTo(Direction.SELL);   // engine no longer filters side
        assertThat(s.stopLevel()).isGreaterThan(s.entry());    // short stop above entry
        assertThat(s.htfUp()).isFalse();                       // hunt regime bear
    }

    @Test
    void rejectsWhenTheHuntRegimeDisagreesWithTheEntryDirection() {
        Instant now = t0.plusSeconds(320 * 3600L);
        List<Candle> h1Down = h1(320, 400, -1.0);                 // downtrend → hunt bear
        List<Candle> m15 = m15PullbackThenFlipUp(220, now);       // flips to LONG

        assertThat(engine.evaluate(HtsVariant.HA4, "XAU", "GOLD", m15, h1Down, now, true)).isNull();
    }

    @Test
    void needsEnoughBars() {
        Instant now = t0.plusSeconds(50 * 3600L);
        assertThat(engine.evaluate(HtsVariant.HA4, "XAU", "GOLD",
                h1(50, 100, 1.0), h1(50, 100, 1.0), now, true)).isNull();
    }

    @Test
    void cloudHoldExitFiresWhenTheHuntFlipsAgainstThePosition() {
        Instant now = t0.plusSeconds(200 * 3600L);
        List<Candle> up = h1(200, 50, 1.0);
        List<Candle> down = h1(200, 300, -1.0);

        // long position: holds while hunt is bull, exits once hunt turns bear
        assertThat(engine.cloudHoldExit(HtsVariant.HA4, up, true, now)).isFalse();
        assertThat(engine.cloudHoldExit(HtsVariant.HA4, down, true, now)).isTrue();
        // short position: the mirror
        assertThat(engine.cloudHoldExit(HtsVariant.HA4, down, false, now)).isFalse();
        assertThat(engine.cloudHoldExit(HtsVariant.HA4, up, false, now)).isTrue();
    }

    @Test
    void ribbonVariantsAreNotHandledHere() {
        Instant now = t0.plusSeconds(320 * 3600L);
        assertThat(engine.evaluate(HtsVariant.CORE, "XAU", "GOLD",
                h1(320, 50, 1.0), h1(320, 50, 1.0), now, true)).isNull();
        assertThat(engine.cloudHoldExit(HtsVariant.FAST, h1(200, 50, 1.0), true, now)).isFalse();
    }
}
