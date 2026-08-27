package com.adam.server.sdd;

import com.adam.server.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Red USD/EUR news blackout T-30 / T+30. Fail-open if the calendar cannot be loaded.
 */
@Component
public class NewsBlackout {

    private static final Logger log = LoggerFactory.getLogger(NewsBlackout.class);
    private static final Duration WINDOW = Duration.ofMinutes(30);
    private static final Set<String> RED = Set.of("high", "red", "3");
    private static final Set<String> CURRENCIES = Set.of("usd", "eur", "united states", "euro zone", "eurozone");

    private final AppProperties properties;
    private final RestClient restClient;
    private final Clock clock;

    private volatile Instant cacheAt = Instant.EPOCH;
    private volatile List<Event> cache = List.of();

    public NewsBlackout(AppProperties properties, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.restClient = builder.clone().build();
        this.clock = clock;
    }

    public boolean blocked(Instant now) {
        for (Event event : events()) {
            if (event.redUsdEur() && Duration.between(event.time(), now).abs().compareTo(WINDOW) <= 0) {
                return true;
            }
        }
        return false;
    }

    public List<Event> events() {
        Instant now = clock.instant();
        if (cacheAt.plus(Duration.ofMinutes(15)).isAfter(now)) {
            return cache;
        }
        String url = properties.getNewsCalendarUrl();
        if (url == null || url.isBlank()) {
            cache = List.of();
            cacheAt = now;
            return cache;
        }
        try {
            List<RawEvent> raw = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            List<Event> parsed = new ArrayList<>();
            if (raw != null) {
                for (RawEvent r : raw) {
                    Instant time = parseTime(r);
                    if (time == null) {
                        continue;
                    }
                    parsed.add(new Event(nz(r.title()), nz(r.country()), nz(r.impact()), time));
                }
            }
            cache = List.copyOf(parsed);
            cacheAt = now;
        } catch (Exception e) {
            log.warn("News calendar unavailable; blackout fail-open");
            cache = List.of();
            cacheAt = now;
        }
        return cache;
    }

    private static Instant parseTime(RawEvent r) {
        String raw = r.date() != null ? r.date() : r.datetime();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            try {
                return Instant.parse(raw + "Z");
            } catch (Exception e) {
                return null;
            }
        }
    }

    public record Event(String title, String country, String impact, Instant time) {
        boolean redUsdEur() {
            String i = impact.toLowerCase(Locale.ROOT);
            String c = country.toLowerCase(Locale.ROOT);
            return RED.contains(i) && CURRENCIES.contains(c);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawEvent(String title, String country, String impact, String date, String datetime) {
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
