package com.adam.server.web;

import com.adam.server.broker.BrokerClient;
import com.adam.server.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BrokerInfoController {

    private final BrokerClient broker;
    private final AppProperties properties;

    public BrokerInfoController(BrokerClient broker, AppProperties properties) {
        this.broker = broker;
        this.properties = properties;
    }

    @GetMapping(value = "/api/broker", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> broker() {
        return Map.of(
                "id", broker.id(),
                "name", broker.displayName(),
                "sessionOpen", broker.isSessionOpen(),
                "executionEnabled", properties.isExecutionEnabled()
        );
    }
}
