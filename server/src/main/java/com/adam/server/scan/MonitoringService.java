package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.web.dto.AuditEvent;
import com.adam.server.web.dto.PositionMonitorView;
import com.adam.server.web.dto.PositionRiskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Position monitoring + manual actions from the dashboard.
 * <ul>
 *   <li>#6 audit: in-memory ring of execution events (placed / tp_closed / trail
 *       / be / stop / closed / skip) surfaced via {@code /api/monitor/audit}.</li>
 *   <li>#7 actions: close a single ticket, move the runner to break-even, or
 *       tighten the stop — demo only by default (never live without explicit flag).</li>
 *   <li>#8 stop-drift alert: a position whose stop moved from its entry stop
 *       (trail) or has no stop at all is flagged.</li>
 *   <li>#9 time-in-position: how long each position has been open; "sleeping"
 *       when open longer than {@code app.monitor.sleep-minutes} (default 240).</li>
 * </ul>
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final int MAX_AUDIT = 200;
    private static final Duration DEFAULT_SLEEP = Duration.ofMinutes(240);

    private final BrokerBooks books;
    private final AppProperties properties;
    private final Clock clock;
    private final ConcurrentLinkedDeque<AuditEvent> audit = new ConcurrentLinkedDeque<>();

    public MonitoringService(BrokerBooks books, AppProperties properties, Clock clock) {
        this.books = books;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // #6 audit
    // ------------------------------------------------------------------

    public void record(String book, String symbol, String action, String detail) {
        audit.addFirst(new AuditEvent(clock.instant(), book, symbol, action, detail));
        while (audit.size() > MAX_AUDIT) {
            audit.pollLast();
        }
    }

    public List<AuditEvent> audit(String book) {
        List<AuditEvent> out = new ArrayList<>();
        for (AuditEvent e : audit) {
            if (book == null || book.isBlank() || book.equalsIgnoreCase(e.book())) {
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // #9 monitor view (time-in-position, stop drift, sleeping)
    // ------------------------------------------------------------------

    public List<PositionMonitorView> monitor(String book) {
        BrokerClient client = books.forBook(book);
        if (client == null || !client.configured()) {
            return List.of();
        }
        try {
            if (!client.isSessionOpen()) {
                client.login();
            }
            List<Position> open = client.openPositions();
            Instant now = clock.instant();
            Duration sleep = sleepThreshold();
            List<PositionMonitorView> out = new ArrayList<>();
            for (Position p : open) {
                long openMinutes = p.openedAt() == null ? 0
                        : Math.max(0, Duration.between(p.openedAt(), now).toMinutes());
                boolean stopDrifted = detectDrift(client, p);
                boolean sleeping = sleep != null && openMinutes >= sleep.toMinutes();
                out.add(new PositionMonitorView(
                        p.dealId(),
                        p.epic(),
                        p.direction() == null ? null : p.direction().name(),
                        p.size(),
                        p.level(),
                        p.stopLevel(),
                        p.unrealizedPnl(),
                        p.currency(),
                        PositionRiskView.riskOf(p.direction(), p.level(), p.stopLevel(), p.size()),
                        openMinutes,
                        p.openedAt() == null ? null : p.openedAt().toString(),
                        stopDrifted,
                        sleeping
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("Position monitor failed for {}: {}", book, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** #8: stop drifted if a stop existed at entry and is now gone or moved far. */
    private boolean detectDrift(BrokerClient client, Position p) {
        if (p.stopLevel() == null) {
            return true; // no protective stop = dangerous
        }
        // Trailing is expected to move the stop toward the favourable side.
        // A stop on the WRONG side of entry (beyond it) is a real drift.
        double entry = p.level();
        double stop = p.stopLevel();
        if (p.direction() == Direction.BUY) {
            return stop > entry; // buy stop above entry = drifted
        }
        return stop < entry; // sell stop below entry = drifted
    }

    // ------------------------------------------------------------------
    // #7 manual actions (demo by default)
    // ------------------------------------------------------------------

    public String close(String book, String dealId) {
        BrokerClient client = books.forBook(book);
        if (!allowManual(book, client)) {
            return "manual actions allowed on demo only";
        }
        try {
            client.closePosition(dealId, 0);
            record(book, dealId, "closed", "manual close");
            return "closed " + dealId;
        } catch (Exception e) {
            return "close failed: " + e.getClass().getSimpleName();
        }
    }

    public String moveToBreakEven(String book, String dealId, double entry) {
        BrokerClient client = books.forBook(book);
        if (!allowManual(book, client)) {
            return "manual actions allowed on demo only";
        }
        try {
            client.amendPosition(dealId, entry, false);
            record(book, dealId, "be", "manual move to break-even " + entry);
            return "moved to BE " + entry;
        } catch (Exception e) {
            return "BE failed: " + e.getClass().getSimpleName();
        }
    }

    public String tightenStop(String book, String dealId, double newStop) {
        BrokerClient client = books.forBook(book);
        if (!allowManual(book, client)) {
            return "manual actions allowed on demo only";
        }
        try {
            client.amendPosition(dealId, newStop, false);
            record(book, dealId, "stop", "manual tighten stop to " + newStop);
            return "stop tightened to " + newStop;
        } catch (Exception e) {
            return "stop tighten failed: " + e.getClass().getSimpleName();
        }
    }

    private boolean allowManual(String book, BrokerClient client) {
        return client != null && "demo".equals(book) && client.configured();
    }

    private Duration sleepThreshold() {
        try {
            long minutes = Long.parseLong(properties.getMonitorSleepMinutes());
            return minutes <= 0 ? null : Duration.ofMinutes(minutes);
        } catch (Exception e) {
            return DEFAULT_SLEEP;
        }
    }
}
