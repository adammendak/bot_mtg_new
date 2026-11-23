package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.Wilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KalmanEngineTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final KalmanEngine engine = new KalmanEngine();

    /** H4 candles around the given closes (high/low +-1, open = close). */
    private static List<Candle> candles(double[] closes, int n) {
        List<Candle> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Candle(START.plus(4L * i, ChronoUnit.HOURS), closes[i], closes[i] + 1, closes[i] - 1,
                    closes[i], 0));
        }
        return out;
    }

    private static final int N = 660;

    /** 500 bars of up-drift then 160 of a drop: the 18/144 pair crosses DOWN on bar 514 (reference: python port). */
    private static double[] downFlipCloses() {
        return series(+1);
    }

    /** 500 bars of down-drift then 160 of a rally: crosses UP on bar 506. */
    private static double[] upFlipCloses() {
        return series(-1);
    }

    private static double[] series(int sign) {
        double[] c = new double[N];
        for (int i = 0; i < 500; i++) {
            c[i] = 150 + sign * 0.05 * i + Math.sin(i / 7.0);
        }
        for (int i = 500; i < N; i++) {
            c[i] = c[i - 1] - sign * 0.4 + 0.3 * Math.sin((i - 500) / 3.0);
        }
        return c;
    }

    @Test
    void freshDownCrossoverSellsWithA3AtrStopAndTheFlipBarAsTimestamp() {
        List<Candle> bars = candles(downFlipCloses(), 516); // last closed bar = 515, flip on 514 (1 bar ago)
        HtsScan s = engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", bars);

        assertThat(s).isNotNull();
        assertThat(s.direction()).isEqualTo(Direction.SELL);
        assertThat(s.timestamp()).isEqualTo(bars.get(514).time());
        assertThat(s.entry()).isEqualTo(bars.get(515).close());
        double atr = Wilder.last(Wilder.atr(bars, 14));
        assertThat(s.stopLevel()).isCloseTo(s.entry() + 3 * atr, within(1e-9));
        assertThat(s.htfUp()).isFalse();
        assertThat(s.symbol()).isEqualTo("XAU");
        assertThat(s.epic()).isEqualTo("GOLD");
    }

    @Test
    void freshUpCrossoverBuysWithTheStopBelow() {
        List<Candle> bars = candles(upFlipCloses(), 508); // last = 507, flip on 506
        HtsScan s = engine.evaluate(HtsVariant.KAL_H4, "BTC", "BTCUSD", bars);

        assertThat(s).isNotNull();
        assertThat(s.direction()).isEqualTo(Direction.BUY);
        assertThat(s.timestamp()).isEqualTo(bars.get(506).time());
        assertThat(s.stopLevel()).isLessThan(s.entry());
        assertThat(s.targetLevel()).isGreaterThan(s.entry());
        assertThat(s.htfUp()).isTrue();
    }

    @Test
    void theSignalIsTheSameOnTheFlipBarAndTheNextOne() {
        // scan missed once: the flip is still caught one closed bar later, with the SAME bar timestamp
        // (so the gate's dedupe key / alreadyExecuted treat it as one signal)
        List<Candle> all = candles(downFlipCloses(), N);
        HtsScan onFlip = engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", all.subList(0, 515));
        HtsScan later = engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", all.subList(0, 516));
        assertThat(onFlip).isNotNull();
        assertThat(later).isNotNull();
        assertThat(later.timestamp()).isEqualTo(onFlip.timestamp());
    }

    @Test
    void aStaleFlipIsNotChased() {
        assertThat(engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", candles(downFlipCloses(), 517))).isNull(); // 2 bars
        assertThat(engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", candles(downFlipCloses(), N))).isNull();
    }

    @Test
    void noCrossoverInASteadyTrendMeansNoSignal() {
        double[] c = new double[500];
        for (int i = 0; i < c.length; i++) {
            c[i] = 100 + 0.1 * i;
        }
        assertThat(engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", candles(c, 500))).isNull();
    }

    @Test
    void notEnoughHistoryOrWrongUniverseOrWrongStrategyIsNull() {
        assertThat(engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", candles(downFlipCloses(), 200))).isNull();
        assertThat(engine.evaluate(HtsVariant.KAL_H4, "XAG", "SILVER", candles(downFlipCloses(), 516))).isNull();
        assertThat(engine.evaluate(HtsVariant.H1_ST_V3, "XAU", "GOLD", candles(downFlipCloses(), 516))).isNull();
        assertThat(engine.evaluate(HtsVariant.KAL_H4, "XAU", "GOLD", null)).isNull();
    }

    @Test
    void exitSignalIsTheOppositeTrend() {
        List<Candle> down = candles(downFlipCloses(), N);  // short filter below long at the end
        assertThat(engine.trend(down)).isEqualTo(-1);
        assertThat(engine.exitSignal(down, true)).isTrue();   // a long must go
        assertThat(engine.exitSignal(down, false)).isFalse(); // a short stays

        List<Candle> up = candles(upFlipCloses(), N);
        assertThat(engine.trend(up)).isEqualTo(1);
        assertThat(engine.exitSignal(up, false)).isTrue();
        assertThat(engine.exitSignal(up, true)).isFalse();

        assertThat(engine.exitSignal(candles(downFlipCloses(), 100), true)).isFalse(); // not enough history
        assertThat(engine.exitSignal(null, true)).isFalse();
    }
}
