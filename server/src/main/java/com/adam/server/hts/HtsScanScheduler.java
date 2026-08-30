package com.adam.server.hts;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.SchedulerHeartbeat;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fires {@link HtsScanService#scan()} every 5 minutes so the FAST (H1/M5) model
 * acts on the M5 close; the CORE (H4/M15) and SWING (D1/H1) models re-evaluate
 * their last closed bar and the execution gate's per-bar idempotency stops a
 * duplicate entry. Override the cadence with {@code HTS_CRON}; timezone follows
 * the scan zone (Europe/Warsaw on Heroku).
 *
 * <p>Registers the {@code hts-scan} heartbeat — the one the watchdog cares about
 * during the forward test, and the one that fires the external healthcheck ping.
 */
@Component
public class HtsScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(HtsScanScheduler.class);
    private static final String PROBE = SchedulerHeartbeat.PING_PROBE; // "hts-scan"

    private final HtsScanService scan;
    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;

    public HtsScanScheduler(HtsScanService scan, SchedulerHeartbeat heartbeat, ErrorLog errorLog) {
        this.scan = scan;
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
    }

    @PostConstruct
    void registerProbe() {
        heartbeat.register(PROBE, Duration.ofMinutes(13));
    }

    @Scheduled(cron = "${app.hts.cron:0 */5 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onBarClose() {
        try {
            scan.scan();
            heartbeat.ok(PROBE);
        } catch (Exception e) {
            log.warn("Scheduled HTS scan failed: {}", e.getClass().getSimpleName());
            errorLog.record(PROBE, null, null, e);
        }
    }
}
