package com.adam.server.history;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.config.AppProperties;
import com.adam.server.persistence.BrokerSnapshotRepository;
import com.adam.server.sdd.RiskPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EquityHistoryServiceSyncAllTest {

    private final EquityHistoryService service = new EquityHistoryService(
            new BrokerBooks(
                    new UnavailableBrokerClient("demo", "no demo"),
                    new UnavailableBrokerClient("live", "no live"),
                    new UnavailableBrokerClient("glowne", "no glowne"),
                    new UnavailableBrokerClient("swing", "no swing"),
                    new UnavailableBrokerClient("hts", "no hts"),
                    new UnavailableBrokerClient("okx", "no okx")
            ),
            org.mockito.Mockito.mock(BrokerSnapshotRepository.class),
            new RiskPolicy(new AppProperties()),
            new ObjectMapper()
    );

    @Test
    void syncAllReturnsOneResultPerBookWithoutThrowing() {
        List<EquityHistoryService.SyncResult> results = service.syncAll(true);

        assertThat(results).hasSize(6);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo("error"));
        assertThat(results)
                .extracting(EquityHistoryService.SyncResult::message)
                .containsExactlyInAnyOrder(
                        "broker not configured for book demo",
                        "broker not configured for book live",
                        "broker not configured for book glowne",
                        "broker not configured for book swing",
                        "broker not configured for book hts",
                        "broker not configured for book okx"
                );
    }
}
