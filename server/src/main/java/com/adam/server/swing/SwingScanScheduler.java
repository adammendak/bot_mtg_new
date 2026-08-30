package com.adam.server.swing;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.SchedulerHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SDD-SWING is retired. Kept wired for a possible manual revival, gated by
 * {@code SWING_ENABLED} (env, default <b>false</b>) — not on the HTS
 * feature-flag panel.
 */
@Component
public class SwingScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(SwingScanScheduler.class);
    private static final String PROBE = "swing-scan";

    private final SwingScanService scan;
    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;
    private final boolean enabled;

    public SwingScanScheduler(SwingScanService scan, SchedulerHeartbeat heartbeat, ErrorLog errorLog,
                              @Value("${app.swing.enabled:false}") boolean enabled) {
        this.scan = scan;
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.swing.cron:0 1 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onH1Close() {
        if (!enabled) {
            return;
        }
        heartbeat.register(PROBE, Duration.ofMinutes(125));
        try {
            scan.scan();
            heartbeat.ok(PROBE);
        } catch (Exception e) {
            log.warn("Scheduled SWING scan failed: {}", e.getClass().getSimpleName());
            errorLog.record(PROBE, null, null, e);
        }
    }
}
