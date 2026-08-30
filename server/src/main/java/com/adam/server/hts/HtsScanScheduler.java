package com.adam.server.hts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link HtsScanService#scan()} every 5 minutes so the FAST (H1/M5) model
 * acts on the M5 close; the CORE (H4/M15) and SWING (D1/H1) models re-evaluate
 * their last closed bar and the execution gate's per-bar idempotency stops a
 * duplicate entry. Override the cadence with {@code HTS_CRON}; timezone follows
 * the scan zone (Europe/Warsaw on Heroku).
 */
@Component
public class HtsScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(HtsScanScheduler.class);

    private final HtsScanService scan;

    public HtsScanScheduler(HtsScanService scan) {
        this.scan = scan;
    }

    @Scheduled(cron = "${app.hts.cron:0 */5 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onBarClose() {
        try {
            scan.scan();
        } catch (Exception e) {
            log.warn("Scheduled HTS scan failed: {}", e.getClass().getSimpleName());
        }
    }
}
