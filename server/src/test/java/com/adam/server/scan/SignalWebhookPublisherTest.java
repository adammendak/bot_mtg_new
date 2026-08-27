package com.adam.server.scan;

import com.adam.server.broker.Direction;
import com.adam.server.sdd.SddScan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignalWebhookPublisherTest {

    @Test
    void payloadMatchesAgentContract() {
        SddScan scan = new SddScan(
                Instant.parse("2026-08-26T10:16:00Z"),
                "GER40",
                "DE40",
                Direction.BUY,
                new SddScan.Setup(true, true, true, true),
                1.0,
                2.0,
                2.0,
                10.0,
                true,
                "full stack BUY",
                List.of(),
                true,
                true,
                true,
                "H4 aligned",
                true
        );
        Map<String, Object> body = SignalWebhookPublisher.payload(scan);
        assertThat(body.get("symbol")).isEqualTo("GER40");
        assertThat(body.get("epic")).isEqualTo("DE40");
        assertThat(body.get("direction")).isEqualTo("BUY");
        assertThat(body.get("actionable")).isEqualTo(true);
        assertThat(body.get("newBar")).isEqualTo(true);
        assertThat(body.get("flip")).isEqualTo(true);
        assertThat(body.get("fullStack")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> setup = (Map<String, Object>) body.get("setup");
        assertThat(setup).containsEntry("ha", true).containsEntry("rma", true).containsEntry("h1", true).containsEntry("pp", true);
    }
}
