package com.adam.server.scan;

import com.adam.server.broker.Direction;
import com.adam.server.persistence.SddExecutionEntity;
import com.adam.server.persistence.SddExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real JPA write-through for {@code sdd_execution_entries}. Production skips
 * ({@code entry failed: InvalidDataAccessApiUsageException}) came from
 * {@code deleteByBookAndSymbol} + {@code persist} of a new entity outside a
 * transaction after a Capital fill — including a re-put of the same book+symbol
 * once PR #29 dropped a stuck/partial row.
 *
 * <p>Default {@code @DataJpaTest} transactions are disabled so this matches the
 * scan path (no TX around {@code SddExecutionState.put}). Liquibase is off;
 * Hibernate creates the table from the entity.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.liquibase.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
class SddExecutionStateJpaTest {

    @Autowired
    SddExecutionRepository repository;

    SddExecutionState state;

    final Instant bar = Instant.parse("2026-08-28T20:01:00Z");
    final Instant later = Instant.parse("2026-08-28T20:31:00Z");

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        state = new SddExecutionState(repository);
    }

    @Test
    void derivedDeleteFromNonTransactionalCallerDoesNotThrow() {
        // Old put/remove called deleteByBookAndSymbol with no TX on the caller.
        // A derived DELETE is not a SimpleJpaRepository CRUD method, so that
        // threw InvalidDataAccessApiUsageException after every Capital fill.
        // The repository method is now @Transactional.
        state.put(entry("demo", "XAU", "GOLD", bar, "dealA", "dealB", true));
        assertThatCode(() -> repository.deleteByBookAndSymbol("demo", "XAU"))
                .doesNotThrowAnyException();
        assertThat(repository.findByBookAndSymbol("demo", "XAU")).isEmpty();
    }

    @Test
    void putOfExistingBookSymbolMergesInsteadOfPersist() {
        state.put(entry("demo", "XAU", "GOLD", bar, "dealA1", "dealB1", true));
        assertThat(repository.findByBookAndSymbol("demo", "XAU")).hasSize(1);

        assertThatCode(() -> state.put(entry("demo", "XAU", "GOLD", later, "dealA2", "dealB2", true)))
                .doesNotThrowAnyException();

        List<SddExecutionEntity> rows = repository.findByBookAndSymbol("demo", "XAU");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTicketA()).isEqualTo("dealA2");
        assertThat(rows.get(0).getTicketB()).isEqualTo("dealB2");
        assertThat(rows.get(0).getBarTime()).isEqualTo(later);
        SddExecutionState.Entry ram = state.get("demo", "XAU");
        assertThat(ram.ticketA).isEqualTo("dealA2");
        assertThat(ram.ticketB).isEqualTo("dealB2");
    }

    @Test
    void putWithNullTicketBPersistsSingleTicketRow() {
        assertThatCode(() -> state.put(entry("live", "EURUSD", "EURUSD", bar, "dealA", null, false)))
                .doesNotThrowAnyException();

        SddExecutionEntity row = repository.findByBookAndSymbol("live", "EURUSD").get(0);
        assertThat(row.getTicketA()).isEqualTo("dealA");
        assertThat(row.getTicketB()).isNull();
        assertThat(row.isTwoTickets()).isFalse();
        assertThat(state.get("live", "EURUSD").ticketB).isNull();
    }

    @Test
    void removeThenPutSameSymbolWritesANewRow() {
        state.put(entry("demo", "BTC", "BTCUSD", bar, "oldA", "oldB", true));
        state.remove("demo", "BTC");
        assertThat(repository.findByBookAndSymbol("demo", "BTC")).isEmpty();
        assertThat(state.get("demo", "BTC")).isNull();

        state.put(entry("demo", "BTC", "BTCUSD", later, "newA", "newB", true));

        List<SddExecutionEntity> rows = repository.findByBookAndSymbol("demo", "BTC");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTicketA()).isEqualTo("newA");
        assertThat(rows.get(0).getTicketB()).isEqualTo("newB");
        assertThat(rows.get(0).getBarTime()).isEqualTo(later);
        assertThat(state.alreadyPlaced("demo", "BTC", Direction.SELL, bar)).isTrue();
        assertThat(state.alreadyPlaced("demo", "BTC", Direction.SELL, later)).isTrue();
    }

    @Test
    void putThenLoadFromDbSurvivesRestart() {
        state.put(entry("live", "XAU", "GOLD", bar, "dealA", "dealB", true));
        SddExecutionState fresh = new SddExecutionState(repository);
        fresh.loadFromDb();

        SddExecutionState.Entry e = fresh.get("live", "XAU");
        assertThat(e).isNotNull();
        assertThat(e.ticketA).isEqualTo("dealA");
        assertThat(e.ticketB).isEqualTo("dealB");
        assertThat(e.twoTickets).isTrue();
        assertThat(fresh.alreadyPlaced("live", "XAU", Direction.SELL, bar)).isTrue();
    }

    @Test
    void updateMergesTpFilledWithoutInsertingASecondRow() {
        state.put(entry("demo", "GER40", "DE40", bar, "dealA", "dealB", true));
        SddExecutionState.Entry e = state.get("demo", "GER40");
        e.tpFilled = true;
        e.trailing = true;
        state.update(e);

        List<SddExecutionEntity> rows = repository.findByBookAndSymbol("demo", "GER40");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isTpFilled()).isTrue();
        assertThat(rows.get(0).isTrailing()).isTrue();
        assertThat(rows.get(0).getTicketA()).isEqualTo("dealA");
    }

    private static SddExecutionState.Entry entry(String book, String symbol, String epic,
                                                 Instant barTime, String ticketA, String ticketB,
                                                 boolean twoTickets) {
        return new SddExecutionState.Entry(book, symbol, epic, Direction.SELL, barTime,
                3400.0, 5.0, 3412.5, ticketA, ticketB, twoTickets);
    }
}
