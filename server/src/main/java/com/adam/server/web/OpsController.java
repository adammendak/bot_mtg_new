package com.adam.server.web;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.SchedulerHeartbeat;
import com.adam.server.persistence.ErrorEventEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observability read side (E-5), behind the normal {@code /api/**} auth.
 * <ul>
 *   <li>{@code GET /api/ops/health} — scheduler heartbeats (fresh / stale).</li>
 *   <li>{@code GET /api/ops/errors?limit=} — the durable failure log, newest first.</li>
 * </ul>
 */
@RestController
public class OpsController {

    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;
    private final Clock clock;

    public OpsController(SchedulerHeartbeat heartbeat, ErrorLog errorLog, Clock clock) {
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
        this.clock = clock;
    }

    @GetMapping(value = "/api/ops/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Instant now = Instant.now(clock);
        List<SchedulerHeartbeat.HeartbeatView> probes = heartbeat.snapshot(now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("time", now.toString());
        body.put("staleCount", probes.stream().filter(SchedulerHeartbeat.HeartbeatView::stale).count());
        body.put("schedulers", probes);
        return body;
    }

    @GetMapping(value = "/api/ops/errors", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ErrorEventEntity> errors(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return errorLog.recent(limit);
    }
}
