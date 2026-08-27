package com.adam.server.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Honest v1/legacy probe. This repo never contained Bybit or Binance clients;
 * do not invent adapters. Capital.com is the current default (v2) via {@code BrokerClient}.
 */
@RestController
public class LegacyBrokerController {

    @GetMapping(value = {"/api/v1", "/api/legacy"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> legacy() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("present", false);
        body.put("defaultBroker", "capital");
        body.put("version", "v2");
        body.put("brokersSearched", List.of("bybit", "binance", "ccxt"));
        body.put("message",
                "No Bybit or Binance client exists in adammendak/bot_mtg_new "
                        + "(all 12 commits, branches main and cursor/capital-sdd-m15-337f, no tags). "
                        + "Those names were not deleted here; they were never in this repository. "
                        + "Capital.com is the default BrokerClient. Plug a real adapter into "
                        + "com.adam.server.broker.BrokerClient when you have one — do not use a stub.");
        return body;
    }
}
