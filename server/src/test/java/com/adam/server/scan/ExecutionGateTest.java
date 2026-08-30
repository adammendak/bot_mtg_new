package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.persistence.SddExecutionEntity;
import com.adam.server.persistence.SddExecutionRepository;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.sdd.SddScan;
import com.adam.server.web.dto.AccountView;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Computron execution rules behind EXECUTION_ENABLED: two-deal placement with a hard
 * 1R TP on ONE deal (stop + TP PUT together), never DELETE+size, TP-ticket-gone ->
 * runner keeps its 2.5× stop and H1-trails (no 2R, no break-even), skip when the name
 * is open, 4-name cap, idempotent retry, live account gate, and never touching the
 * stocks book (TQQQ/CRCL/SPOT/SHOP) or Glowne.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionGateTest {

    @Mock
    BrokerClient demoClient;
    @Mock
    SignalWebhookPublisher webhooks;
    @Mock
    TelegramNotifier telegram;
    @Mock
    MonitoringService monitor;

    AppProperties props;
    RiskPolicy risk;
    SddExecutionState state;
    BrokerBooks books;
    com.adam.server.ops.FeatureFlags flags;
    ExecutionGate gate;

    final Instant bar = Instant.parse("2026-08-28T12:00:00Z");

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setBroker("capital");
        props.setExecutionEnabled(true);
        props.setMinDealSize(0.01);
        props.setMaxOpenNames(4);
        props.setNewsCalendarUrl("");
        risk = new RiskPolicy(props);
        state = new SddExecutionState(mock(SddExecutionRepository.class));
        books = new BrokerBooks(demoClient, new UnavailableBrokerClient("live", "test"),
                new UnavailableBrokerClient("glowne", "test"),
                new UnavailableBrokerClient("swing", "test"),
                new UnavailableBrokerClient("hts", "test"));
        flags = com.adam.server.ops.FeatureFlags.forTest();
        flags.set("sdd.execution", true, "test");
        gate = new ExecutionGate(props, books, risk, state, webhooks, telegram, monitor, com.adam.server.scan.Mailer.disabled(), flags);

        when(demoClient.book()).thenReturn("demo");
        when(demoClient.id()).thenReturn("capital");
        when(demoClient.configured()).thenReturn(true);
        when(demoClient.isSessionOpen()).thenReturn(false);
        when(demoClient.accounts())
                .thenReturn(List.of(new Account("1", "Account", "PLN", 1000, 1000, 0, true)));
    }

    @Test
    void fullStackPlacesTwoDealsWith1RtpOnOneAndStopOnBoth() {
        when(demoClient.openPositions()).thenReturn(List.of());
        when(demoClient.placeMarketOrder(any()))
                .thenReturn(new OrderAck("refA", null, "SUBMITTED"))
                .thenReturn(new OrderAck("refB", null, "SUBMITTED"));
        when(demoClient.confirm("refA"))
                .thenReturn(new Confirmation("refA", "dealA", "OPEN", "ACCEPTED", "DE40", Direction.BUY, 100.0, 2.0));
        when(demoClient.confirm("refB"))
                .thenReturn(new Confirmation("refB", "dealB", "OPEN", "ACCEPTED", "DE40", Direction.BUY, 100.0, 2.0));

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(demoClient, times(2)).placeMarketOrder(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        // Deal 1 (TP ticket): stop 2.5× AND 1R profit level PUT together.
        OrderRequest tp = captor.getAllValues().get(0);
        assertThat(tp.direction()).isEqualTo(Direction.BUY);
        assertThat(tp.size()).isEqualTo(2.0);      // 4.0 risk-sized / 2
        assertThat(tp.stopLevel()).isEqualTo(97.5); // entry - 2.5 * atr
        assertThat(tp.profitLevel()).isEqualTo(101.0); // hard 1R TP
        assertThat(tp.trailingStop()).isFalse();
        // Deal 2 (runner): stop only, NO TP.
        OrderRequest runner = captor.getAllValues().get(1);
        assertThat(runner.stopLevel()).isEqualTo(97.5);
        assertThat(runner.profitLevel()).isNull();
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e).isNotNull();
        assertThat(e.twoTickets).isTrue();
        assertThat(e.ticketA).isEqualTo("dealA");
        assertThat(e.ticketB).isEqualTo("dealB");
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("placed"), eq(""));
    }

    @Test
    void neverClosesWithSizeAndNoTrailingStop() {
        // Even in the single-ticket close path we only ever use closePosition(id, 0)
        // and amendPosition(id, stop, false). A size != 0 DELETE is never issued.
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", null, false));
        Position pos = new Position("dealA", "refA", "DE40", Direction.BUY, 4.0, 100, 97.5, 101.0, 5, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(pos));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        verify(demoClient, never()).closePosition(anyString(), anyDouble());
        verify(demoClient, never()).amendPosition(anyString(), anyDouble(), eq(true));
        // single ticket present -> still open, nothing to do
        assertThat(state.get("demo", "GER40")).isNotNull();
    }

    @Test
    void whenTpTicketGoneRunnerKeepsStopAndH1TrailsNoBe() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        // Only the runner (dealB) is open; the TP ticket (dealA) is gone.
        Position runner = new Position("dealB", "refB", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 0, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(runner));
        when(demoClient.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 100, 100, Instant.now()));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        // TP ticket gone -> tpFilled; runner stop NEVER amended to entry/BE (entry=100).
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e.tpFilled).isTrue();
        verify(demoClient, never()).amendPosition(eq("dealB"), eq(100.0), anyBoolean());
        // No 2R close of a whole ticket (no closePosition with size).
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
    }

    @Test
    void whenTpTicketGoneRunnerTrailsInFavourFloorIsOriginalStop() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        Position runner = new Position("dealB", "refB", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 0, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(runner));
        // Market moved to 103: trail = 103 - 2.5 = 100.5 > original stop 97.5 -> ratchet up.
        when(demoClient.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103, 103, Instant.now()));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e.tpFilled).isTrue();
        // Runner amended via PUT stopLevel (trailingStop=false) to the trailed stop.
        verify(demoClient).amendPosition("dealB", 100.5, false);
        // Never moved to break-even / entry.
        verify(demoClient, never()).amendPosition("dealB", 100.0, false);
    }

    @Test
    void whenRunnerGoneBothTicketsDoneRemovesRow() {
        // Realistic flow: TP ticket filled first, then the runner also exits.
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true, true, true));
        // Both tickets gone.
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        assertThat(state.get("demo", "GER40")).isNull();
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("closed"), anyString());
    }

    @Test
    void flipWithoutFullStackDoesNotPlace() {
        SddScan flip = new SddScan(bar, "GER40", "DE40", Direction.BUY,
                new SddScan.Setup(true, false, false, false),
                97.5, 1, 1, 100, false, "HA flip without full stack",
                List.of(), true, true, false, "H4 aligned", true);
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.executeBook("demo", List.of(flip), view("demo", 0), false);

        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void skipWhenNameAlreadyOpen() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenReturn(List.of(
                openPos("dealA", "DE40"), openPos("dealB", "DE40")));

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar.plusSeconds(900))),
                view("demo", 0), false);

        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("name already open"));
    }

    @Test
    void skipWhenFourNamesAreOpen() {
        seedEntry("GER40", "DE40");
        seedEntry("XAU", "GOLD");
        seedEntry("US100", "US100");
        seedEntry("EURUSD", "EURUSD");
        when(demoClient.openPositions()).thenReturn(List.of(
                openPos("d-GER40", "DE40"),
                openPos("d-XAU", "GOLD"),
                openPos("d-US100", "US100"),
                openPos("d-EURUSD", "EURUSD")));

        gate.executeBook("demo", List.of(fullStack("BTC", "BTCUSD", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("demo"), eq("BTC"), eq("BUY"), eq("skip"),
                contains("max 4 names open"));
    }

    @Test
    void skipOnDayHalt() {
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", -35), false);

        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"), startsWith("halt"));
    }

    @Test
    void sameBarDoesNotPlaceTwice() {
        when(demoClient.openPositions()).thenReturn(List.of());
        when(demoClient.placeMarketOrder(any()))
                .thenReturn(new OrderAck("refA", null, "SUBMITTED"))
                .thenReturn(new OrderAck("refB", null, "SUBMITTED"));
        when(demoClient.confirm(anyString()))
                .thenReturn(new Confirmation("r", "d", "OPEN", "ACCEPTED", "DE40", Direction.BUY, 100.0, 2.0));

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);
        // webhook retry / same bar re-scan must NOT open a second entry
        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        verify(demoClient, times(2)).placeMarketOrder(any()); // only the first place's two tickets
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("duplicate bar already placed"));
    }

    @Test
    void liveRefusesWhenBotKontoMissing() {
        BrokerClient liveClient = mock(BrokerClient.class);
        when(liveClient.book()).thenReturn("live");
        when(liveClient.id()).thenReturn("capital");
        when(liveClient.configured()).thenReturn(true);
        when(liveClient.isSessionOpen()).thenReturn(false);
        when(liveClient.accounts())
                .thenReturn(List.of(new Account("2", "Other Account", "PLN", 400, 400, 0, false)));
        when(liveClient.openPositions()).thenReturn(List.of());
        BrokerBooks liveBooks = new BrokerBooks(demoClient, liveClient,
                new UnavailableBrokerClient("glowne", "test"),
                new UnavailableBrokerClient("swing", "test"),
                new UnavailableBrokerClient("hts", "test"));
        ExecutionGate liveGate = new ExecutionGate(props, liveBooks, risk, state, webhooks, telegram, monitor, com.adam.server.scan.Mailer.disabled(), flags);

        liveGate.executeBook("live", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("live", 0), false);

        verify(liveClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("live"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("LIVE requires account"));
    }

    @Test
    void neverTouchesNeverFlattenEpics() {
        state.put(new SddExecutionState.Entry("demo", "SPOT", "SPOT", Direction.BUY, bar,
                100, 1, 97.5, "tq1", "tq2", true));
        Position pos = new Position("tq1", "r", "SPOT", Direction.BUY, 1.0, 100, 97.5, null, 5, "USD", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(pos));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        verify(demoClient, never()).closePosition(anyString(), anyDouble());
        verify(demoClient, never()).amendPosition(anyString(), anyDouble(), anyBoolean());
        // the SPOT entry is left alone (not managed)
        assertThat(state.get("demo", "SPOT")).isNotNull();
    }

    @Test
    void singleTicketPlacedWhenPerTicketBelowMinDeal() {
        props.setMinDealSize(3.0); // 4.0/2 = 2.0 < 3.0 -> one ticket of full size
        when(demoClient.openPositions()).thenReturn(List.of());
        when(demoClient.placeMarketOrder(any())).thenReturn(new OrderAck("refA", null, "SUBMITTED"));
        when(demoClient.confirm("refA"))
                .thenReturn(new Confirmation("refA", "dealA", "OPEN", "ACCEPTED", "DE40", Direction.BUY, 100.0, 4.0));

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(demoClient, times(1)).placeMarketOrder(captor.capture());
        // single ticket = TP ticket: stop + 1R TP together.
        assertThat(captor.getValue().size()).isEqualTo(4.0);
        assertThat(captor.getValue().stopLevel()).isEqualTo(97.5);
        assertThat(captor.getValue().profitLevel()).isEqualTo(101.0);
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e.twoTickets).isFalse();
    }

    @Test
    void executionDisabledDoesNotPlaceAmendOrClose() {
        flags.set("sdd.execution", false, "test");
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        Position pos = new Position("dealA", "refA", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 5, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(pos));

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        verify(demoClient, never()).placeMarketOrder(any());
        verify(demoClient, never()).amendPosition(anyString(), anyDouble(), anyBoolean());
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
    }

    @Test
    void glowneIsNeverExecuted() {
        // Glowne client is not wired into execution; ScanService only calls demo/live.
        // Assert the gate refuses to run on a glowne client (not configured / not present).
        when(demoClient.openPositions()).thenReturn(List.of());
        // No glowne broker in the books -> forBook("glowne") returns demo, but we assert
        // the gate only manages books it is asked to (demo/live) — Glowne never enters.
        assertThat(state.entriesFor("glowne")).isEmpty();
    }

    @Test
    void bothTicketsGoneWithoutTpFilledRemovesRowAndAllowsLaterBarEntry() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenReturn(List.of());
        when(demoClient.placeMarketOrder(any()))
                .thenReturn(new OrderAck("refA", null, "SUBMITTED"))
                .thenReturn(new OrderAck("refB", null, "SUBMITTED"));
        when(demoClient.confirm(anyString()))
                .thenReturn(new Confirmation("r", "d", "OPEN", "ACCEPTED", "DE40", Direction.BUY, 100.0, 2.0));

        Instant later = bar.plusSeconds(900);
        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, later)),
                view("demo", 0), false);

        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("closed"),
                eq("tickets gone (manual or SL)"));
        verify(monitor).record(eq("demo"), eq("GER40"), eq("closed"), eq("tickets gone (manual or SL)"));
        verify(demoClient, times(2)).placeMarketOrder(any());
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("placed"), eq(""));
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e).isNotNull();
        assertThat(e.barTime).isEqualTo(later);
    }

    @Test
    void onlyTpTicketGoneKeepsRowMarksTpFilledAndBlocksReentry() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenReturn(List.of(openPos("dealB", "DE40")));
        when(demoClient.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 100, 100, Instant.now()));

        Instant later = bar.plusSeconds(900);
        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, later)),
                view("demo", 0), false);

        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e).isNotNull();
        assertThat(e.tpFilled).isTrue();
        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("tp_fill"), eq(""));
        verify(monitor).record(eq("demo"), eq("GER40"), eq("tp_closed"), contains("1R"));
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("name already open"));
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("closed"), anyString());
    }

    @Test
    void onlyRunnerGoneKeepsRowWhileTpTicketOpen() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenReturn(List.of(openPos("dealA", "DE40")));

        Instant later = bar.plusSeconds(900);
        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, later)),
                view("demo", 0), false);

        assertThat(state.get("demo", "GER40")).isNotNull();
        assertThat(state.get("demo", "GER40").tpFilled).isFalse();
        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("tp_fill"), anyString());
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("closed"), anyString());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("name already open"));
    }

    @Test
    void singleTicketGoneWithoutTpFilledRemovesRow() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", null, false));
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        assertThat(state.get("demo", "GER40")).isNull();
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("closed"),
                eq("tickets gone (manual or SL)"));
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
        verify(demoClient, never()).placeMarketOrder(any());
    }

    @Test
    void sameBarFullStackStillSkippedAfterTicketsVanish() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        assertThat(state.get("demo", "GER40")).isNull();
        assertThat(state.alreadyPlaced("demo", "GER40", Direction.BUY, bar)).isTrue();
        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("duplicate bar already placed"));
    }

    @Test
    void neverFlattenNamesAreNotRemovedEvenWhenTicketsGone() {
        state.put(new SddExecutionState.Entry("demo", "SPOT", "SPOT", Direction.BUY, bar,
                100, 1, 97.5, "tq1", "tq2", true));
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        assertThat(state.get("demo", "SPOT")).isNotNull();
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
        verify(demoClient, never()).amendPosition(anyString(), anyDouble(), anyBoolean());
        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("closed"), anyString());
    }

    @Test
    void strayTicketOnSameEpicAndDirectionKeepsRow() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenReturn(List.of(openPos("stray", "DE40")));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        assertThat(state.get("demo", "GER40")).isNotNull();
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("closed"), anyString());
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
    }

    @Test
    void emptyPositionsGlitchThenTicketsReappearKeepsRow() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions())
                .thenReturn(List.of())
                .thenReturn(List.of(openPos("dealA", "DE40"), openPos("dealB", "DE40")));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        assertThat(state.get("demo", "GER40")).isNotNull();
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("closed"), anyString());
    }

    @Test
    void persistFailureAfterCapitalFillReportsPlacedAndKeepsRam() {
        SddExecutionRepository repo = mock(SddExecutionRepository.class);
        when(repo.findByBookAndSymbol(anyString(), anyString())).thenReturn(List.of());
        when(repo.save(any(SddExecutionEntity.class)))
                .thenThrow(new InvalidDataAccessApiUsageException("Executing an update/delete query"));
        state = new SddExecutionState(repo);
        gate = new ExecutionGate(props, books, risk, state, webhooks, telegram, monitor, com.adam.server.scan.Mailer.disabled(), flags);

        when(demoClient.openPositions()).thenReturn(List.of());
        when(demoClient.placeMarketOrder(any()))
                .thenReturn(new OrderAck("refA", null, "SUBMITTED"))
                .thenReturn(new OrderAck("refB", null, "SUBMITTED"));
        when(demoClient.confirm("refA"))
                .thenReturn(new Confirmation("refA", "dealA", "OPEN", "ACCEPTED", "DE40", Direction.BUY, 100.0, 2.0));
        when(demoClient.confirm("refB"))
                .thenReturn(new Confirmation("refB", "dealB", "OPEN", "ACCEPTED", "DE40", Direction.BUY, 100.0, 2.0));

        gate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("placed"), eq(""));
        verify(webhooks, never()).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("InvalidDataAccessApiUsageException"));
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e).isNotNull();
        assertThat(e.ticketA).isEqualTo("dealA");
        assertThat(e.ticketB).isEqualTo("dealB");
        assertThat(state.alreadyPlaced("demo", "GER40", Direction.BUY, bar)).isTrue();
        verify(telegram).onFill(eq("demo"), eq("GER40"), eq("BUY"), anyDouble(), eq(100.0), eq(97.5));
    }

    @Test
    void positionsFetchFailureDoesNotDropRow() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenThrow(new RuntimeException("capital down"));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        assertThat(state.get("demo", "GER40")).isNotNull();
        verify(webhooks, never()).publishExecution(anyString(), anyString(), anyString(), eq("closed"), anyString());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void seedEntry(String symbol, String epic) {
        state.put(new SddExecutionState.Entry("demo", symbol, epic, Direction.BUY, bar,
                100, 1, 97.5, "d-" + symbol, "d2-" + symbol, true));
    }

    private static Position openPos(String dealId, String epic) {
        return new Position(dealId, "r", epic, Direction.BUY, 2.0, 100, 97.5, null, 0, "PLN", Instant.now());
    }

    private static SddScan fullStack(String symbol, String epic, Direction dir, double entry,
                                     double atr, Instant barTime) {
        double stop = dir == Direction.BUY ? entry - 2.5 * atr : entry + 2.5 * atr;
        return new SddScan(barTime, symbol, epic, dir,
                new SddScan.Setup(true, true, true, true),
                stop, atr, atr, entry, true, "full stack " + dir,
                List.of(), true, true, true, "H4 aligned", true);
    }

    private static AccountView view(String book, double dayPnl) {
        return new AccountView(book, "capital", "Account", 1000.0, 1000.0, dayPnl, "PLN", true, null);
    }
}
