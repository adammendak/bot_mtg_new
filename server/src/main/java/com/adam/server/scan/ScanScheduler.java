package com.adam.server.scan;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.SchedulerHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SDD-M15 is retired. This scheduler stays wired for a possible manual revival
 * but is gated by {@code SCAN_ENABLED} (env, default <b>false</b>) — it is not on
 * the HTS feature-flag panel.
 */
@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);
    private static final String PROBE = "sdd-scan";

    private final ScanService scanService;
    private final SignalWebhookPublisher webhooks;
    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;
    private final boolean enabled;

    public ScanScheduler(ScanService scanService, SignalWebhookPublisher webhooks,
                         SchedulerHeartbeat heartbeat, ErrorLog errorLog,
                         @Value("${app.scan.enabled:false}") boolean enabled) {
        this.scanService = scanService;
        this.webhooks = webhooks;
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.scan.cron:0 1,16,31,46 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void onM15Close() {
        if (!enabled) {
            return;
        }
        heartbeat.register(PROBE, Duration.ofMinutes(33));
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
