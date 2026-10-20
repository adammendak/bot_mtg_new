package com.adam.server.web;

import com.adam.server.persistence.TvSignalEntity;
import com.adam.server.persistence.TvSignalRepository;
import com.adam.server.tv.TvSignalService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/tv/signal} — public (no bearer token; TradingView cannot send
 * one), authenticated by the shared secret in the JSON body. TradingView posts
 * {@code text/plain} for non-JSON-looking messages and {@code application/json}
 * otherwise, so the body is read as a raw string. {@code GET /api/tv/signals}
 * (bearer token) lists the latest events.
 */
@RestController
public class TvWebhookController {

    private final TvSignalService service;
    private final TvSignalRepository repo;

    public TvWebhookController(TvSignalService service, TvSignalRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @PostMapping(value = "/api/tv/signal", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receive(@RequestBody(required = false) String body) {
        TvSignalService.Result r = service.ingest(body);
        HttpStatus status = switch (r.status()) {
            case ACCEPTED, DUPLICATE -> HttpStatus.OK;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case DISABLED -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(r.id() == null
                ? Map.of("ok", status == HttpStatus.OK, "status", r.status().name(), "message", r.message())
                : Map.of("ok", true, "status", r.status().name(), "id", r.id()));
    }

    @GetMapping(value = "/api/tv/signals", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TvSignalEntity> latest(@RequestParam(defaultValue = "100") int limit) {
        return repo.findAllByOrderByIdDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }
}
