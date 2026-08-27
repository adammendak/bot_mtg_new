package com.adam.server.web;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class BrokerInfoController {

    private final BrokerBooks books;
    private final AppProperties properties;

    public BrokerInfoController(BrokerBooks books, AppProperties properties) {
        this.books = books;
        this.properties = properties;
    }

    @GetMapping(value = "/api/broker", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> broker() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionEnabled", properties.isExecutionEnabled());
        body.put("books", List.of(book(books.demo()), book(books.live())));
        return body;
    }

    private static Map<String, Object> book(com.adam.server.broker.BrokerClient client) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", client.book());
        m.put("broker", client.id());
        m.put("name", client.displayName());
        m.put("sessionOpen", client.isSessionOpen());
        m.put("configured", client.configured());
        return m;
    }
}
