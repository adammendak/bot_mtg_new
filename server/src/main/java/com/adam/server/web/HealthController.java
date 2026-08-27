package com.adam.server.web;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.config.AppProperties;
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

    public HealthController(Clock clock, BrokerBooks books, AppProperties properties) {
        this.clock = clock;
        this.books = books;
        this.properties = properties;
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
        return body;
    }
}
