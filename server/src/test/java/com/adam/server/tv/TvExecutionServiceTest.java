package com.adam.server.tv;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.hts.HtsExecutionGate;
import com.adam.server.hts.HtsScan;
import com.adam.server.hts.HtsTradeService;
import com.adam.server.hts.HtsVariant;
import com.adam.server.ops.FeatureFlags;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.persistence.TvSignalEntity;
import com.adam.server.persistence.TvSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TvExecutionServiceTest {

    HtsExecutionGate gate;
    HtsTradeService trades;
    BrokerBooks books;
    BrokerClient market;
    AppProperties props;
    FeatureFlags flags;
    TvSignalRepository repo;
    TvExecutionService service;

    @BeforeEach
    void setUp() {
        gate = mock(HtsExecutionGate.class);
        trades = mock(HtsTradeService.class);
        books = mock(BrokerBooks.class);
        market = mock(BrokerClient.class);
        props = new AppProperties();
        flags = FeatureFlags.forTest();
        when(books.marketData()).thenReturn(market);
        // 30 flat candles, range 2 (high 101 / low 99) -> Wilder ATR14 = 2
        List<Candle> candles = new ArrayList<>();
        Instant t0 = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < 30; i++) {
            candles.add(new Candle(t0.plusSeconds(3600L * i), 100, 101, 99, 100, 0));
        }
        when(market.candles(anyString(), any(), any(), any(), anyInt())).thenReturn(candles);
        repo = mock(TvSignalRepository.class);
        service = new TvExecutionService(gate, trades, books, props, flags, repo);
    }

    private TvSignalEntity ev(String strat, String event, String side, String sym, double price) {
        TvSignalEntity e = new TvSignalEntity();
        e.setStrat(strat);
        e.setEvent(event);
        e.setSide(side);
        e.setSymbol(sym);
        e.setPrice(price);
        e.setBarTime("2026-10-20T10:00:00Z");
        return e;
    }

    private HtsTradeEntity open(HtsVariant v, String sym, String dir) {
        HtsTradeEntity t = new HtsTradeEntity();
        t.setVariant(v.name());
        t.setSymbol(sym);
        t.setDirection(dir);
        t.setStatus("OPEN");
        return t;
    }

    @Test
    void entryRecordsOpenedTradeLinkAndTheLevelsUsedOnTheSignalRow() {
        HtsTradeEntity filled = open(HtsVariant.TV_ALGO_H1, "XAU", "BUY");
        filled.setId(77L);
        when(trades.openPositionOf(HtsVariant.TV_ALGO_H1, "XAU")).thenReturn(null, filled); // none before, filled after
        TvSignalEntity e = ev("algopro-h1", "entry", "BUY", "XAU", 100.0);

        service.handle(e);

        assertThat(e.getExecStatus()).isEqualTo("OPENED");
        assertThat(e.getTradeId()).isEqualTo(77L);
        assertThat(e.getStopUsed()).isEqualTo(95.0);
        assertThat(e.getTargetUsed()).isEqualTo(104.0);
        assertThat(e.getAtr()).isEqualTo(2.0);
        verify(repo).save(e);
    }

    @Test
    void entryThatTheGateDidNotOpenIsRecordedAsNotOpened() {
        TvSignalEntity e = ev("algopro-h1", "entry", "BUY", "XAU", 100.0);

        service.handle(e);   // trades.openPositionOf stays null: gate skipped / broker rejected

        assertThat(e.getExecStatus()).isEqualTo("NOT_OPENED");
        assertThat(e.getTradeId()).isNull();
    }

    @Test
    void skipsAreRecordedWithTheirReason() {
        TvSignalEntity off = ev("algopro-h1", "entry", "BUY", "XAU", 100.0);
        flags.set("tv.execution", false, "test");
        service.handle(off);
        flags.reset("tv.execution");
        TvSignalEntity universe = ev("algopro-h1", "entry", "BUY", "GER40", 100.0);
        service.handle(universe);
        TvSignalEntity unknown = ev("other", "entry", "BUY", "XAU", 100.0);
        service.handle(unknown);
        TvSignalEntity noPos = ev("algopro-h1", "tp", "BUY", "XAU", 100.0);
        service.handle(noPos);

        assertThat(off.getExecStatus()).isEqualTo("FLAG_OFF");
        assertThat(universe.getExecStatus()).isEqualTo("NOT_IN_UNIVERSE");
        assertThat(unknown.getExecStatus()).isEqualTo("RECORDED_ONLY");
        assertThat(noPos.getExecStatus()).isEqualTo("NO_POSITION");
    }

    @Test
    void takeProfitRecordsThePartialSliceOnTheSignalRow() {
        HtsTradeEntity pos = open(HtsVariant.TV_ALGO_H1, "XAU", "BUY");
        pos.setId(5L);
        when(trades.openPositionOf(HtsVariant.TV_ALGO_H1, "XAU")).thenReturn(pos);
        when(trades.partialTakeProfit(pos, 0.2))
                .thenReturn(new HtsTradeService.AlertOutcome(true, "PARTIAL_TP", "closed 0.2 of 1.0"));
        TvSignalEntity e = ev("algopro-h1", "tp", "BUY", "XAU", 100.0);

        service.handle(e);

        assertThat(e.getExecStatus()).isEqualTo("PARTIAL_TP");
        assertThat(e.getTradeId()).isEqualTo(5L);
        assertThat(e.getExecDetail()).isEqualTo("closed 0.2 of 1.0");
    }

    @Test
    void invalidationRecordsTheFullCloseAndItsR() {
        HtsTradeEntity pos = open(HtsVariant.TV_ALGO_H1, "XAU", "BUY");
        pos.setId(5L);
        pos.setRMultiple(-0.4);
        when(trades.openPositionOf(HtsVariant.TV_ALGO_H1, "XAU")).thenReturn(pos);
        when(trades.closeForAlert(pos, "ALGO_INVALID")).thenReturn(true);
        TvSignalEntity e = ev("algopro-h1", "invalid", "BUY", "XAU", 100.0);

        service.handle(e);

        assertThat(e.getExecStatus()).isEqualTo("CLOSED");
        assertThat(e.getExecDetail()).isEqualTo("R=-0.4");
    }

    @Test
    void theEntryIsSizedFromTheCapitalPriceNotTheTradingViewClose() {
        when(market.marketPrice(anyString()))
                .thenReturn(new com.adam.server.broker.model.MarketPrice("GOLD", 200.0, 200.0, Instant.now()));

        service.handle(ev("algopro-h1", "entry", "BUY", "XAU", 100.0));

        ArgumentCaptor<HtsScan> c = ArgumentCaptor.forClass(HtsScan.class);
        verify(gate).executeSignal(c.capture());
        assertThat(c.getValue().entry()).isEqualTo(200.0);
        assertThat(c.getValue().stopLevel()).isEqualTo(195.0);   // 200 - 2.5 x ATR(2)
    }

    @Test
    void entryBuildsAnAtrSafetyStopAndHandsTheSignalToTheGate() {
        service.handle(ev("algopro-h1", "entry", "BUY", "XAU", 100.0));

        ArgumentCaptor<HtsScan> c = ArgumentCaptor.forClass(HtsScan.class);
        verify(gate).executeSignal(c.capture());
        HtsScan s = c.getValue();
        assertThat(s.variant()).isEqualTo(HtsVariant.TV_ALGO_H1);
        assertThat(s.symbol()).isEqualTo("XAU");
        assertThat(s.direction()).isEqualTo(Direction.BUY);
        assertThat(s.entry()).isEqualTo(100.0);
        assertThat(s.stopLevel()).isEqualTo(95.0);          // 100 - 2.5 x ATR(2)
        assertThat(s.targetLevel()).isEqualTo(104.0);       // 100 + 2.0 x ATR
        assertThat(s.timestamp()).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
    }

    @Test
    void shortEntryPutsTheStopAboveThePrice() {
        service.handle(ev("algopro-h4", "entry", "SELL", "EURUSD", 100.0));

        ArgumentCaptor<HtsScan> c = ArgumentCaptor.forClass(HtsScan.class);
        verify(gate).executeSignal(c.capture());
        assertThat(c.getValue().variant()).isEqualTo(HtsVariant.TV_ALGO_H4);
        assertThat(c.getValue().stopLevel()).isEqualTo(105.0);
        assertThat(c.getValue().targetLevel()).isEqualTo(96.0);
    }

    @Test
    void nothingIsExecutedWhenTheFlagIsOffOrTheStratOrSymbolIsUnknown() {
        flags.set("tv.execution", false, "test");
        service.handle(ev("algopro-h1", "entry", "BUY", "XAU", 100.0));
        flags.reset("tv.execution");
        service.handle(ev("other-strat", "entry", "BUY", "XAU", 100.0));
        service.handle(ev("algopro-h1", "entry", "BUY", "GER40", 100.0)); // not in the TV universe

        verify(gate, never()).executeSignal(any());
    }

    @Test
    void takeProfitAndInvalidationCloseTheMatchingSideOnly() {
        HtsTradeEntity longPos = open(HtsVariant.TV_ALGO_H1, "XAU", "BUY");
        when(trades.openPositionOf(HtsVariant.TV_ALGO_H1, "XAU")).thenReturn(longPos);
        when(trades.partialTakeProfit(any(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(new HtsTradeService.AlertOutcome(true, "PARTIAL_TP", "x"));

        service.handle(ev("algopro-h1", "tp", "SELL", "XAU", 100.0));      // wrong side — ignored
        service.handle(ev("algopro-h1", "invalid", "SELL", "XAU", 100.0));
        verify(trades, never()).closeForAlert(any(), anyString());
        verify(trades, never()).partialTakeProfit(any(), org.mockito.ArgumentMatchers.anyDouble());

        service.handle(ev("algopro-h1", "tp", "BUY", "XAU", 100.0));        // TP = a 20% slice, NOT a full close
        verify(trades).partialTakeProfit(longPos, 0.2);
        verify(trades, never()).closeForAlert(any(), anyString());
        service.handle(ev("algopro-h1", "invalid", "BUY", "XAU", 100.0));   // invalidation = full close
        verify(trades).closeForAlert(longPos, "ALGO_INVALID");
        verify(gate, never()).executeSignal(any());
    }

    @Test
    void anOppositeEntryClosesTheOpenPositionFirstThenOpensTheNewSide() {
        HtsTradeEntity shortPos = open(HtsVariant.TV_ALGO_H1, "BTC", "SELL");
        when(trades.openPositionOf(HtsVariant.TV_ALGO_H1, "BTC")).thenReturn(shortPos);
        when(trades.closeForAlert(shortPos, "ALGO_REVERSE")).thenReturn(true);

        service.handle(ev("algopro-h1", "entry", "BUY", "BTC", 100.0));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(trades, gate);
        order.verify(trades).closeForAlert(shortPos, "ALGO_REVERSE");
        order.verify(gate).executeSignal(any());
    }

    @Test
    void aFailedReversalCloseSkipsTheNewEntryAndADuplicateEntryIsIgnored() {
        HtsTradeEntity shortPos = open(HtsVariant.TV_ALGO_H1, "BTC", "SELL");
        when(trades.openPositionOf(HtsVariant.TV_ALGO_H1, "BTC")).thenReturn(shortPos);
        when(trades.closeForAlert(eq(shortPos), anyString())).thenReturn(false);
        service.handle(ev("algopro-h1", "entry", "BUY", "BTC", 100.0));
        verify(gate, never()).executeSignal(any());

        service.handle(ev("algopro-h1", "entry", "SELL", "BTC", 100.0));    // already short
        verify(trades, org.mockito.Mockito.times(1)).closeForAlert(any(), anyString());
        verify(gate, never()).executeSignal(any());
    }

    @Test
    void tooFewCandlesForAtrSkipsTheEntry() {
        when(market.candles(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of());

        service.handle(ev("algopro-h1", "entry", "BUY", "XAU", 100.0));

        verify(gate, never()).executeSignal(any());
    }
}
