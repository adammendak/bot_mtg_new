package com.adam.server.hts;

import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.FeatureFlags;
import com.adam.server.ops.SchedulerHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
 * Override the cadence with {@code HTS_MONITOR_CRON}; toggle with the
 * {@code hts.monitor} feature flag.
 */
@Component
public class HtsPositionMonitor {

    private static final Logger log = LoggerFactory.getLogger(HtsPositionMonitor.class);
    private static final String PROBE = "hts-monitor";
    private static final String FLAG = "hts.monitor";

    private final HtsTradeService trades;
    private final SchedulerHeartbeat heartbeat;
    private final ErrorLog errorLog;
    private final FeatureFlags flags;

    public HtsPositionMonitor(HtsTradeService trades, SchedulerHeartbeat heartbeat, ErrorLog errorLog,
                              FeatureFlags flags) {
        this.trades = trades;
        this.heartbeat = heartbeat;
        this.errorLog = errorLog;
        this.flags = flags;
    }

    @Scheduled(cron = "${app.hts.monitor-cron:30 */5 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void run() {
        if (!flags.enabled(FLAG)) {
            return;
        }
        heartbeat.register(PROBE, Duration.ofMinutes(13));
        try {
            int touched = trades.manage();
            heartbeat.ok(PROBE);
            if (touched > 0) {
                log.info("HTS position monitor: touched {} trade(s)", touched);
            }
        } catch (Exception e) {
            log.warn("HTS position monitor failed: {}", e.getClass().getSimpleName());
            errorLog.record(PROBE, null, null, e);
        }
    }
}
