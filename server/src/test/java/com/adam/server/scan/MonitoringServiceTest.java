package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.web.dto.PositionMonitorView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringServiceTest {

    @Mock
    BrokerClient demo;

    private MonitoringService monitor;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setMonitorSleepMinutes("240");
        BrokerBooks books = new BrokerBooks(demo,
                new UnavailableBrokerClient("live", "test"),
                new UnavailableBrokerClient("glowne", "test"),
                new UnavailableBrokerClient("swing", "test"));
        monitor = new MonitoringService(books, props, clock);
        when(demo.book()).thenReturn("demo");
        when(demo.id()).thenReturn("capital");
        when(demo.configured()).thenReturn(true);
    }

    @Test
    void monitorFlagsStopDriftAndSleeping() {
        Position noStop = new Position("d1", "r1", "US100", Direction.BUY, 1.0, 20000, null, null, 5, "PLN",
                clock.instant().minusSeconds(20 * 3600));
        Position drifted = new Position("d2", "r2", "GER40", Direction.BUY, 1.0, 18000, 18100.0, null, 5, "PLN",
                clock.instant().minusSeconds(3600));
        when(demo.openPositions()).thenReturn(List.of(noStop, drifted));

        List<PositionMonitorView> rows = monitor.monitor("demo");

        assertThat(rows).hasSize(2);
        PositionMonitorView first = rows.get(0);
        assertThat(first.stopDrifted()).isTrue();      // no stop
        assertThat(first.sleeping()).isTrue();          // 20h open
        assertThat(first.openMinutes()).isEqualTo(20 * 60);
        assertThat(rows.get(1).stopDrifted()).isTrue(); // buy stop above entry
    }

    @Test
    void actionsAreRejectedOnLive() {
        String out = monitor.close("live", "d1");
        assertThat(out).contains("demo only");
        verify(demo, never()).closePosition(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void closeRecordsAudit() {
        monitor.close("demo", "d1");
        verify(demo).closePosition("d1", 0);
        assertThat(monitor.audit("demo")).isNotEmpty();
        assertThat(monitor.audit("demo").getFirst().action()).isEqualTo("closed");
    }
}
