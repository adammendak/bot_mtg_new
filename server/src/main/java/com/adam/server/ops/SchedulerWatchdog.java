package com.adam.server.ops;

import com.adam.server.scan.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Dead-man switch (E-5). Every {@code app.ops.watchdog-ms} it asks
 * {@link SchedulerHeartbeat} which probes have gone quiet past their healthy
 * window and, if any have, sends one throttled mail and records an
 * {@link ErrorLog} event. When everything is fresh again the throttle is
 * cleared so the next outage alerts immediately.
 *
 * <p>This is the only thing that notices a scan that silently stopped — a dyno
 * restart, an exception in the cron, or a Capital.com rate-limit storm — since
 * "no signals" on its own never raises an alarm.
 */
@Component
public class SchedulerWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SchedulerWatchdog.class);
    private static final String KEY = "scheduler-watchdog";

    private final SchedulerHeartbeat heartbeat;
    private final Mailer mailer;
    private final ErrorLog errorLog;
    private final Clock clock;
    private final boolean enabled;

    public SchedulerWatchdog(SchedulerHeartbeat heartbeat, Mailer mailer, ErrorLog errorLog, Clock clock,
                             @Value("${app.ops.watchdog-enabled:true}") boolean enabled) {
        this.heartbeat = heartbeat;
        this.mailer = mailer;
        this.errorLog = errorLog;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.ops.watchdog-ms:300000}",
            fixedDelayString = "${app.ops.watchdog-ms:300000}")
    public void check() {
        if (!enabled) {
            return;
        }
        try {
            List<String> stale = heartbeat.stale(clock.instant());
            if (stale.isEmpty()) {
                mailer.clearThrottle(KEY);
                return;
            }
            String list = String.join(", ", stale);
            log.warn("Scheduler watchdog: stale probe(s) — {}", list);
            errorLog.record("watchdog", null, list, "SchedulerStale",
                    "No successful cycle within the healthy window for: " + list);
            mailer.sendThrottled(KEY, "Bot: scheduler(y) nie odpalają",
                    "Watchdog nie widzi udanego cyklu dla: " + list + ".\n\n"
                            + "Możliwe: restart dyno, wyjątek w cronie, rate-limit Capital.com.\n"
                            + "Sprawdź logi Heroku i /api/ops/health.\n\n"
                            + "(kolejne alerty w ciągu 30 min są wyciszone)");
        } catch (Exception e) {
            log.warn("Scheduler watchdog failed: {}", e.getClass().getSimpleName());
        }
    }
}
