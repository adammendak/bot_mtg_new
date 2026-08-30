package com.adam.server.swing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link SwingScanService#scan()} one minute after every H1 close.
 * Override the cadence with {@code SWING_CRON}; timezone follows the SDD-M15
 * scan zone (Europe/Warsaw on Heroku). Archived for the HTS forward test —
 * set {@code SWING_ENABLED=false}.
 */
@Component
public class SwingScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(SwingScanScheduler.class);

    private final SwingScanService scan;

    @Value("${app.swing.enabled:true}")
    private boolean enabled = true;

    public SwingScanScheduler(SwingScanService scan) {
        this.scan = scan;
    }

    @Scheduled(cron = "${app.swing.cron:0 1 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onH1Close() {
        if (!enabled) {
            return;
        }
        try {
            scan.scan();
        } catch (Exception e) {
            log.warn("Scheduled SWING scan failed: {}", e.getClass().getSimpleName());
        }
    }
}
