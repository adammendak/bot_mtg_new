package com.adam.server.scan;

import com.adam.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends SDD event notifications to a Telegram chat via the Bot API
 * ({@code TELEGRAM_BOT_TOKEN} + {@code TELEGRAM_CHAT_ID}). Fail-open: when the
 * token/chat is not configured, or a send fails, nothing is thrown to the scan.
 * Messages cover the roadmap's Priority-3 alerts: entry fill, 2R taken, day-P/L
 * halt, and scan failure.
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final AppProperties properties;
    private final RestClient restClient;
    /** Telegram Bot API base; overridable for tests. */
    String apiBase = "https://api.telegram.org";
    /** Per-book halt edge: true while a halt notification is active for that book. */
    private final Map<String, Boolean> haltActive = new ConcurrentHashMap<>();
    private final AtomicBoolean errorActive = new AtomicBoolean(false);

    private volatile Instant lastSentAt;
    private volatile String lastError;

    public TelegramNotifier(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = builder.clone().requestFactory(factory).build();
    }

    public Instant lastSentAt() {
        return lastSentAt;
    }

    public String lastError() {
        return lastError;
    }

    public void onFill(String book, String symbol, String direction, double size, double entry, double stop) {
        send(text("📈 FILL " + label(book) + " " + symbol + " " + direction
                + "\nsize " + trim(size) + " @ " + trim(entry) + "  stop " + trim(stop)));
    }

    public void onTake2R(String book, String symbol, double entry, double atr) {
        send(text("🎯 2R TAKEN " + label(book) + " " + symbol
                + "\nrunner to break-even (entry " + trim(entry) + ", 1R " + trim(atr) + ")"));
    }

    /**
     * Edge-triggered day halt: notifies once when a book enters halt, and resets
     * the edge so a later new halt after recovery alerts again.
     */
    public void onHalt(String book, double dayPnl, double haltPln, boolean halted) {
        Boolean was = haltActive.put(book, halted);
        if (!halted) {
            return; // recovered — just clear the edge
        }
        if (Boolean.TRUE.equals(was)) {
            return; // already notifying this halt — do not spam every 15 min
        }
        send(text("🛑 DAY HALT " + label(book) + "\nday P/L " + trim(dayPnl) + " <= " + trim(haltPln)));
    }

    /** Edge-triggered scan failure: notifies once per failure episode. */
    public void onScanError(String message) {
        if (message == null || message.isBlank()) {
            onScanRecovered();
            return;
        }
        if (errorActive.getAndSet(true)) {
            return; // already notifying this failure episode
        }
        send(text("⚠️ SCAN FAILED\n" + message));
    }

    /** Clears the scan-failure edge so the next failure alerts again. */
    public void onScanRecovered() {
        errorActive.set(false);
    }

    private static String label(String book) {
        return book == null ? "?" : "[" + book.toUpperCase() + "]";
    }

    private static String trim(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static Map<String, Object> text(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        return body;
    }

    /** Best-effort POST to sendMessage; never throws to the caller. */
    public void send(Map<String, Object> body) {
        if (!properties.telegramConfigured()) {
            return; // not configured — silent no-op
        }
        String token = properties.getTelegramBotToken();
        try {
            body.put("chat_id", properties.getTelegramChatId());
            restClient.post()
                    .uri(apiBase + "/bot{token}/sendMessage", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            this.lastSentAt = Instant.now();
            this.lastError = null;
        } catch (RestClientResponseException e) {
            this.lastError = "HTTP " + e.getStatusCode().value();
            log.warn("Telegram send failed: {}", lastError);
        } catch (Exception e) {
            this.lastError = e.getClass().getSimpleName();
            log.warn("Telegram send failed: {}", lastError);
        }
    }
}
