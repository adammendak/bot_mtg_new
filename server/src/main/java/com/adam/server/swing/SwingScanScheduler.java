package com.adam.server.swing;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.FeatureFlags;
import com.adam.server.ops.SchedulerHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fires {@link SwingScanService#scan()} one minute after every H1 close.
 * Override the cadence with {@code SWING_CRON}; timezone follows the SDD-M15
 * scan zone (Europe/Warsaw on Heroku). Archived for the HTS forward test —
 * toggle {@code swing.scan} off.
 */
@Component
public class SwingScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(SwingScanScheduler.class);
    private static final String PROBE = "swing-scan";
    private static final String FLAG = "swing.scan";

    private final SwingScanService scan;
    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;
    private final FeatureFlags flags;

    public SwingScanScheduler(SwingScanService scan, SchedulerHeartbeat heartbeat, ErrorLog errorLog,
                              FeatureFlags flags) {
        this.scan = scan;
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
        this.flags = flags;
    }

    @Scheduled(cron = "${app.swing.cron:0 1 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onH1Close() {
        if (!flags.enabled(FLAG)) {
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
