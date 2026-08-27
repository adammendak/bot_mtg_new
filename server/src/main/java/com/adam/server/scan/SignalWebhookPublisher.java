package com.adam.server.scan;

import com.adam.server.config.AppProperties;
import com.adam.server.sdd.SddScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SignalWebhookPublisher {

    private static final Logger log = LoggerFactory.getLogger(SignalWebhookPublisher.class);

    private final AppProperties properties;
    private final RestClient restClient;

    public SignalWebhookPublisher(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.clone().build();
    }

    public void publish(SddScan scan) {
        if (!scan.fullStack() && !scan.flip()) {
            return;
        }
        Map<String, Object> payload = payload(scan);
        for (String url : properties.webhookUrlList()) {
            try {
                restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Webhook POST failed for {}", scan.symbol());
            }
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
}
