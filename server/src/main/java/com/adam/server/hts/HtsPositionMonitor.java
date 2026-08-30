package com.adam.server.hts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the HTS runner exit on its own cron — 30 s into every 5-minute slot,
 * just after the scan. Each pass {@link HtsTradeService#manage()}:
 * <ul>
 *   <li>closes half the position at TP1 (1:2 R:R) and locks the runner's stop
 *       at break-even + 1R;</li>
 *   <li>after TP1, trails the remaining stop under the fast band and flattens
 *       the runner on a candle body closing beyond the slow band;</li>
 *   <li>reconciles: any OPEN trade the broker no longer reports is flipped to
 *       CLOSED with its outcome (exit, R, P/L, reason) filled in.</li>
 * </ul>
 * Override the cadence with {@code HTS_MONITOR_CRON}; disable with
 * {@code HTS_MONITOR_ENABLED=false}.
 */
@Component
public class HtsPositionMonitor {

    private static final Logger log = LoggerFactory.getLogger(HtsPositionMonitor.class);

    private final HtsTradeService trades;

    @Value("${app.hts.monitor-enabled:true}")
    private boolean enabled = true;

    public HtsPositionMonitor(HtsTradeService trades) {
        this.trades = trades;
    }

    @Scheduled(cron = "${app.hts.monitor-cron:30 */5 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            int touched = trades.manage();
            if (touched > 0) {
                log.info("HTS position monitor: touched {} trade(s)", touched);
            }
        } catch (Exception e) {
            log.warn("HTS position monitor failed: {}", e.getClass().getSimpleName());
        }
    }
}
