package com.adam.server.web;

import com.adam.server.broker.BrokerClient;
import com.adam.server.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    private final Clock clock;
    private final BrokerClient broker;
    private final AppProperties properties;

    public HealthController(Clock clock, BrokerClient broker, AppProperties properties) {
        this.clock = clock;
        this.broker = broker;
        this.properties = properties;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "time", Instant.now(clock).toString(),
                "broker", broker.id(),
                "executionEnabled", properties.isExecutionEnabled()
        );
    }
}
