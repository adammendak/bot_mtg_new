package com.adam.server.scan;

import com.adam.server.broker.Direction;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.SddScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class SignalWebhookPublisherTest {

    static final String TEST_SECRET = "crsr_UNITTEST_SENDER_KEY";

    @Test
    void payloadMatchesAgentContract() {
        Map<String, Object> body = SignalWebhookPublisher.payload(fullStackScan());
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

    @Test
    void http500DoesNotThrowAndRecordsStatusWithoutLoggingSecret(CapturedOutput output) throws Exception {
        try (RecordingWebhookServer server = new RecordingWebhookServer()) {
            server.status = 500;
            AppProperties props = new AppProperties();
            props.setWebhookUrls(server.urlWithQuery("token=querysecret"));
            props.setWebhookSecret(TEST_SECRET);
            SignalWebhookPublisher publisher = new SignalWebhookPublisher(props, RestClient.builder());

            assertThatCode(() -> publisher.publish(fullStackScan())).doesNotThrowAnyException();

            assertThat(server.requests()).hasSize(1);
            RecordingWebhookServer.Recorded recorded = server.requests().getFirst();
            assertThat(recorded.authorization()).isEqualTo("Bearer " + TEST_SECRET);
            assertThat(recorded.webhookSecret()).isEqualTo(TEST_SECRET);
            assertThat(publisher.lastWebhookError()).isEqualTo("HTTP 500");
            assertThat(publisher.lastWebhook()).isEqualTo("HTTP 500");
            assertThat(publisher.lastWebhookAt()).isNotNull();

            String logs = output.getOut() + output.getErr();
            assertThat(logs).contains("HTTP 500");
            assertThat(logs).doesNotContain(TEST_SECRET);
            assertThat(logs).doesNotContain("querysecret");
            assertThat(logs).doesNotContain("token=querysecret");
        }
    }

    @Test
    void bearerPrefixOnSecretIsNotDoubled() throws Exception {
        try (RecordingWebhookServer server = new RecordingWebhookServer()) {
            AppProperties props = new AppProperties();
            props.setWebhookUrls(server.url());
            props.setWebhookSecret("Bearer " + TEST_SECRET);
            SignalWebhookPublisher publisher = new SignalWebhookPublisher(props, RestClient.builder());
            publisher.publish(fullStackScan());
            assertThat(server.requests().getFirst().authorization()).isEqualTo("Bearer " + TEST_SECRET);
            assertThat(server.requests().getFirst().webhookSecret()).isEqualTo(TEST_SECRET);
        }
    }

    static SddScan fullStackScan() {
        return new SddScan(
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
    }
}
