package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.persistence.HtsTradeRepository;
import com.adam.server.web.dto.HtsScorecardRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTS trade lifecycle (E-1): one position per signal, half off at TP1 (1:2 R:R),
 * the runner trails the fast band and exits on a body beyond the slow band, and a
 * vanished deal is reconciled to CLOSED with its outcome.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HtsTradeServiceTest {

    @Mock
    HtsTradeRepository repo;
    @Mock
    BrokerClient broker;
    @Mock
    BrokerClient market;
    @Mock
    HtsEngine engine;
    @Mock
    HaHuntEngine haHunt;
    @Mock
    HtsTradeSink sink;
    @Mock
    com.adam.server.sdd.RiskPolicy risk;

    AppProperties props;
    BrokerBooks books;
    HtsTradeService service;

    final String book = HtsVariant.CORE.book();
    final Instant bar = Instant.parse("2026-08-28T12:00:00Z");

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        books = mock(BrokerBooks.class);
        when(books.marketData()).thenReturn(market);
        when(books.forBook(book)).thenReturn(broker);
        when(broker.configured()).thenReturn(true);
        when(broker.isSessionOpen()).thenReturn(true);
        when(market.candles(anyString(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new Candle(bar.minusSeconds(120), 100, 100, 100, 100, 0),
                        new Candle(bar.minusSeconds(60), 100, 100, 100, 100, 0),
                        new Candle(bar, 100, 100, 100, 100, 0)));
        when(repo.save(any(HtsTradeEntity.class))).thenAnswer(i -> i.getArgument(0));
        service = new HtsTradeService(repo, books, engine, haHunt, new MmsEngine(), props, risk, List.of(sink));
    }

    // ---- recordOpen / idempotency / realised P/L ----

    @Test
    void recordOpenMapsTheEntityAndFansToSink() {
        HtsScan s = new HtsScan(HtsVariant.CORE, bar, "GER40", "DE40", Direction.BUY,
                100.0, 99.0, 102.0, true);

        HtsTradeEntity t = service.recordOpen(s, HtsVariant.CORE, book, "Account m15", 1.0,
                new OrderAck("ref1", "d1", "OK"));

        assertThat(t.getVariant()).isEqualTo("CORE");
        assertThat(t.getHtf()).isEqualTo("H4");
        assertThat(t.getLtf()).isEqualTo("M15");
        assertThat(t.getStatus()).isEqualTo("OPEN");
        assertThat(t.getDealId()).isEqualTo("d1");
        assertThat(t.getBarTime()).isEqualTo(bar);
        assertThat(t.getRunnerStop()).isEqualTo(99.0);
        verify(repo).save(any(HtsTradeEntity.class));
        verify(sink).onOpen(t);
    }

    @Test
    void recordOpenDoesNotNpeForHaHuntVariantsWithNoResolutionHtf() {
        HtsScan s = new HtsScan(HtsVariant.HA4, bar, "XAU", "GOLD", Direction.BUY,
                4400.0, 4350.0, 4500.0, true);

        HtsTradeEntity t = service.recordOpen(s, HtsVariant.HA4, HtsVariant.HA4.book(), "Account m15", 0.2,
                new OrderAck("ref-ha", "d-ha", "OK"));

        assertThat(t.getVariant()).isEqualTo("HA4");
        assertThat(t.getHtf()).isEqualTo("H4");   // htfLabel(), not htf().name() (which is null)
        assertThat(t.getLtf()).isEqualTo("M15");
        assertThat(t.getStatus()).isEqualTo("OPEN");
        verify(sink).onOpen(t);
    }

    @Test
    void mmsRiskUnitDeleversAfterAStopAndRestoresAfterATarget() {
        HtsTradeEntity stopped = new HtsTradeEntity();
        stopped.setCloseReason("STOP");
        stopped.setRMultiple(-1.0);
        stopped.setEntry(100.0);
        stopped.setStopLevel(98.0); // full ~2% base SL
        when(repo.findTop20ByVariantAndSymbolAndStatusOrderByIdDesc("MMS", "BTC", "CLOSED"))
                .thenReturn(List.of(stopped));
        assertThat(service.mmsRiskUnit(HtsVariant.MMS, "BTC")).isEqualTo(0.1);

        HtsTradeEntity won = new HtsTradeEntity();
        won.setCloseReason("TARGET");
        won.setRMultiple(1.2);
        won.setEntry(100.0);
        won.setStopLevel(98.0);
        when(repo.findTop20ByVariantAndSymbolAndStatusOrderByIdDesc("MMS", "XAU", "CLOSED"))
                .thenReturn(List.of(won));
        assertThat(service.mmsRiskUnit(HtsVariant.MMS, "XAU")).isEqualTo(1.0);

        when(repo.findTop20ByVariantAndSymbolAndStatusOrderByIdDesc("MMS", "US100", "CLOSED"))
                .thenReturn(List.of());
        assertThat(service.mmsRiskUnit(HtsVariant.MMS, "US100")).isEqualTo(1.0);
    }

    @Test
    void mmsRiskUnitIgnoresAnAddonStopAndReadsTheBaseOutcome() {
        HtsTradeEntity addonStop = new HtsTradeEntity();
        addonStop.setCloseReason("STOP");
        addonStop.setRMultiple(-1.0);
        addonStop.setEntry(100.0);
        addonStop.setStopLevel(99.4); // 0.6% wick — add-on, must not cut ×1

        HtsTradeEntity baseWin = new HtsTradeEntity();
        baseWin.setCloseReason("TARGET");
        baseWin.setRMultiple(1.1);
        baseWin.setEntry(100.0);
        baseWin.setStopLevel(98.0);
        when(repo.findTop20ByVariantAndSymbolAndStatusOrderByIdDesc("MMS", "BTC", "CLOSED"))
                .thenReturn(List.of(addonStop, baseWin));
        assertThat(service.mmsRiskUnit(HtsVariant.MMS, "BTC")).isEqualTo(1.0);

        HtsTradeEntity baseStop = new HtsTradeEntity();
        baseStop.setCloseReason("STOP");
        baseStop.setRMultiple(-1.0);
        baseStop.setEntry(200.0);
        baseStop.setStopLevel(196.0); // 2%
        when(repo.findTop20ByVariantAndSymbolAndStatusOrderByIdDesc("MMS", "XAU", "CLOSED"))
                .thenReturn(List.of(addonStop, baseStop));
        assertThat(service.mmsRiskUnit(HtsVariant.MMS, "XAU")).isEqualTo(0.1);
    }

    @Test
    void allowMmsAddOnRequiresFlagExactlyOneOpenAndACleanBook() {
        HtsScan s = new HtsScan(HtsVariant.MMS, bar, "BTC", "BTCUSD", Direction.SELL,
                99.0, 99.4, 95.0, false);
        when(repo.countByVariantAndSymbolAndStatus("MMS", "BTC", "OPEN")).thenReturn(1L);
        assertThat(service.allowMmsAddOn(s)).isFalse(); // default engine: add-on off

        MmsEngine addOn = new MmsEngine(new MmsEngine.Params(
                MmsEngine.ATR_PERIOD, MmsEngine.ATR_MULT, MmsEngine.SL_PCT,
                com.adam.server.sdd.AtrEnvelope.Mode.TMA_ATR,
                MmsEngine.TpMode.OPPOSITE_BAND, MmsEngine.SlMode.PCT,
                true, false, false, MmsEngine.ADDON_MAX_EXTRA_PCT, MmsEngine.ADDON_STOCH_SL_PCT, MmsEngine.REACTION_WINDOW, true, MmsEngine.HTF_SMA, false));
        service = new HtsTradeService(repo, books, engine, haHunt, addOn, props, risk, List.of(sink));
        assertThat(service.allowMmsAddOn(s)).isTrue();
        assertThat(service.canScanMmsAddOn(HtsVariant.MMS, "BTC")).isTrue();

        when(repo.countByVariantAndSymbolAndStatus("MMS", "BTC", "OPEN")).thenReturn(2L);
        assertThat(service.allowMmsAddOn(s)).isFalse(); // add already on

        when(repo.countByVariantAndSymbolAndStatus("MMS", "BTC", "OPEN")).thenReturn(1L);
        addOn.rememberAddon(HtsVariant.MMS, "BTC", bar);
        assertThat(service.allowMmsAddOn(s)).isFalse(); // no retry
        assertThat(service.allowMmsAddOn(new HtsScan(HtsVariant.HA4, bar, "XAU", "GOLD",
                Direction.BUY, 1, 1, 1, true))).isFalse();
    }

    @Test
    void alreadyExecutedDelegatesToTheRepo() {
        when(repo.existsByVariantAndSymbolAndDirectionAndBarTime("CORE", "GER40", "BUY", bar))
                .thenReturn(true);
        HtsScan s = new HtsScan(HtsVariant.CORE, bar, "GER40", "DE40", Direction.BUY,
                100.0, 99.0, 102.0, true);
        assertThat(service.alreadyExecuted(s)).isTrue();
    }

    @Test
    void realisedPnlSinceSumsClosedTradesOnTheBook() {
        HtsTradeEntity a = new HtsTradeEntity();
        a.setPnl(12.0);
        HtsTradeEntity b = new HtsTradeEntity();
        b.setPnl(-4.0);
        when(repo.findByBookAndStatusAndExitAtAfter(eq(book), eq("CLOSED"), any()))
                .thenReturn(List.of(a, b));
        assertThat(service.realisedPnlSince(book, bar)).isEqualTo(8.0);
    }

    // ---- manage(): reconcile a vanished deal ----

    @Test
    void manageClosesAVanishedDealWithItsOutcomeAfterTwoConsecutiveMisses() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of()); // deal gone
        when(broker.transactionHistory(any(), any(), any(Duration.class)))
                .thenReturn(List.of(new BrokerTransaction(bar.plusSeconds(3600), "TRADE", "DE40",
                        -1.0, "d1", "stopped out")));

        // first miss — deferred, not closed (could be the account-selection race)
        assertThat(service.manage()).isEqualTo(0);
        assertThat(t.getStatus()).isEqualTo("OPEN");
        verify(sink, never()).onClose(any());

        // second consecutive miss — now force-closed with its outcome
        int touched = service.manage();

        assertThat(touched).isEqualTo(1);
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getPnl()).isEqualTo(-1.0);
        assertThat(t.getRMultiple()).isEqualTo(-1.0);
        assertThat(t.getCloseReason()).isEqualTo("STOP");
        verify(sink).onClose(t);
    }

    @Test
    void manageEstimatesRFromMarkPriceWhenNoTransactionReferenceMatches() {
        // Capital's tx `reference` is the closing deal's id, not our opening one,
        // so matchPnl misses — applyClose must still produce an R from the mark price.
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, 1.0); // leg = 2.0
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of());
        when(broker.transactionHistory(any(), any(), any(Duration.class)))
                .thenReturn(List.of(new BrokerTransaction(bar.plusSeconds(600), "TRADE", "DE40",
                        -7.0, "o-some-other-close", "unrelated close")));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 101.0, 101.0, bar));

        service.manage();                 // 1st miss — deferred
        int touched = service.manage();   // 2nd — closed

        assertThat(touched).isEqualTo(1);
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getExitPrice()).isEqualTo(101.0);
        assertThat(t.getRMultiple()).isEqualTo(0.5);       // (101 - 100) / 2
        assertThat(t.getPnl()).isNull();                   // R estimated, cash not known
        assertThat(t.getCloseReason()).isEqualTo("MANUAL");
    }

    @Test
    void manageAssumesStopOutWhenNoTransactionAndNoMarkPrice() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of());
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());
        when(broker.marketPrice(anyString())).thenThrow(new BrokerException("no price"));

        service.manage();
        service.manage();

        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getExitPrice()).isEqualTo(98.0);      // fell back to the stop level
        assertThat(t.getRMultiple()).isEqualTo(-1.0);
        assertThat(t.getCloseReason()).isEqualTo("STOP");
        assertThat(t.getPnl()).isNull();
    }

    @Test
    void manageClampsAVanishedStopOutToTheStopWhenTheLateMarkRanPastIt() {
        // The vanish is noticed up to ~10 min after the broker stopped the deal out; the mark taken then can sit
        // well beyond the stop. The exit was AT the stop: R = -1.0, reason STOP (was -1.5R / MANUAL).
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, 1.0); // leg = 2.0
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of());
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 97.0, 97.0, bar)); // 1.5 legs against

        service.manage();
        service.manage();

        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getExitPrice()).isEqualTo(98.0);
        assertThat(t.getRMultiple()).isEqualTo(-1.0);
        assertThat(t.getCloseReason()).isEqualTo("STOP");
    }

    @Test
    void manageDoesNotClampAnEstimateWhenTp1AlreadyFilled() {
        // After TP1 the working stop is breakeven, so the original stop level is not the exit — keep the mark estimate.
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, 1.0);
        t.setTp1At(bar);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of());
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 97.0, 97.0, bar));

        service.manage();
        service.manage();

        assertThat(t.getExitPrice()).isEqualTo(97.0);
        assertThat(t.getRMultiple()).isEqualTo(-1.5);
    }

    @Test
    void manageDoesNotCloseWhenTheDealReappearsAfterOneMiss() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(
                List.of(),                            // pass 1: absent (race)
                List.of(pos("d1", Direction.BUY)),    // pass 2: back — streak resets
                List.of());                           // pass 3: absent again — only the 1st miss of a new streak
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(100.5, 99.5, false)); // below TP1, nothing to do

        service.manage();
        service.manage();
        service.manage();

        assertThat(t.getStatus()).isEqualTo("OPEN");
        verify(sink, never()).onClose(any());
    }

    // ---- manage(): HA-hunt cloud-hold exit vs. a plain reconcile-vanish ----

    /**
     * Weekly automated report (2026-09-11): every HA4/HA1 CLOSED trade that
     * week showed close_reason=CLOUD, ~10 min after entry (the 2nd
     * HtsPositionMonitor tick), with near-zero variance — looking exactly like
     * {@link HaHuntEngine#cloudHoldExit} was firing on a bug. Production logs
     * showed no "cloud-hold exit" line for any of them, only the ordinary
     * "absent from openPositions()" reconcile-miss line: {@code haHunt} was
     * never even consulted, let alone true (see {@link HaHuntEngineTest} for
     * the proof that cloudHoldExit itself is stable given unchanged data).
     * {@code stampReason()} used to default every un-preset HA-hunt close to
     * "CLOUD" (its comment assumed "no stop/target match => cloud flip"),
     * mislabelling this ordinary vanish as the strategy's own signal.
     */
    @Test
    void manageDoesNotMislabelAReconcileVanishedHaHuntDealAsCloudHold() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        t.setVariant("HA4");
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of()); // never seen open — a reconcile-vanish, not a decided flip
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 100.2, 100.2, bar));

        service.manage();                 // 1st miss — deferred
        int touched = service.manage();   // 2nd — force-closed

        assertThat(touched).isEqualTo(1);
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getCloseReason()).isEqualTo("MANUAL"); // honest "don't know why", same as ribbon
        assertThat(t.getCloseReason()).isNotEqualTo("CLOUD");
        verify(haHunt, never()).cloudHoldExit(any(), any(), anyBoolean(), any());
    }

    /** The other half: a genuine flip must stamp CLOUD itself, at decision time — not by elimination later. */
    @Test
    void haHuntCloudExitStampsCloudReasonItselfBeforeTheReconcilePassCloses() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        t.setVariant("HA4");
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY))); // still open per the broker
        when(haHunt.cloudHoldExit(eq(HtsVariant.HA4), any(), eq(true), any())).thenReturn(true);

        int touched = service.manage();

        assertThat(touched).isEqualTo(1);
        verify(broker).closePosition("d1", 1.0);
        assertThat(t.getCloseReason()).isEqualTo("CLOUD"); // stamped now, not guessed on a later pass
        assertThat(t.getStatus()).isEqualTo("OPEN");        // reconcile flips it to CLOSED next cycle
    }

    @Test
    void manageSelectsTheBooksAccountBeforeReadingPositions() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.accounts()).thenReturn(List.of(
                new com.adam.server.broker.model.Account("acc-m5", "Account m5", "PLN", 1000, 1000, 0, true)));
        when(risk.pickForBook(eq(book), any())).thenReturn(
                new com.adam.server.broker.model.Account("acc-m5", "Account m5", "PLN", 1000, 1000, 0, true));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));

        service.manage();

        verify(broker).selectAccount("acc-m5");
    }

    // ---- manage(): TP1 partial + lock ----

    @Test
    void manageTakesHalfOffAtTp1AndLocksTheRunner() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(102.0, 100.5, false)); // TP1 = 102 hit

        int touched = service.manage();

        assertThat(touched).isEqualTo(1);
        verify(broker).closePosition("d1", 0.5);
        verify(broker).amendPosition("d1", 101.0, false); // break-even + 1R
        assertThat(t.getTp1At()).isNotNull();
        assertThat(t.getRemainingSize()).isEqualTo(0.5);
        assertThat(t.getRunnerStop()).isEqualTo(101.0);
    }

    @Test
    void manageDoesNothingBeforeTp1IsReached() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(101.0, 100.5, false)); // below TP1 102

        assertThat(service.manage()).isEqualTo(0);
        verify(broker, never()).closePosition(anyString(), anyDouble());
        verify(broker, never()).amendPosition(anyString(), anyDouble(), anyBoolean());
    }

    // ---- manage(): runner trail + slow-band exit ----

    @Test
    void manageTrailsTheRunnerUnderTheFastBandAfterTp1() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        t.setTp1At(bar.plusSeconds(900));
        t.setRemainingSize(0.5);
        t.setRunnerStop(101.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(104.0, 102.5, false));

        assertThat(service.manage()).isEqualTo(1);
        verify(broker).amendPosition("d1", 102.5, false);
        assertThat(t.getRunnerStop()).isEqualTo(102.5);
    }

    @Test
    void manageNeverTrailsTheRunnerStopBackwards() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        t.setTp1At(bar.plusSeconds(900));
        t.setRemainingSize(0.5);
        t.setRunnerStop(103.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(104.0, 101.5, false)); // fast edge below current stop

        assertThat(service.manage()).isEqualTo(0);
        verify(broker, never()).amendPosition(anyString(), anyDouble(), anyBoolean());
    }

    @Test
    void manageFlattensTheRunnerOnABodyBeyondTheSlowBand() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        t.setTp1At(bar.plusSeconds(900));
        t.setRemainingSize(0.5);
        t.setRunnerStop(101.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(103.0, 102.0, true)); // body beyond slow band

        assertThat(service.manage()).isEqualTo(1);
        verify(broker).closePosition("d1", 0.5);
    }

    // ---- manage(): TP1 partial P/L backfill + candle cache ----

    @Test
    void manageBackfillsTheTp1PartialPnlFromTheFeed() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        t.setTp1At(bar.plusSeconds(600));
        t.setRemainingSize(0.5);
        t.setRunnerStop(101.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));
        when(broker.transactionHistory(any(), any(), any(Duration.class)))
                .thenReturn(List.of(new BrokerTransaction(bar.plusSeconds(660), "TRADE", "DE40",
                        24.0, "d1", "partial close")));
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(103.0, 101.5, false)); // trail, no exit

        service.manage();

        assertThat(t.getTp1Pnl()).isEqualTo(24.0);
    }

    @Test
    void manageFetchesCandlesOncePerEpicPerCycle() {
        HtsTradeEntity a = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        HtsTradeEntity b = open("d2", Direction.BUY, 100.0, 99.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(a, b));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY), pos("d2", Direction.BUY)));
        when(engine.runnerRead(any(), eq(true)))
                .thenReturn(new HtsEngine.RunnerRead(100.5, 99.5, false)); // below TP1, nothing to do

        service.manage();

        verify(market, times(1)).candles(anyString(), any(), any(), any(), anyInt());
    }

    // ---- scorecard (E-4) ----

    @Test
    void scorecardAggregatesPerVariant() {
        HtsTradeEntity w1 = closed("CORE", 2.0, 40.0, bar.plusSeconds(100));
        HtsTradeEntity l1 = closed("CORE", -1.0, -20.0, bar.plusSeconds(200));
        HtsTradeEntity w2 = closed("CORE", 2.0, 40.0, bar.plusSeconds(300));
        HtsTradeEntity openOne = open("dX", Direction.BUY, 100.0, 99.0, 1.0);
        openOne.setVariant("CORE");
        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(w1, l1, w2, openOne));

        List<HtsScorecardRow> board = service.scorecard();

        HtsScorecardRow core = board.stream().filter(r -> r.variant().equals("CORE")).findFirst().orElseThrow();
        assertThat(core.htf()).isEqualTo("H4");
        assertThat(core.ltf()).isEqualTo("M15");
        assertThat(core.openTrades()).isEqualTo(1);
        assertThat(core.closedTrades()).isEqualTo(3);
        assertThat(core.wins()).isEqualTo(2);
        assertThat(core.losses()).isEqualTo(1);
        assertThat(core.sumR()).isEqualTo(3.0);
        assertThat(core.avgR()).isEqualTo(1.0);
        assertThat(core.winRate()).isEqualTo(0.67);
        assertThat(core.maxDrawdownR()).isEqualTo(1.0); // cum 2 → 1 → 3, peak 2, trough 1
        assertThat(core.realisedPnl()).isEqualTo(60.0);
        // every variant present even with no trades
        assertThat(board.stream().map(HtsScorecardRow::variant))
                .contains("CORE", "SWING", "FAST", "CORE_LIVE");
        HtsScorecardRow fast = board.stream().filter(r -> r.variant().equals("FAST")).findFirst().orElseThrow();
        assertThat(fast.closedTrades()).isZero();
    }

    // ---- helpers ----

    private HtsTradeEntity closed(String variant, double r, double pnl, Instant exitAt) {
        HtsTradeEntity t = new HtsTradeEntity();
        t.setVariant(variant);
        t.setSymbol("GER40");
        t.setStatus("CLOSED");
        t.setRMultiple(r);
        t.setPnl(pnl);
        t.setPnlCcy("PLN");
        t.setOpenedAt(exitAt.minusSeconds(3600));
        t.setExitAt(exitAt);
        return t;
    }

    private HtsTradeEntity open(String dealId, Direction dir, double entry, double stop, double size) {
        HtsTradeEntity t = new HtsTradeEntity();
        t.setId((long) dealId.hashCode()); // reconcile keys its miss-streak by id
        t.setVariant("CORE");
        t.setBook(book);
        t.setSymbol("GER40");
        t.setEpic("DE40");
        t.setDirection(dir.name());
        t.setEntry(entry);
        t.setStopLevel(stop);
        t.setSize(size);
        t.setRunnerStop(stop);
        t.setStatus("OPEN");
        t.setDealId(dealId);
        t.setOpenedAt(bar);
        return t;
    }

    private static Position pos(String dealId, Direction dir) {
        return new Position(dealId, "r", "DE40", dir, 1.0, 100, 99.0, null, 0, "PLN", Instant.now());
    }

    // ---- alert-driven close (TradingView) ----

    @Test
    void closeForAlertClosesAtBrokerStampsTheReasonAndFinalisesTheRow() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, 1.0); // leg = 2.0
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));

        boolean ok = service.closeForAlert(t, "ALGO_TP");

        assertThat(ok).isTrue();
        verify(broker).closePosition("d1", 1.0);
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getCloseReason()).isEqualTo("ALGO_TP");
        assertThat(t.getRMultiple()).isEqualTo(1.5);       // (103 - 100) / 2, estimated from the mark
        verify(sink).onClose(t);
    }

    @Test
    void closeForAlertLeavesTheTradeOpenWhenTheBrokerCloseFails() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, 1.0);
        org.mockito.Mockito.doThrow(new BrokerException("down")).when(broker).closePosition(anyString(), anyDouble());

        boolean ok = service.closeForAlert(t, "ALGO_INVALID");

        assertThat(ok).isFalse();
        assertThat(t.getStatus()).isEqualTo("OPEN");
        assertThat(t.getCloseReason()).isNull();
        verify(sink, never()).onClose(any());
    }

    @Test
    void openPositionOfFindsTheOpenRowOfTheVariantAndSymbol() {
        HtsTradeEntity a = open("dA", Direction.BUY, 100.0, 98.0, 1.0);
        a.setVariant("TV_ALGO_H1");
        HtsTradeEntity b = open("dB", Direction.SELL, 100.0, 102.0, 1.0);
        b.setVariant("TV_ALGO_H4");
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(a, b));

        assertThat(service.openPositionOf(HtsVariant.TV_ALGO_H1, "ger40")).isSameAs(a);
        assertThat(service.openPositionOf(HtsVariant.TV_ALGO_H4, "GER40")).isSameAs(b);
        assertThat(service.openPositionOf(HtsVariant.TV_ALGO_H1, "XAU")).isNull();
    }

    @Test
    void tvVariantsHaveNoStrategyExitInManage() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, 1.0);
        t.setVariant("TV_ALGO_H1");
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of(pos("d1", Direction.BUY)));

        service.manage();

        assertThat(t.getStatus()).isEqualTo("OPEN");
        verify(broker, never()).closePosition(anyString(), anyDouble());
        verify(broker, never()).amendPosition(anyString(), anyDouble(), anyBoolean());
    }

    // ---- TradingView partial take-profit ----

    private HtsTradeEntity tvOpen(double size) {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 98.0, size); // leg = 2.0
        t.setVariant("TV_ALGO_H1");
        return t;
    }

    @Test
    void partialTakeProfitClosesTwentyPercentOfTheOriginalSizeAndBanksItsR() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        when(broker.openPositions()).thenReturn(List.of(position("d1", Direction.BUY, 0.8)));

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.ok()).isTrue();
        assertThat(o.status()).isEqualTo("PARTIAL_TP");
        verify(broker).reducePosition("d1", "DE40", Direction.BUY, 0.2);
        verify(broker, never()).closePosition(anyString(), anyDouble());   // the DELETE would close EVERYTHING
        assertThat(t.getStatus()).isEqualTo("OPEN");
        assertThat(t.getRemainingSize()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(t.getRealisedR()).isEqualTo(0.3);        // (103-100)/2 = 1.5R on 20% of the size
        assertThat(t.getTp1At()).isNotNull();
    }

    @Test
    void invalidationAfterPartialsAddsTheLastLegToTheBankedR() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(
                new MarketPrice("DE40", 103.0, 103.0, bar),    // the TP slice
                new MarketPrice("DE40", 99.0, 99.0, bar));     // the invalidation close
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());
        when(broker.openPositions()).thenReturn(List.of(position("d1", Direction.BUY, 0.8)));
        service.partialTakeProfit(t, 0.2);

        boolean ok = service.closeForAlert(t, "ALGO_INVALID");

        assertThat(ok).isTrue();
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        // 0.3 banked + (-0.5R on the remaining 80%) = -0.1
        assertThat(t.getRMultiple()).isEqualTo(-0.1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(t.getCloseReason()).isEqualTo("ALGO_INVALID");
    }

    @Test
    void theFinalTakeProfitSliceFlattensWhenTheRemainderWouldBeBelowTheMinimumDeal() {
        HtsTradeEntity t = tvOpen(0.02);
        t.setRemainingSize(0.01);
        t.setRealisedR(0.2);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 104.0, 104.0, bar));
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.status()).isEqualTo("CLOSED");
        verify(broker).closePosition("d1", 0.01);
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getCloseReason()).isEqualTo("ALGO_TP");
        assertThat(t.getRMultiple()).isEqualTo(1.2, org.assertj.core.data.Offset.offset(1e-9)); // 0.2 + 2R*0.5
    }

    @Test
    void aSliceBelowTheMinimumDealIsRaisedToTheMinimum() {
        HtsTradeEntity t = tvOpen(0.05);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.02, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 101.0, 101.0, bar));

        when(broker.openPositions()).thenReturn(List.of(position("d1", Direction.BUY, 0.03)));

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);   // 20% = 0.01 < min 0.02

        assertThat(o.status()).isEqualTo("PARTIAL_TP");
        verify(broker).reducePosition("d1", "DE40", Direction.BUY, 0.02);
    }

    @Test
    void aFailedPartialCloseChangesNothing() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        org.mockito.Mockito.doThrow(new BrokerException("down")).when(broker)
                .reducePosition(anyString(), anyString(), any(), anyDouble());

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.ok()).isFalse();
        assertThat(t.getRemainingSize()).isNull();
        assertThat(t.getRealisedR()).isNull();
    }

    private static Position position(String dealId, Direction dir, double size) {
        return new Position(dealId, "r-" + dealId, "DE40", dir, size, 100, 98.0, null, 0, "PLN", Instant.now());
    }

    @Test
    void ifTheBrokerRekeyedTheDealAfterThePartialTheTradeAdoptsTheNewDealId() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        when(broker.openPositions()).thenReturn(List.of(position("d2", Direction.BUY, 0.8)));

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.status()).isEqualTo("PARTIAL_TP");
        assertThat(t.getDealId()).isEqualTo("d2");
        assertThat(t.getRemainingSize()).isEqualTo(0.8);
        assertThat(t.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void ifThePartialLeftNoPositionTheTradeIsBookedAsAFullTpCloseNotLeftOpen() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        when(broker.transactionHistory(any(), any(), any(Duration.class))).thenReturn(List.of());
        when(broker.openPositions()).thenReturn(List.of());          // the broker closed everything

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.status()).isEqualTo("CLOSED");
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getCloseReason()).isEqualTo("ALGO_TP");
        verify(sink).onClose(t);
    }

    @Test
    void aHedgingAccountsCounterPositionIsRevertedAndTheTradeLeftUntouched() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        when(broker.openPositions()).thenReturn(List.of(position("d1", Direction.BUY, 1.0),
                position("hedge", Direction.SELL, 0.2)));

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.ok()).isFalse();
        assertThat(o.status()).isEqualTo("PARTIAL_UNSUPPORTED");
        verify(broker).closePosition("hedge", 0);                    // the counter leg is closed, not the position
        assertThat(t.getRemainingSize()).isNull();
        assertThat(t.getRealisedR()).isNull();
    }

    @Test
    void aPartialTpWhoseSizeDidNotShrinkAtTheBrokerBanksNoR() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        when(broker.openPositions()).thenReturn(List.of(position("d1", Direction.BUY, 1.0)));   // still the full size

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.ok()).isFalse();
        assertThat(o.status()).isEqualTo("PARTIAL_NOT_APPLIED");
        assertThat(t.getRealisedR()).isNull();
        assertThat(t.getRemainingSize()).isNull();
        assertThat(t.getTp1At()).isNull();
    }

    @Test
    void aRejectedReducingOrderIsReportedWithTheBrokersReasonAndBanksNothing() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        when(broker.reducePosition(anyString(), anyString(), any(), anyDouble()))
                .thenReturn(new OrderAck("ref-x", "d-x", "OK"));
        when(broker.confirm("ref-x")).thenReturn(new com.adam.server.broker.model.Confirmation(
                "ref-x", "d-x", "DELETED", "REJECTED", "RC_SOMETHING", "DE40", Direction.SELL, null, null));

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.ok()).isFalse();
        assertThat(o.status()).isEqualTo("PARTIAL_REJECTED");
        assertThat(o.detail()).contains("RC_SOMETHING");
        assertThat(t.getRealisedR()).isNull();
        verify(broker, never()).openPositions();     // rejected before we even look at the position
    }

    @Test
    void theFailureDetailCarriesTheBrokersMessageNotJustTheExceptionClass() {
        HtsTradeEntity t = tvOpen(1.0);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103.0, 103.0, bar));
        org.mockito.Mockito.doThrow(new BrokerException("Rejected. EURUSD is currently closed")).when(broker)
                .reducePosition(anyString(), anyString(), any(), anyDouble());

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.status()).isEqualTo("CLOSE_FAILED");
        assertThat(o.detail()).contains("currently closed");
    }

    @Test
    void thePartialDetailKeepsFullSizePrecision() {
        HtsTradeEntity t = tvOpen(0.05);
        when(broker.marketRules("DE40")).thenReturn(new com.adam.server.broker.model.MarketRules("DE40", 0.01, 2, 0, 0));
        when(broker.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 101.0, 101.0, bar));
        when(broker.openPositions()).thenReturn(List.of(position("d1", Direction.BUY, 0.04)));

        HtsTradeService.AlertOutcome o = service.partialTakeProfit(t, 0.2);

        assertThat(o.detail()).contains("closed 0.01 of 0.05, remaining 0.04");
    }
}
