package com.adam.server.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liveness heartbeat for the schedulers (E-5). Each enabled scheduler
 * {@link #register}s a probe with the longest silence that is still healthy,
 * then calls {@link #ok} after every successful cycle. {@link SchedulerWatchdog}
 * reads {@link #stale} and alerts. A disabled scheduler never registers, so it
 * is never watched — exactly what we want while SDD / SWING are archived.
 *
 * <p>If {@code app.ops.healthcheck-url} is set, {@link #ok} also pings it (once
 * per {@link #PING_PROBE}) so an external monitor catches a whole-dyno outage.
 */
@Component
public class SchedulerHeartbeat {

    /** Only this probe fires the external ping — avoids 4× the traffic. */
    public static final String PING_PROBE = "hts-scan";

    private static final Logger log = LoggerFactory.getLogger(SchedulerHeartbeat.class);
    private static final HttpClient PING = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final Clock clock;
    private final String healthcheckUrl;
    private final Map<String, Duration> maxSilence = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastOk = new ConcurrentHashMap<>();

    public SchedulerHeartbeat(Clock clock, @Value("${app.ops.healthcheck-url:}") String healthcheckUrl) {
        this.clock = clock;
        this.healthcheckUrl = healthcheckUrl == null ? "" : healthcheckUrl.trim();
    }

    /**
     * Register a probe. Idempotent and quiet on repeat, so a scheduler may call
     * it every cycle (it needs to — a flag toggled on at runtime must start the
     * probe). Grace: counts as fresh until {@code maxSilence} after the first
     * registration.
     */
    public void register(String name, Duration maxSilence) {
        if (this.maxSilence.putIfAbsent(name, maxSilence) == null) {
            this.lastOk.putIfAbsent(name, clock.instant());
            log.info("Heartbeat probe registered: {} (max silence {}s)", name, maxSilence.toSeconds());
        }
    }

    /** A scheduler completed a cycle without throwing. */
    public void ok(String name) {
        lastOk.put(name, clock.instant());
        if (PING_PROBE.equals(name) && !healthcheckUrl.isEmpty()) {
            ping();
        }
    }

    public List<String> stale(Instant now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Duration> e : maxSilence.entrySet()) {
            Instant seen = lastOk.get(e.getKey());
            if (seen == null || Duration.between(seen, now).compareTo(e.getValue()) > 0) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public List<HeartbeatView> snapshot(Instant now) {
        List<HeartbeatView> out = new ArrayList<>();
        for (Map.Entry<String, Duration> e : maxSilence.entrySet()) {
            Instant seen = lastOk.get(e.getKey());
            long age = seen == null ? -1 : Duration.between(seen, now).toSeconds();
            boolean stale = seen == null || age > e.getValue().toSeconds();
            out.add(new HeartbeatView(e.getKey(), seen, age, e.getValue().toSeconds(), stale));
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    private void ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(healthcheckUrl))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            PING.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.debug("Healthcheck ping failed: {}", ex.getClass().getSimpleName());
                        return null;
                    });
        } catch (Exception e) {
            log.debug("Healthcheck ping not sent: {}", e.getClass().getSimpleName());
        }
    }

    public record HeartbeatView(String name, Instant lastOkAt, long ageSeconds,
                                long maxSilenceSeconds, boolean stale) {
    }
}
