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
import com.adam.server.persistence.SddExecutionRepository;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.sdd.SddScan;
import com.adam.server.web.dto.AccountView;
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
 * Execution rules behind EXECUTION_ENABLED: two-deal placement, never DELETE+size,
 * skip when the SDD name is open, 4-name cap, day halt, idempotent retry, live
 * account gate, and never touching the stocks book (TQQQ/CRCL/SPOT/SHOP).
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

    AppProperties props;
    RiskPolicy risk;
    SddExecutionState state;
    BrokerBooks books;
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
                new UnavailableBrokerClient("glowne", "test"));
        gate = new ExecutionGate(props, books, risk, state, webhooks, telegram);

        when(demoClient.book()).thenReturn("demo");
        when(demoClient.id()).thenReturn("capital");
        when(demoClient.configured()).thenReturn(true);
        when(demoClient.isSessionOpen()).thenReturn(false);
        when(demoClient.accounts())
                .thenReturn(List.of(new Account("1", "Account", "PLN", 1000, 1000, 0, true)));
    }

    @Test
    void fullStackPlacesTwoSeparateDeals() {
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
        for (OrderRequest r : captor.getAllValues()) {
            assertThat(r.direction()).isEqualTo(Direction.BUY);
            assertThat(r.size()).isEqualTo(2.0);      // 4.0 risk-sized / 2
            assertThat(r.stopLevel()).isEqualTo(97.5); // entry - 2.5 * atr
            assertThat(r.profitLevel()).isNull();      // no TP at entry
        }
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e).isNotNull();
        assertThat(e.twoTickets).isTrue();
        assertThat(e.ticketA).isEqualTo("dealA");
        assertThat(e.ticketB).isEqualTo("dealB");
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("placed"), eq(""));
    }

    @Test
    void at2RClosesOneWholeTicketWithoutSizeAndRunnerToBe() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        Position pos = new Position("dealA", "refA", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 5, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(pos));
        when(demoClient.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 103, 103, Instant.now()));

        gate.executeBook("demo", List.of(), view("demo", 0), false);

        // ONE whole ticket closed with NO size (0) — never DELETE + size=
        verify(demoClient).closePosition("dealA", 0);
        verify(demoClient).amendPosition("dealB", 100.0, false); // runner to BE
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e.closedAt2R).isTrue();
        assertThat(e.runnerAtBe).isTrue();
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
        when(demoClient.openPositions()).thenReturn(List.of());

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
        when(demoClient.openPositions()).thenReturn(List.of());

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
                new UnavailableBrokerClient("glowne", "test"));
        ExecutionGate liveGate = new ExecutionGate(props, liveBooks, risk, state, webhooks, telegram);

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
        assertThat(captor.getValue().size()).isEqualTo(4.0);
        SddExecutionState.Entry e = state.get("demo", "GER40");
        assertThat(e.twoTickets).isFalse();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void seedEntry(String symbol, String epic) {
        state.put(new SddExecutionState.Entry("demo", symbol, epic, Direction.BUY, bar,
                100, 1, 97.5, "d-" + symbol, "d2-" + symbol, true));
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
