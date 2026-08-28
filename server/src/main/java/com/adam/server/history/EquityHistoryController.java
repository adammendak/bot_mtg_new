package com.adam.server.history;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for the equity history sync (reconstructs daily equity from the
 * broker's transaction history into {@code broker_snapshots}).
 */
@RestController
@RequestMapping("/api/history")
public class EquityHistoryController {

    private final EquityHistoryService service;

    public EquityHistoryController(EquityHistoryService service) {
        this.service = service;
    }

    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public EquityHistoryService.SyncResult sync(
            @RequestParam(name = "book", defaultValue = "live") String book,
            @RequestParam(name = "replace", defaultValue = "false") boolean replace
    ) {
        return service.sync(book, replace);
    }
}
