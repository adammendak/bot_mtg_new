package com.adam.server.history;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {

    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    @GetMapping(value = "/api/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public HistoryResponse history(
            @RequestParam(name = "book", required = false, defaultValue = "demo") String book
    ) {
        return history.daily(book);
    }
}
