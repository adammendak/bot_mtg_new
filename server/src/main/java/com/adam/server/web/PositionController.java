package com.adam.server.web;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.model.Position;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PositionController {

    private final BrokerClient broker;

    public PositionController(BrokerClient broker) {
        this.broker = broker;
    }

    @GetMapping(value = "/api/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Position> positions() {
        try {
            if (!broker.isSessionOpen()) {
                broker.login();
            }
            return broker.openPositions();
        } catch (BrokerException e) {
            return List.of();
        }
    }
}
