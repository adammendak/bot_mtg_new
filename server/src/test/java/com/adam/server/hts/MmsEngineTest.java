package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.sdd.AtrEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MmsEngineTest {

    private final MmsEngine engine = new MmsEngine();
    private final Instant t0 = Instant.parse("2026-06-01T00:00:00Z");

    /** Flat M15 series (ATR from a 2-point range), then two extra bars. */
    private List<Candle> baseThen(Candle touch, Candle reaction) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            Instant t = t0.plusSeconds(i * 900L);
            out.add(new Candle(t, 100, 101, 99, 100, 0));
        }
        out.add(touch);
        out.add(reaction);
        return out;
    }

    @Test
    void shortAfterUpperBandTouchThenReactiveDown() {
        // Flat 100, TMA~100, ATR~2 → upper ~104. Spike well through the band, then a red body.
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        Instant now = reactT.plusSeconds(900); // reaction bar is closed
        List<Candle> bars = baseThen(
                new Candle(touchT, 100.5, 112, 100, 101, 0),
                new Candle(reactT, 101, 101.2, 98.5, 99.0, 0));

        HtsScan s = engine.evaluate(HtsVariant.MMS, "BTC", "BTCUSD", bars, null, now);

        assertThat(s).isNotNull();
        assertThat(s.direction()).isEqualTo(Direction.SELL);
        assertThat(s.entry()).isEqualTo(99.0);
        assertThat(s.stopLevel()).isCloseTo(99.0 * 1.02, within(1e-9));
        assertThat(s.targetLevel()).isLessThan(s.entry()); // opposite (lower) band
        assertThat(s.symbol()).isEqualTo("BTC");
    }

    @Test
    void longAfterLowerBandTouchThenReactiveUp() {
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        Instant now = reactT.plusSeconds(900);
        List<Candle> bars = baseThen(
                new Candle(touchT, 99.5, 100, 88, 99, 0),
                new Candle(reactT, 99, 101.5, 98.8, 101.0, 0));

        HtsScan s = engine.evaluate(HtsVariant.MMS, "XAU", "GOLD", bars, null, now);

        assertThat(s).isNotNull();
        assertThat(s.direction()).isEqualTo(Direction.BUY);
        assertThat(s.entry()).isEqualTo(101.0);
        assertThat(s.stopLevel()).isCloseTo(101.0 * 0.98, within(1e-9));
        assertThat(s.targetLevel()).isGreaterThan(s.entry());
    }

    @Test
    void ignoresSymbolsOutsideTheMmsUniverse() {
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        Instant now = reactT.plusSeconds(900);
        List<Candle> bars = baseThen(
                new Candle(touchT, 99.5, 100, 88, 99, 0),
                new Candle(reactT, 99, 101.5, 98.8, 101.0, 0));

        assertThat(engine.evaluate(HtsVariant.MMS, "GER40", "DE40", bars, null, now)).isNull();
        assertThat(engine.evaluate(HtsVariant.HA4, "BTC", "BTCUSD", bars, null, now)).isNull();
    }

    @Test
    void dropsTheFormingBarSoItCannotLookAhead() {
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        // now is still inside the reaction bar (opened at reactT, 15-min TF)
        Instant now = reactT.plusSeconds(60);
        List<Candle> bars = baseThen(
                new Candle(touchT, 100.5, 112, 100, 101, 0),
                new Candle(reactT, 101, 101.2, 98.5, 99.0, 0));

        assertThat(engine.evaluate(HtsVariant.MMS, "BTC", "BTCUSD", bars, null, now)).isNull();
    }

    @Test
    void oppositeBandHitForALongWhenHighReachesUpper() {
        Instant last = t0.plusSeconds(80 * 900L);
        Instant now = last.plusSeconds(900);
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            bars.add(new Candle(t0.plusSeconds(i * 900L), 100, 101, 99, 100, 0));
        }
        bars.add(new Candle(last, 100, 112, 99.5, 101, 0));

        assertThat(engine.oppositeBandHit(HtsVariant.MMS, bars, true, now)).isTrue();
        assertThat(engine.oppositeBandHit(HtsVariant.MMS, bars, false, now)).isFalse();
    }

    @Test
    void riskUnitDeleversAfterStopAndRestoresAfterTarget() {
        assertThat(MmsEngine.riskUnitAfter("STOP", -1.0)).isEqualTo(0.1);
        assertThat(MmsEngine.riskUnitAfter("TARGET", 1.5)).isEqualTo(1.0);
        assertThat(MmsEngine.riskUnitAfter("BAND", 0.8)).isEqualTo(1.0);
        assertThat(MmsEngine.riskUnitAfter("UNKNOWN", -1.0)).isEqualTo(0.1);
        assertThat(MmsEngine.riskUnitAfter(null, null)).isEqualTo(1.0);
    }

    @Test
    void closedOnlyKeepsABarWhoseIntervalHasElapsed() {
        Instant open = t0;
        Instant now = t0.plusSeconds(15 * 60);
        List<Candle> bars = List.of(new Candle(open, 1, 1, 1, 1, 0));
        assertThat(MmsEngine.closedOnly(bars, 15, now)).hasSize(1);
        assertThat(MmsEngine.closedOnly(bars, 15, t0.plusSeconds(60))).isEmpty();
    }

    @Test
    void defaultsUseOppositeBandTpAndPercentSl() {
        assertThat(engine.params().tpMode()).isEqualTo(MmsEngine.TpMode.OPPOSITE_BAND);
        assertThat(engine.params().slMode()).isEqualTo(MmsEngine.SlMode.PCT);
        assertThat(engine.params().stochFilterEnabled()).isFalse();
        assertThat(engine.params().stochCrossEnabled()).isFalse();
    }

    @Test
    void fixed1rWithPercentSlIsOneToOneFromThePercentStop() {
        MmsEngine fixed = new MmsEngine(new MmsEngine.Params(
                MmsEngine.ATR_PERIOD, MmsEngine.ATR_MULT, MmsEngine.SL_PCT,
                AtrEnvelope.Mode.TMA_ATR, MmsEngine.TpMode.FIXED_1R, MmsEngine.SlMode.PCT,
                false, false, false, MmsEngine.ADDON_MAX_EXTRA_PCT, MmsEngine.ADDON_STOCH_SL_PCT));
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        Instant now = reactT.plusSeconds(900);
        List<Candle> bars = baseThen(
                new Candle(touchT, 100.5, 112, 100, 101, 0),
                new Candle(reactT, 101, 101.2, 98.5, 99.0, 0));

        HtsScan s = fixed.evaluate(HtsVariant.MMS, "BTC", "BTCUSD", bars, null, now);

        assertThat(s).isNotNull();
        assertThat(s.stopLevel()).isCloseTo(99.0 * 1.02, within(1e-9));
        assertThat(s.targetLevel()).isCloseTo(99.0 - (99.0 * 0.02), within(1e-9));
        assertThat(Math.abs(s.entry() - s.targetLevel()))
                .isCloseTo(Math.abs(s.entry() - s.stopLevel()), within(1e-9));
    }

    @Test
    void fixed1rWithWickSlStopsBeyondThePiercingExtreme() {
        MmsEngine wick = new MmsEngine(MmsEngine.Params.testerFixed1r());
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        Instant now = reactT.plusSeconds(900);
        List<Candle> bars = baseThen(
                new Candle(touchT, 100.5, 112, 100, 101, 0),
                new Candle(reactT, 101, 101.2, 98.5, 99.0, 0));

        HtsScan s = wick.evaluate(HtsVariant.MMS, "BTC", "BTCUSD", bars, null, now);

        assertThat(s).isNotNull();
        assertThat(s.direction()).isEqualTo(Direction.SELL);
        assertThat(s.stopLevel()).isEqualTo(112.0); // piercing high
        assertThat(s.targetLevel()).isEqualTo(99.0 - (112.0 - 99.0));
    }

    @Test
    void fixed1rHitWhenClosedBarReachesTheStoredTarget() {
        Instant last = t0.plusSeconds(80 * 900L);
        Instant now = last.plusSeconds(900);
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            bars.add(new Candle(t0.plusSeconds(i * 900L), 100, 101, 99, 100, 0));
        }
        bars.add(new Candle(last, 100, 101, 86, 90, 0)); // low reaches 1R target 86

        assertThat(engine.fixed1rHit(HtsVariant.MMS, bars, false, 87.0, now)).isTrue();
        assertThat(engine.fixed1rHit(HtsVariant.MMS, bars, false, 80.0, now)).isFalse();
    }

    @Test
    void algoTfExcludesM5AndAllowsM15AndH1() {
        assertThat(MmsEngine.algoTfOk(5)).isFalse();
        assertThat(MmsEngine.algoTfOk(10)).isTrue();
        assertThat(MmsEngine.algoTfOk(15)).isTrue();
        assertThat(MmsEngine.algoTfOk(30)).isTrue();
        assertThat(MmsEngine.algoTfOk(60)).isTrue();
        assertThat(MmsEngine.algoTfOk(240)).isFalse();
    }

    @Test
    void siteBbM15ExampleIsDocumentedNotDefault() {
        MmsEngine.Params ex = MmsEngine.Params.siteBbM15Example();
        assertThat(ex.atrPeriod()).isEqualTo(41);
        assertThat(ex.atrMult()).isEqualTo(3.2);
        assertThat(ex.slPct()).isEqualTo(0.017);
        assertThat(ex.mode()).isEqualTo(AtrEnvelope.Mode.BB_ATR);
        assertThat(MmsEngine.Params.defaults().atrPeriod()).isEqualTo(20);
    }

    @Test
    void confirmingBarIsTheNextSameColourClosedInterval() {
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        Instant confirmT = reactT.plusSeconds(900);
        Instant now = confirmT.plusSeconds(900);
        List<Candle> bars = baseThen(
                new Candle(touchT, 100.5, 112, 100, 101, 0),
                new Candle(reactT, 101, 101.2, 98.5, 99.0, 0));
        bars.add(new Candle(confirmT, 99.0, 99.1, 98.4, 98.5, 0)); // next interval, still down
        assertThat(MmsEngine.confirmingBar(MmsEngine.closedOnly(bars, 15, now), reactT, false))
                .isEqualTo(bars.size() - 1);
        assertThat(MmsEngine.confirmingBar(MmsEngine.closedOnly(bars, 15, now), reactT, true))
                .isEqualTo(-1);
    }

    @Test
    void evaluateAddFiresOnceThenBlocksAfterAddonStop() {
        MmsEngine addOn = new MmsEngine(new MmsEngine.Params(
                MmsEngine.ATR_PERIOD, MmsEngine.ATR_MULT, MmsEngine.SL_PCT,
                AtrEnvelope.Mode.TMA_ATR, MmsEngine.TpMode.OPPOSITE_BAND, MmsEngine.SlMode.PCT,
                true, false, false, MmsEngine.ADDON_MAX_EXTRA_PCT, MmsEngine.ADDON_STOCH_SL_PCT));
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        Instant confirmT = reactT.plusSeconds(900);
        Instant now = confirmT.plusSeconds(900);
        List<Candle> bars = baseThen(
                new Candle(touchT, 100.5, 112, 100, 101, 0),
                new Candle(reactT, 101, 101.2, 98.5, 99.0, 0));
        bars.add(new Candle(confirmT, 99.0, 99.2, 98.55, 98.6, 0)); // extra 99-98.55=0.45% of 99

        HtsScan add = addOn.evaluateAdd(HtsVariant.MMS, "BTC", "BTCUSD", bars, null, now,
                99.0, false, reactT, 95.0);
        assertThat(add).isNotNull();
        assertThat(add.stopLevel()).isEqualTo(99.2); // short wick high
        assertThat(addOn.addonBlocked(HtsVariant.MMS, "BTC")).isTrue();
        assertThat(addOn.evaluateAdd(HtsVariant.MMS, "BTC", "BTCUSD", bars, null, now,
                99.0, false, reactT, 95.0)).isNull();

        addOn.onClosed(HtsVariant.MMS, "BTC", confirmT, "STOP");
        assertThat(addOn.addonBlocked(HtsVariant.MMS, "BTC")).isTrue(); // no retry
        addOn.rememberBase(HtsVariant.MMS, "BTC", confirmT.plusSeconds(900));
        assertThat(addOn.addonBlocked(HtsVariant.MMS, "BTC")).isFalse(); // new setup
    }

    @Test
    void addonStopRejectsAWickWiderThanOnePercentAndUsesFixedSlWhenStochFiltered() {
        assertThat(MmsEngine.addonStop(100, true, 98.8, false, 0.01, 0.01)).isNaN(); // 1.2% > 1%
        assertThat(MmsEngine.addonStop(100, true, 99.6, false, 0.01, 0.01))
                .isCloseTo(99.6, within(1e-9)); // 0.4%
        assertThat(MmsEngine.addonStop(100, true, 98.0, false, 0.01, 0.01)).isNaN(); // 2%
        assertThat(MmsEngine.addonStop(100, true, 98.0, true, 0.01, 0.01))
                .isCloseTo(99.0, within(1e-9)); // stoch-filtered add: fixed 1%
        assertThat(MmsEngine.addonStop(100, true, 99.2, false, 0.01, 0.01))
                .isCloseTo(99.2, within(1e-9)); // 0.8% — over typical 0.5%, still ≤ 1%
        assertThat(MmsEngine.addonSizedStop(100.0, 99.4)).isTrue();
        assertThat(MmsEngine.addonSizedStop(100.0, 98.0)).isFalse(); // full 2% base
        assertThat(MmsEngine.addonSizedStop(100.0, 99.0)).isTrue();  // stoch 1%
    }

    @Test
    void reactionEntryRequiresTouchAndOppositeColourOnDifferentBars() {
        Instant touchT = t0.plusSeconds(80 * 900L);
        Instant reactT = touchT.plusSeconds(900);
        List<Candle> bars = baseThen(
                new Candle(touchT, 100.5, 112, 100, 101, 0),
                new Candle(reactT, 101, 101.2, 98.5, 99.0, 0));
        AtrEnvelope.Series env = AtrEnvelope.of(bars, MmsEngine.ATR_PERIOD, MmsEngine.ATR_MULT,
                AtrEnvelope.Mode.TMA_ATR);
        assertThat(MmsEngine.reactionEntry(bars, env, bars.size() - 1)).isFalse(); // short
    }
}
