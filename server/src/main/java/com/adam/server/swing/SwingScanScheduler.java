package com.adam.server.swing;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.SchedulerHeartbeat;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fires {@link SwingScanService#scan()} one minute after every H1 close.
 * Override the cadence with {@code SWING_CRON}; timezone follows the SDD-M15
 * scan zone (Europe/Warsaw on Heroku). Archived for the HTS forward test —
 * set {@code SWING_ENABLED=false}.
 */
@Component
public class SwingScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(SwingScanScheduler.class);
    private static final String PROBE = "swing-scan";

    private final SwingScanService scan;
    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;

    @Value("${app.swing.enabled:true}")
    private boolean enabled = true;

    public SwingScanScheduler(SwingScanService scan, SchedulerHeartbeat heartbeat, ErrorLog errorLog) {
        this.scan = scan;
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
    }

    @PostConstruct
    void registerProbe() {
        if (enabled) {
            heartbeat.register(PROBE, Duration.ofMinutes(125));
        }
    }

    @Scheduled(cron = "${app.swing.cron:0 1 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onH1Close() {
        if (!enabled) {
            return;
        }
        try {
            scan.scan();
            heartbeat.ok(PROBE);
        } catch (Exception e) {
            log.warn("Scheduled SWING scan failed: {}", e.getClass().getSimpleName());
            errorLog.record(PROBE, null, null, e);
        }
    }
}
