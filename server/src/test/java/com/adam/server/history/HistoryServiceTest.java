package com.adam.server.history;

import com.adam.server.persistence.BrokerSnapshotEntity;
import com.adam.server.persistence.BrokerSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@ActiveProfiles("test")
class HistoryServiceTest {

    @Autowired
    HistoryService history;

    @Autowired
    BrokerSnapshotRepository brokers;

    @BeforeEach
    void clean() {
        brokers.deleteAll();
    }

    @Test
    void aggregatesDailySnapshotsAndComputesPctChange() {
        brokers.save(snapshot("demo", "EUR", 1000.0, 0.0, Instant.parse("2026-08-01T10:00:00Z")));
        brokers.save(snapshot("demo", "EUR", 1010.0, 10.0, Instant.parse("2026-08-01T18:00:00Z")));
        brokers.save(snapshot("demo", "EUR", 990.0, -20.0, Instant.parse("2026-08-02T10:05:00Z")));
        brokers.save(snapshot("live", "EUR", 5000.0, 0.0, Instant.parse("2026-08-01T10:00:00Z")));

        HistoryResponse demo = history.daily("demo");

        assertThat(demo.connected()).isTrue();
        assertThat(demo.currency()).isEqualTo("EUR");
        assertThat(demo.points()).hasSize(2);
        DailyEquityPoint aug1 = demo.points().get(0);
        DailyEquityPoint aug2 = demo.points().get(1);
        assertThat(aug1.equity()).isEqualTo(1010.0);
        assertThat(aug1.pctChange()).isEqualTo(0.0);
        assertThat(aug1.dayPnl()).isEqualTo(10.0);
        assertThat(aug2.equity()).isEqualTo(990.0);
        assertThat(aug2.dayPnl()).isEqualTo(-20.0);
        assertThat(aug2.pctChange()).isCloseTo(-1.9801980198019802, within(0.0001));

        assertThat(history.daily("live").points()).hasSize(1);
        assertThat(history.daily("missing").points()).isEmpty();
    }

    @Test
    void computesDrawdownMetrics() {
        brokers.save(snapshot("demo", "EUR", 1000.0, 0.0, Instant.parse("2026-08-01T10:00:00Z")));
        brokers.save(snapshot("demo", "EUR", 800.0, -200.0, Instant.parse("2026-08-02T10:00:00Z")));
        brokers.save(snapshot("demo", "EUR", 900.0, 100.0, Instant.parse("2026-08-03T10:00:00Z")));

        HistoryResponse demo = history.daily("demo");

        // Peak 1000 -> trough 800 = 20% max DD; recovered on day 3 (900 >= 1000? no) -> still not recovered.
        assertThat(demo.maxDrawdownPct()).isCloseTo(20.0, within(0.001));
        assertThat(demo.currentDrawdownPct()).isCloseTo(10.0, within(0.001));
    }

    private static BrokerSnapshotEntity snapshot(
            String book,
            String currency,
            double equity,
            double dayPnl,
            Instant capturedAt
    ) {
        BrokerSnapshotEntity row = new BrokerSnapshotEntity();
        row.setBook(book);
        row.setBroker("paper");
        row.setAccountName("paper");
        row.setEquity(equity);
        row.setAvailable(equity);
        row.setDayPnl(dayPnl);
        row.setCurrency(currency);
        row.setConnected(true);
        row.setCapturedAt(capturedAt);
        return row;
    }
}
