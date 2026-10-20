package com.adam.server.tv;

import com.adam.server.config.AppProperties;
import com.adam.server.persistence.TvSignalEntity;
import com.adam.server.persistence.TvSignalRepository;
import com.adam.server.scan.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Observe-only ingest of TradingView alert webhooks (AlgoPro forward test).
 * TradingView cannot send headers, so the shared secret travels in the JSON body.
 * Nothing here places an order — it records the event, de-duplicates TradingView's
 * retries and mails entries.
 */
@Service
public class TvSignalService {

    private static final Logger log = LoggerFactory.getLogger(TvSignalService.class);
    private static final Set<String> EVENTS = Set.of("entry", "tp", "invalid");

    public enum Status { DISABLED, UNAUTHORIZED, BAD_REQUEST, DUPLICATE, ACCEPTED }

    public record Result(Status status, String message, Long id) {
        static Result of(Status s, String m) {
            return new Result(s, m, null);
        }
    }

    private final AppProperties properties;
    private final TvSignalRepository repo;
    private final Mailer mailer;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TvExecutionService execution;

    public TvSignalService(AppProperties properties, TvSignalRepository repo, Mailer mailer,
                           ObjectMapper mapper, Clock clock, TvExecutionService execution) {
        this.properties = properties;
        this.repo = repo;
        this.mailer = mailer;
        this.mapper = mapper;
        this.clock = clock;
        this.execution = execution;
    }

    public Result ingest(String body) {
        String secret = properties.getTv().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return Result.of(Status.DISABLED, "webhook disabled (TV_WEBHOOK_SECRET not set)");
        }
        JsonNode j;
        try {
            j = mapper.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            return Result.of(Status.BAD_REQUEST, "body is not valid JSON");
        }
        if (j == null || !j.isObject()) {
            return Result.of(Status.BAD_REQUEST, "body must be a JSON object");
        }
        if (!constantTimeEquals(secret, text(j, "secret"))) {
            return Result.of(Status.UNAUTHORIZED, "bad secret");
        }
        String strat = lower(text(j, "strat"), "algopro");
        String event = lower(text(j, "event"), null);
        String side = upper(text(j, "side"));
        String rawSym = text(j, "sym");
        String symbol = TvSymbols.normalize(rawSym);
        Double price = number(j, "price");
        if (event == null || !EVENTS.contains(event)) {
            return Result.of(Status.BAD_REQUEST, "event must be entry|tp|invalid");
        }
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            return Result.of(Status.BAD_REQUEST, "side must be BUY|SELL");
        }
        if (symbol == null || symbol.isBlank() || symbol.length() > 24) {
            return Result.of(Status.BAD_REQUEST, "sym missing");
        }
        if (price == null || !(price > 0) || Double.isInfinite(price)) {
            return Result.of(Status.BAD_REQUEST, "price must be a positive number");
        }
        String tf = text(j, "tf");
        String barTime = text(j, "t");
        Double sl = positive(number(j, "sl"));
        Double tp = positive(number(j, "tp"));

        String key = String.join("|", strat, event, side, symbol, tf == null ? "" : tf,
                barTime == null || barTime.isBlank() ? "p" + price : barTime);
        if (key.length() > 200) {
            key = key.substring(0, 200);
        }
        if (repo.existsByDedupeKey(key)) {
            return Result.of(Status.DUPLICATE, "already recorded");
        }
        TvSignalEntity e = new TvSignalEntity();
        e.setReceivedAt(Instant.now(clock));
        e.setStrat(strat);
        e.setEvent(event);
        e.setSide(side);
        e.setRawSymbol(rawSym == null ? null : rawSym.substring(0, Math.min(48, rawSym.length())));
        e.setSymbol(symbol);
        e.setTf(tf == null ? null : tf.substring(0, Math.min(16, tf.length())));
        e.setPrice(price);
        e.setSl(sl);
        e.setTp(tp);
        e.setBarTime(barTime == null ? null : barTime.substring(0, Math.min(40, barTime.length())));
        e.setDedupeKey(key);
        TvSignalEntity saved = repo.save(e);
        log.info("TV [{}] {} {} {} @ {} tf={} sl={} tp={}", strat, event.toUpperCase(Locale.ROOT), side, symbol,
                price, tf, sl, tp);
        if ("entry".equals(event) && properties.getTv().isMailEnabled()) {
            try {
                mailer.send("TV [" + strat + "] " + side + " " + symbol + " @ " + price + (tf == null ? "" : " " + tf),
                        "AlgoPro entry\n" + side + " " + symbol + " @ " + price
                                + (sl != null ? "\nSL " + sl : "") + (tp != null ? "\nTP " + tp : ""));
            } catch (RuntimeException ex) {
                log.warn("TV mail failed: {}", ex.getClass().getSimpleName());
            }
        }
        execution.submit(saved);
        return new Result(Status.ACCEPTED, "ok", saved.getId());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode j, String field) {
        JsonNode n = j.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Double number(JsonNode j, String field) {
        JsonNode n = j.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        try {
            return Double.parseDouble(n.asString().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double positive(Double d) {
        return d != null && d > 0 && !Double.isInfinite(d) ? d : null;
    }

    private static String lower(String s, String dflt) {
        return s == null ? dflt : s.toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
