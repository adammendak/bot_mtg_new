package com.adam.server.web;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.config.AppProperties;
import com.adam.server.scan.SignalWebhookPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final Clock clock;
    private final BrokerBooks books;
    private final AppProperties properties;
    private final SignalWebhookPublisher webhooks;

    public HealthController(
            Clock clock,
            BrokerBooks books,
            AppProperties properties,
            SignalWebhookPublisher webhooks
    ) {
        this.clock = clock;
        this.books = books;
        this.properties = properties;
        this.webhooks = webhooks;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", Instant.now(clock).toString());
        body.put("broker", books.marketData().id());
        body.put("executionEnabled", properties.isExecutionEnabled());
        body.put("demoConfigured", books.demo().configured());
        body.put("liveConfigured", books.live().configured());
        body.put("swingConfigured", books.swing().configured());
        body.put("htsConfigured", books.hts().configured());
        body.put("webhookConfigured", properties.webhookConfigured());
        body.put("lastWebhook", webhooks.lastWebhook());
        Instant at = webhooks.lastWebhookAt();
        body.put("lastWebhookAt", at == null ? null : at.toString());
        return body;
    }
}
