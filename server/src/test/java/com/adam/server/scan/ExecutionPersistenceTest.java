package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.persistence.SddExecutionEntity;
import com.adam.server.persistence.SddExecutionRepository;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.sdd.SddScan;
import com.adam.server.web.dto.AccountView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Persistence of SDD execution state in Postgres: a dyno restart must keep the
 * two-ticket deal ids, the tp/trail flags and idempotency (same bar after reload does
 * not place twice; a name open from the DB blocks a new bar until BOTH tickets are gone).
 * The hydrator reconciles tracked entries against open positions and never adopts
 * leftover SDD positions that have no row in sdd_execution_entries.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionPersistenceTest {

    @Mock
    BrokerClient demoClient;
    @Mock
    SignalWebhookPublisher webhooks;
    @Mock
    TelegramNotifier telegram;
    @Mock
    MonitoringService monitor;
    @Mock
    SddExecutionRepository repository;

    AppProperties props;
    RiskPolicy risk;
    BrokerBooks books;
    ExecutionGate gate;
    SddExecutionState state;

    /** In-memory "DB": what loadFromDb returns after a simulated restart. */
    final List<SddExecutionEntity> db = new ArrayList<>();
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

        // Repository mock backed by the in-memory list, simulating Postgres.
        when(repository.findAll()).thenAnswer(i -> new ArrayList<>(db));
        when(repository.findByBookAndSymbol(anyString(), anyString())).thenAnswer(i -> {
            String book = i.getArgument(0);
            String symbol = i.getArgument(1);
            return db.stream()
                    .filter(r -> book.equals(r.getBook()) && symbol.equals(r.getSymbol()))
                    .toList();
        });
        when(repository.save(any(SddExecutionEntity.class))).thenAnswer(i -> {
            SddExecutionEntity e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(db.stream().mapToLong(r -> r.getId() == null ? 0 : r.getId()).max().orElse(0) + 1);
                db.add(e);
            } else if (!db.contains(e)) {
                db.removeIf(r -> e.getId().equals(r.getId()));
                db.add(e);
            }
            return e;
        });
        org.mockito.Mockito.doAnswer(i -> {
            String book = i.getArgument(0);
            String symbol = i.getArgument(1);
            db.removeIf(r -> book.equals(r.getBook()) && symbol.equals(r.getSymbol()));
            return 0L;
        }).when(repository).deleteByBookAndSymbol(anyString(), anyString());
        org.mockito.Mockito.doAnswer(i -> {
            SddExecutionEntity e = i.getArgument(0);
            db.remove(e);
            return null;
        }).when(repository).delete(any(SddExecutionEntity.class));
        org.mockito.Mockito.doAnswer(i -> {
            db.clear();
            return null;
        }).when(repository).deleteAll();

        state = new SddExecutionState(repository);
        books = new BrokerBooks(demoClient, new UnavailableBrokerClient("live", "test"),
                new UnavailableBrokerClient("glowne", "test"),
                new UnavailableBrokerClient("swing", "test"),
                new UnavailableBrokerClient("hts", "test"));
        gate = new ExecutionGate(props, books, risk, state, webhooks, telegram, monitor, com.adam.server.scan.Mailer.disabled());

        when(demoClient.book()).thenReturn("demo");
        when(demoClient.id()).thenReturn("capital");
        when(demoClient.configured()).thenReturn(true);
        when(demoClient.isSessionOpen()).thenReturn(false);
        when(demoClient.accounts())
                .thenReturn(List.of(new Account("1", "Account", "PLN", 1000, 1000, 0, true)));
    }

    @Test
    void sameBarAfterReloadDoesNotPlaceTwice() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        SddExecutionState fresh = new SddExecutionState(repository); // restart
        fresh.loadFromDb();
        ExecutionGate freshGate = new ExecutionGate(props, books, risk, fresh, webhooks, telegram, monitor, com.adam.server.scan.Mailer.disabled());
        when(demoClient.openPositions()).thenReturn(List.of());

        freshGate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, bar)),
                view("demo", 0), false);

        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("duplicate bar already placed"));
    }

    @Test
    void nameOpenFromDbBlocksNewBarUntilBothTicketsGone() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        SddExecutionState fresh = new SddExecutionState(repository); // restart
        fresh.loadFromDb();
        ExecutionGate freshGate = new ExecutionGate(props, books, risk, fresh, webhooks, telegram, monitor, com.adam.server.scan.Mailer.disabled());
        Position tp = new Position("dealA", "refA", "DE40", Direction.BUY, 2.0, 100, 97.5, 101.0, 0, "PLN", Instant.now());
        Position runner = new Position("dealB", "refB", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 0, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(tp, runner));

        // Same symbol, NEW bar: must be skipped because the name is still open on the broker.
        Instant newBar = bar.plusSeconds(900);
        freshGate.executeBook("demo", List.of(fullStack("GER40", "DE40", Direction.BUY, 100, 1, newBar)),
                view("demo", 0), false);

        verify(demoClient, never()).placeMarketOrder(any());
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("skip"),
                contains("name already open"));
    }

    @Test
    void afterRestartTpTicketGoneMarksTpFilledAndRunnerTrails() {
        // Persist a two-ticket entry; the TP ticket (dealA) is gone on the broker,
        // the runner (dealB) is still open.
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        SddExecutionState fresh = new SddExecutionState(repository); // restart
        fresh.loadFromDb();
        ExecutionGate freshGate = new ExecutionGate(props, books, risk, fresh, webhooks, telegram, monitor, com.adam.server.scan.Mailer.disabled());

        Position runner = new Position("dealB", "refB", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 0, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(runner));
        when(demoClient.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 100, 100, Instant.now()));

        freshGate.executeBook("demo", List.of(), view("demo", 0), false);

        assertThat(fresh.get("demo", "GER40").tpFilled).isTrue();
        // Runner stop never amended to entry/BE (entry=100).
        verify(demoClient, never()).amendPosition("dealB", 100.0, false);
    }

    @Test
    void hydratorIgnoresLeftoverBrokerPositionsWithoutDbRow() {
        // A Computron-opened SDD position on the broker with NO row in sdd_execution_entries.
        Position leftover = new Position("zzz", "refZ", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 5, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(leftover));

        gate.reloadAndReconcile();

        // Nothing is adopted, no orders sent, no rows created.
        assertThat(state.entriesFor("demo")).isEmpty();
        verify(demoClient, never()).placeMarketOrder(any());
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
        verify(demoClient, never()).amendPosition(anyString(), anyDouble(), anyBoolean());
        verify(repository, never()).save(any(SddExecutionEntity.class));
    }

    @Test
    void hydratorRemovesEntryWhenBothTicketsGone() {
        // TP ticket filled first (runner was trailing), then both vanish.
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true, true, true));
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.reloadAndReconcile();

        assertThat(state.get("demo", "GER40")).isNull();
    }

    @Test
    void hydratorRemovesEntryWhenBothTicketsGoneEvenIfTpFilledFalse() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.reloadAndReconcile();

        assertThat(state.get("demo", "GER40")).isNull();
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("closed"),
                eq("tickets gone (manual or SL)"));
        verify(monitor).record(eq("demo"), eq("GER40"), eq("closed"), eq("tickets gone (manual or SL)"));
        verify(demoClient, never()).closePosition(anyString(), anyDouble());
    }

    @Test
    void hydratorRemovesSingleTicketWhenGoneWithoutTpFilled() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", null, false));
        when(demoClient.openPositions()).thenReturn(List.of());

        gate.reloadAndReconcile();

        assertThat(state.get("demo", "GER40")).isNull();
        verify(webhooks).publishExecution(eq("demo"), eq("GER40"), eq("BUY"), eq("closed"),
                eq("tickets gone (manual or SL)"));
    }

    @Test
    void putOfExistingBookSymbolReplacesRowWithoutDuplicate() {
        state.put(new SddExecutionState.Entry("demo", "XAU", "GOLD", Direction.SELL, bar,
                3400, 5, 3412.5, "oldA", "oldB", true));
        state.put(new SddExecutionState.Entry("demo", "XAU", "GOLD", Direction.SELL, bar.plusSeconds(900),
                3390, 5, 3402.5, "newA", "newB", true));

        assertThat(db).hasSize(1);
        assertThat(db.get(0).getTicketA()).isEqualTo("newA");
        assertThat(db.get(0).getTicketB()).isEqualTo("newB");
        assertThat(state.get("demo", "XAU").ticketA).isEqualTo("newA");
    }

    @Test
    void putWithNullTicketBPersistsSingleTicket() {
        state.put(new SddExecutionState.Entry("live", "BTC", "BTCUSD", Direction.SELL, bar,
                100, 1, 102.5, "dealA", null, false));

        assertThat(db).hasSize(1);
        assertThat(db.get(0).getTicketB()).isNull();
        assertThat(db.get(0).isTwoTickets()).isFalse();
    }

    @Test
    void removeThenPutSameSymbolWritesNewRow() {
        state.put(new SddExecutionState.Entry("demo", "EURUSD", "EURUSD", Direction.SELL, bar,
                1.1, 0.001, 1.1025, "oldA", "oldB", true));
        state.remove("demo", "EURUSD");
        assertThat(db).isEmpty();

        Instant later = bar.plusSeconds(1800);
        state.put(new SddExecutionState.Entry("demo", "EURUSD", "EURUSD", Direction.SELL, later,
                1.09, 0.001, 1.0925, "newA", "newB", true));

        assertThat(db).hasSize(1);
        assertThat(db.get(0).getTicketA()).isEqualTo("newA");
        assertThat(state.alreadyPlaced("demo", "EURUSD", Direction.SELL, bar)).isTrue();
        assertThat(state.get("demo", "EURUSD").barTime).isEqualTo(later);
    }

    @Test
    void hydratorKeepsRowWhenOnlyRunnerOpen() {
        state.put(new SddExecutionState.Entry("demo", "GER40", "DE40", Direction.BUY, bar,
                100, 1, 97.5, "dealA", "dealB", true));
        Position runner = new Position("dealB", "refB", "DE40", Direction.BUY, 2.0, 100, 97.5, null, 0, "PLN", Instant.now());
        when(demoClient.openPositions()).thenReturn(List.of(runner));

        gate.reloadAndReconcile();

        assertThat(state.get("demo", "GER40").tpFilled).isTrue();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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
