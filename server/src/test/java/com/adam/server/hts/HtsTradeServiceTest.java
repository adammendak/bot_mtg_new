package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.broker.model.Candle;
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
    HtsTradeSink sink;

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
        service = new HtsTradeService(repo, books, engine, props, List.of(sink));
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
    void manageClosesAVanishedDealWithItsOutcome() {
        HtsTradeEntity t = open("d1", Direction.BUY, 100.0, 99.0, 1.0);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(t));
        when(broker.openPositions()).thenReturn(List.of()); // deal gone
        when(broker.transactionHistory(any(), any(), any(Duration.class)))
                .thenReturn(List.of(new BrokerTransaction(bar.plusSeconds(3600), "TRADE", "DE40",
                        -1.0, "d1", "stopped out")));

        int touched = service.manage();

        assertThat(touched).isEqualTo(1);
        assertThat(t.getStatus()).isEqualTo("CLOSED");
        assertThat(t.getPnl()).isEqualTo(-1.0);
        assertThat(t.getRMultiple()).isEqualTo(-1.0);
        assertThat(t.getCloseReason()).isEqualTo("STOP");
        verify(sink).onClose(t);
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
}
