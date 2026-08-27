package com.adam.server.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final ScanService scanService;

    public ScanScheduler(ScanService scanService) {
        this.scanService = scanService;
    }

    @Scheduled(cron = "${app.scan.cron:0 1,16,31,46 8-22 * * MON-FRI}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onM15Close() {
        try {
            scanService.scan();
        } catch (Exception e) {
            log.warn("Scheduled scan failed");
        }
    }
}
