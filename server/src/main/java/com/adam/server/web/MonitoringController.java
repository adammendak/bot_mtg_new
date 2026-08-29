package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.scan.MonitoringService;
import com.adam.server.web.dto.AuditEvent;
import com.adam.server.web.dto.PositionMonitorView;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Position monitoring and manual dashboard actions.
 * <ul>
 *   <li>{@code GET /api/monitor?book=…} — positions with time-in-position,
 *       stop-drift and sleeping flags (#8, #9).</li>
 *   <li>{@code GET /api/monitor/audit?book=…} — execution timeline (#6).</li>
 *   <li>{@code POST /api/monitor/close|be|stop} — manual actions, demo only (#7).</li>
 * </ul>
 * Every endpoint enforces the caller's book access.
 */
@RestController
public class MonitoringController {

    private final MonitoringService monitor;

    public MonitoringController(MonitoringService monitor) {
        this.monitor = monitor;
    }

    @GetMapping(value = "/api/monitor", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PositionMonitorView> monitor(
            @RequestParam(name = "book", defaultValue = "demo") String book,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return List.of();
        }
        return monitor.monitor(book);
    }

    @GetMapping(value = "/api/monitor/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AuditEvent> audit(
            @RequestParam(name = "book", required = false) String book,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (book != null && user != null && !user.canSeeBook(book)) {
            return List.of();
        }
        return monitor.audit(book);
    }

    @PostMapping(value = "/api/monitor/close", produces = MediaType.APPLICATION_JSON_VALUE)
    public String close(
            @RequestParam(name = "book", defaultValue = "demo") String book,
            @RequestParam(name = "dealId") String dealId,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return "no access to book " + book;
        }
        return monitor.close(book, dealId);
    }

    @PostMapping(value = "/api/monitor/be", produces = MediaType.APPLICATION_JSON_VALUE)
    public String moveToBreakEven(
            @RequestParam(name = "book", defaultValue = "demo") String book,
            @RequestParam(name = "dealId") String dealId,
            @RequestParam(name = "entry", defaultValue = "0") double entry,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return "no access to book " + book;
        }
        return monitor.moveToBreakEven(book, dealId, entry);
    }

    @PostMapping(value = "/api/monitor/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public String tightenStop(
            @RequestParam(name = "book", defaultValue = "demo") String book,
            @RequestParam(name = "dealId") String dealId,
            @RequestParam(name = "stop") double stop,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return "no access to book " + book;
        }
        return monitor.tightenStop(book, dealId, stop);
    }
}
