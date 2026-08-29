package com.adam.server.hts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link HtsScanService#scan()} two minutes after every H1 close (one
 * minute behind the SDD-SWING scan, so the shared market-data session is not hit
 * by both at once). Override with {@code HTS_CRON}; timezone follows the SDD-M15
 * scan zone (Europe/Warsaw on Heroku).
 */
@Component
public class HtsScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(HtsScanScheduler.class);

    private final HtsScanService scan;

    public HtsScanScheduler(HtsScanService scan) {
        this.scan = scan;
    }

    @Scheduled(cron = "${app.hts.cron:0 2 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onH1Close() {
        try {
            scan.scan();
        } catch (Exception e) {
            log.warn("Scheduled HTS scan failed: {}", e.getClass().getSimpleName());
        }
    }
}
