package com.adam.server.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final ScanService scanService;
    private final SignalWebhookPublisher webhooks;

    /** SDD-M15 is archived for the HTS forward test — set {@code SCAN_ENABLED=false}. */
    @Value("${app.scan.enabled:true}")
    private boolean enabled = true;

    public ScanScheduler(ScanService scanService, SignalWebhookPublisher webhooks) {
        this.scanService = scanService;
        this.webhooks = webhooks;
    }

    @Scheduled(cron = "${app.scan.cron:0 1,16,31,46 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onM15Close() {
        if (!enabled) {
            return;
        }
        try {
            scanService.scan();
        } catch (Exception e) {
            log.warn("Scheduled scan failed: {}", e.getClass().getSimpleName());
            try {
                webhooks.publishFailover("scan_failed", AccountQueryService.publicMessage(e));
            } catch (Exception webhookEx) {
                log.warn("Failover webhook POST failed: {}", webhookEx.getClass().getSimpleName());
            }
        }
    }
}
