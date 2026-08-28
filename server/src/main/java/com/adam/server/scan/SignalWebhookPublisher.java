package com.adam.server.scan;

import com.adam.server.config.AppProperties;
import com.adam.server.sdd.SddScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * POSTs trade signals, failover, and scan_ok payloads to {@code AGENT_SIGNAL_WEBHOOK_URLS}.
 * Cursor automation webhooks authenticate with {@code Authorization: Bearer crsr_…}
 * (also sent as {@code X-Webhook-Secret}). Failures never throw to the scan.
 */
@Component
public class SignalWebhookPublisher {

    static final String HEADER_WEBHOOK_SECRET = "X-Webhook-Secret";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private static final Logger log = LoggerFactory.getLogger(SignalWebhookPublisher.class);

    private final AppProperties properties;
    private final RestClient restClient;
    private final AtomicBoolean lastTickHardFailed = new AtomicBoolean(false);

    private volatile Instant lastWebhookAt;
    private volatile String lastWebhookError;

    public SignalWebhookPublisher(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.clone().requestFactory(factory).build();
    }

    public Instant lastWebhookAt() {
        return lastWebhookAt;
    }

    public String lastWebhookError() {
        return lastWebhookError;
    }

    /**
     * {@code ok}, {@code never}, or a short error class such as {@code HTTP 500}.
     */
    public String lastWebhook() {
        if (lastWebhookAt == null) {
            return "never";
        }
        if (lastWebhookError == null || lastWebhookError.isBlank()) {
            return "ok";
        }
        return lastWebhookError;
    }

    public void publish(SddScan scan) {
        if (scan == null || (!scan.fullStack() && !scan.flip())) {
            return;
        }
        postAll(payload(scan), "signal");
    }

    public void publishFailover(String reason, String error) {
        lastTickHardFailed.set(true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "failover");
        body.put("reason", reason == null || reason.isBlank() ? "scan_failed" : reason);
        body.put("error", sanitizeError(error));
        body.put("scannedAt", null);
        postAll(body, "failover");
    }

    public void publishScanOk(Instant scannedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "scan_ok");
        body.put("scannedAt", scannedAt == null ? null : scannedAt.toString());
        postAll(body, "scan_ok");
    }

    /**
     * Execution feedback to Computron (fill / skip with reason) so it audits tickets
     * against webhooks, caps and stops instead of polling every 15 minutes.
     */
    public void publishExecution(String book, String symbol, String direction,
                                 String action, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "execution");
        body.put("book", book);
        body.put("symbol", symbol);
        body.put("direction", direction == null ? null : direction);
        body.put("action", action == null || action.isBlank() ? "unknown" : action);
        body.put("detail", detail == null ? "" : detail);
        body.put("timestamp", Instant.now().toString());
        postAll(body, "execution");
    }

    /**
     * Edge-trigger: one failover POST per failed scan tick; {@code scan_ok} only when
     * recovering from a previous hard failure. Per-symbol 404 skips are not failover.
     */
    public void onScanFinished(ScanSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.hardFailure()) {
            publishFailover("scan_failed", snapshot.error());
            return;
        }
        if (snapshot.completedWithSymbols() && lastTickHardFailed.compareAndSet(true, false)) {
            publishScanOk(snapshot.scannedAt());
        }
    }

    static Map<String, Object> payload(SddScan scan) {
        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("ha", scan.setup().ha());
        setup.put("rma", scan.setup().rma());
        setup.put("h1", scan.setup().h1());
        setup.put("pp", scan.setup().pp());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", scan.timestamp() == null ? null : scan.timestamp().toString());
        body.put("symbol", scan.symbol());
        body.put("epic", scan.epic());
        body.put("direction", scan.direction() == null ? null : scan.direction().name());
        body.put("setup", setup);
        body.put("stop", scan.stop());
        body.put("oneR", scan.oneR());
        body.put("atrH1", scan.atrH1());
        body.put("entry", scan.entry());
        body.put("actionable", scan.actionable());
        body.put("reason", scan.reason());
        body.put("failed", scan.failed());
        body.put("newBar", scan.newBar());
        body.put("flip", scan.flip());
        body.put("fullStack", scan.fullStack());
        return body;
    }

    static String sanitizeError(String raw) {
        if (raw == null || raw.isBlank()) {
            return "scan_failed";
        }
        String s = raw;
        s = s.replaceAll("(?i)crsr_[A-Za-z0-9_-]+", "crsr_[redacted]");
        s = s.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [redacted]");
        s = s.replaceAll("(?i)(api[_-]?key|password|token|secret)\\s*[=:]\\s*\\S+", "$1=[redacted]");
        s = s.replaceAll("\\?[^\\s]*", "");
        if (s.length() > 180) {
            s = s.substring(0, 180);
        }
        return s;
    }

    private void postAll(Map<String, Object> payload, String kind) {
        var urls = properties.webhookUrlList();
        if (urls.isEmpty()) {
            return;
        }
        String lastError = null;
        Instant at = Instant.now();
        for (String url : urls) {
            try {
                postOne(url, payload);
            } catch (RestClientResponseException e) {
                lastError = "HTTP " + e.getStatusCode().value();
                log.warn("Webhook POST failed: {} {} {}", kind, lastError, e.getClass().getSimpleName());
            } catch (Exception e) {
                lastError = timeoutClass(e);
                log.warn("Webhook POST failed: {} {}", kind, lastError);
            }
        }
        this.lastWebhookAt = at;
        this.lastWebhookError = lastError;
    }

    private void postOne(String url, Map<String, Object> payload) {
        String token = properties.webhookSenderToken();
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (!token.isEmpty()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HEADER_WEBHOOK_SECRET, token);
        }
        spec.body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private static String timeoutClass(Exception e) {
        String simple = e.getClass().getSimpleName();
        String msg = e.getMessage();
        boolean timeout = simple.toLowerCase().contains("timeout")
                || (msg != null && msg.toLowerCase().contains("timed out"))
                || (msg != null && msg.toLowerCase().contains("timeout"));
        if (timeout) {
            return "timeout " + simple;
        }
        return simple;
    }
}
