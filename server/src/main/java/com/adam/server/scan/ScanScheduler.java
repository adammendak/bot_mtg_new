package com.adam.server.scan;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.SchedulerHeartbeat;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);
    private static final String PROBE = "sdd-scan";

    private final ScanService scanService;
    private final SignalWebhookPublisher webhooks;
    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;

    /** SDD-M15 is archived for the HTS forward test — set {@code SCAN_ENABLED=false}. */
    @Value("${app.scan.enabled:true}")
    private boolean enabled = true;

    public ScanScheduler(ScanService scanService, SignalWebhookPublisher webhooks,
                         SchedulerHeartbeat heartbeat, ErrorLog errorLog) {
        this.scanService = scanService;
        this.webhooks = webhooks;
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
    }

    @PostConstruct
    void registerProbe() {
        if (enabled) {
            heartbeat.register(PROBE, Duration.ofMinutes(33));
        }
    }

    @Scheduled(cron = "${app.scan.cron:0 1,16,31,46 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onM15Close() {
        if (!enabled) {
            return;
        }
        try {
            scanService.scan();
            heartbeat.ok(PROBE);
        } catch (Exception e) {
            log.warn("Scheduled scan failed: {}", e.getClass().getSimpleName());
            errorLog.record(PROBE, null, null, e);
            try {
                webhooks.publishFailover("scan_failed", AccountQueryService.publicMessage(e));
            } catch (Exception webhookEx) {
                log.warn("Failover webhook POST failed: {}", webhookEx.getClass().getSimpleName());
            }
        }
    }
}
