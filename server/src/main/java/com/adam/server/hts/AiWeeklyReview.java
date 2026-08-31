package com.adam.server.hts;

import com.adam.server.config.AppProperties;
import com.adam.server.ops.ErrorLog;
import com.adam.server.scan.Mailer;
import com.adam.server.web.dto.HtsJournal;
import com.adam.server.web.dto.HtsScorecardRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * E-12 — weekly AI review of the HTS forward test. Every Monday morning it
 * gathers the last 7 days of closed {@code hts_trades} (via {@link HtsTradeService})
 * and asks Claude for a short Polish write-up: what worked, what didn't, whether
 * a variant or instrument is systematically lagging, any pattern in the close
 * reasons. The reply is mailed through the shared {@link Mailer}.
 *
 * <p>Opt-in: needs {@code ANTHROPIC_API_KEY} and {@code app.hts.ai-review-enabled=true}.
 * No API key or disabled → silent no-op. Not investment advice — an engineering
 * observation, and the prompt says so.
 */
@Component
public class AiWeeklyReview {

    private static final Logger log = LoggerFactory.getLogger(AiWeeklyReview.class);

    private final HtsTradeService trades;
    private final Mailer mailer;
    private final ErrorLog errorLog;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String endpoint;

    public AiWeeklyReview(HtsTradeService trades, Mailer mailer, ErrorLog errorLog,
                          ObjectMapper mapper, AppProperties properties,
                          @Value("${app.hts.ai-review-enabled:false}") boolean enabled,
                          @Value("${anthropic.api-key:}") String apiKey,
                          @Value("${anthropic.model:claude-sonnet-5}") String model,
                          @Value("${anthropic.endpoint:https://api.anthropic.com/v1/messages}") String endpoint) {
        this.trades = trades;
        this.mailer = mailer;
        this.errorLog = errorLog;
        this.mapper = mapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.endpoint = endpoint;
        this.http = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    @Scheduled(cron = "${app.hts.ai-review-cron:0 30 7 * * MON}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void run() {
        if (!enabled || apiKey.isEmpty()) {
            return;
        }
        try {
            Instant now = Instant.now();
            HtsJournal week = trades.journal(null, null, now.minus(Duration.ofDays(7)), now);
            if (week.trades() == 0) {
                mailer.send("HTS — przegląd tygodnia", "Brak zamkniętych tradów HTS w ostatnich 7 dniach.");
                return;
            }
            String facts = facts(week, trades.scorecard());
            String review = ask(facts);
            mailer.send("HTS — przegląd tygodnia (AI)",
                    review + "\n\n---\nDane wejściowe:\n" + facts);
            log.info("AI weekly review sent ({} trades in window)", week.trades());
        } catch (Exception e) {
            log.warn("AI weekly review failed: {}", e.getClass().getSimpleName());
            errorLog.record("ai-review", null, null, e);
        }
    }

    private String ask(String facts) {
        String prompt = """
                Jesteś asystentem tradera-programisty. Poniżej surowe wyniki tygodnia forward-testu \
                strategii HTS ("wstęgi") — 3 warianty timeframe na kontach demo (CORE H4/M15, \
                SWING D1/H1, FAST H1/M5), jeden na koncie live (CORE_LIVE H4/M15) i 2 na OKX crypto \
                (CORE_OKX H4/M15, FAST_OKX H1/M5).

                Napisz zwięzłe podsumowanie po polsku (max ~250 słów):
                - co zagrało, co nie,
                - czy któryś wariant lub instrument systematycznie odstaje,
                - czy widać wzorzec w powodach zamknięć (STOP / TRAIL / TARGET / RUNNER / WEEKEND / MANUAL),
                - jedno–dwa konkretne pytania/hipotezy do sprawdzenia w kolejnym tygodniu.

                To obserwacja inżynierska, nie porada inwestycyjna. Nie proponuj zmian w kapitale \
                ani wielkości pozycji. Trzymaj się danych.

                DANE:
                %s
                """.formatted(facts);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1200,
                "messages", List.of(Map.of("role", "user", "content", prompt)));

        String raw = http.post()
                .uri(endpoint)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode content = mapper.readTree(raw).get("content");
        StringBuilder sb = new StringBuilder();
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                JsonNode type = block.get("type");
                JsonNode text = block.get("text");
                if (type != null && "text".equals(type.asString()) && text != null) {
                    sb.append(text.asString());
                }
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "(AI nie zwróciło treści)\n\n" + raw : out;
    }

    private static String facts(HtsJournal j, List<HtsScorecardRow> board) {
        StringBuilder sb = new StringBuilder();
        sb.append("Okno 7 dni: %d tradów, win rate %.0f%%, avg %.2fR, ΣR %.2f%n"
                .formatted(j.trades(), j.winRate() * 100, j.avgR(), j.sumR()));
        sb.append("\nRozkład R: ");
        j.rHistogram().forEach(b -> sb.append(b.label()).append('=').append(b.count()).append("  "));
        sb.append("\n\nWg powodu zamknięcia:\n");
        j.byReason().forEach(g -> sb.append("  %-8s n=%-3d ΣR=%.2f%n".formatted(g.key(), g.trades(), g.sumR())));
        sb.append("\nWg symbolu:\n");
        j.bySymbol().forEach(g -> sb.append("  %-8s n=%-3d win=%.0f%% avg=%.2fR%n"
                .formatted(g.key(), g.trades(), g.winRate() * 100, g.avgR())));
        sb.append("\nScorecard narastająco (per wariant):\n");
        for (HtsScorecardRow r : board) {
            sb.append("  %-10s %s/%s  otw=%d zamk=%d(%dW/%dL) win=%.0f%% avg=%.2fR ΣR=%.2f maxDD=%.2fR%n"
                    .formatted(r.variant(), r.htf(), r.ltf(), r.openTrades(), r.closedTrades(),
                            r.wins(), r.losses(), r.winRate() * 100, r.avgR(), r.sumR(), r.maxDrawdownR()));
        }
        return sb.toString();
    }
}
