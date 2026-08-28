package com.adam.server.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles the equity history with the broker's transaction feed:
 * <ul>
 *   <li>on application startup (e.g. right after a Heroku deploy), and</li>
 *   <li>every day at 03:00 Europe/Warsaw.</li>
 * </ul>
 * Safe to run at any time — it only inserts missing daily snapshots (never
 * touches existing rows unless {@code replace=true} is requested) and skips
 * books whose broker is not configured.
 */
@Component
public class EquityHistorySyncJob {

    private static final Logger log = LoggerFactory.getLogger(EquityHistorySyncJob.class);

    private final EquityHistoryService service;

    @Value("${app.history-sync.enabled:true}")
    private boolean enabled;

    public EquityHistorySyncJob(EquityHistoryService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled) {
            log.info("Equity history sync disabled (app.history-sync.enabled=false)");
            return;
        }
        log.info("Equity history sync: running on startup");
        syncBoth();
    }

    @Scheduled(cron = "${app.history-sync.cron:0 0 3 * * *}", zone = "Europe/Warsaw")
    public void nightly() {
        if (!enabled) {
            return;
        }
        log.info("Equity history sync: nightly run");
        syncBoth();
    }

    private void syncBoth() {
        for (String book : new String[]{"live", "demo", "glowne"}) {
            try {
                EquityHistoryService.SyncResult r = service.sync(book, false);
                log.info("Equity history sync [{}]: {} (written={}, skipped={})",
                        book, r.message(), r.written(), r.skipped());
            } catch (Exception e) {
                log.warn("Equity history sync [{}] failed: {}", book, e.getClass().getSimpleName());
            }
        }
    }
}
