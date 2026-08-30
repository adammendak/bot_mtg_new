package com.adam.server.broker.capital;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Live Capital.com adapter. Tokens stay in memory; credentials are never logged.
 */
public class CapitalComBrokerClient implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(CapitalComBrokerClient.class);
    private static final DateTimeFormatter CAPITAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RestClient restClient;
    private final AppProperties.Endpoint endpoint;
    private final String book;
    private final String missingCredsMessage;

    private volatile String cst;
    private volatile String securityToken;
    private volatile Instant sessionAt = Instant.EPOCH;

    public CapitalComBrokerClient(
            RestClient.Builder builder,
            String book,
            AppProperties.Endpoint endpoint,
            String missingCredsMessage
    ) {
        this.book = book;
        this.endpoint = endpoint;
        this.missingCredsMessage = missingCredsMessage;
        this.restClient = builder.clone()
                .baseUrl(endpoint.getHost())
                .build();
    }

    @Override
    public String id() {
        return "capital";
    }

    @Override
    public String book() {
        return book;
    }

    @Override
    public String displayName() {
        return "live".equals(book) ? "Capital.com (live)" : "Capital.com (demo)";
    }

    @Override
    public boolean configured() {
        return endpoint.credentialsPresent();
    }

    @Override
    public synchronized void login() {
        if (!endpoint.credentialsPresent()) {
            throw new BrokerException(missingCredsMessage);
        }
        try {
            ResponseEntity<String> entity = restClient.post()
                    .uri("/api/v1/session")
                    .header("X-CAP-API-KEY", endpoint.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CapitalJson.SessionRequest(endpoint.getEmail(), endpoint.getPassword(), false))
                    .retrieve()
                    .toEntity(String.class);
            this.cst = firstHeader(entity, "CST");
            this.securityToken = firstHeader(entity, "X-SECURITY-TOKEN");
            if (cst == null || securityToken == null) {
                throw new BrokerException("Capital.com session did not return CST / X-SECURITY-TOKEN");
            }
            this.sessionAt = Instant.now();
            log.info("Capital.com {} session opened against {}", book, endpoint.getHost());
        } catch (RestClientResponseException e) {
            log.warn("Capital.com {} login failed: HTTP {}", book, e.getStatusCode().value());
            throw new BrokerException("Capital.com " + book + " login failed", e);
        }
    }

    @Override
    public boolean isSessionOpen() {
        return cst != null && securityToken != null && sessionAt.plusSeconds(9 * 60).isAfter(Instant.now());
    }

    @Override
    public List<Account> accounts() {
        try {
            // String body so text/plain (Capital session-style) still parses; typed
            // AccountsResponse.class rejects non-JSON content types and missing
            // record fields like preferred/balance.
            String raw = authed()
                    .get()
                    .uri("/api/v1/accounts")
                    .retrieve()
                    .body(String.class);
            return CapitalJson.parseAccounts(raw);
        } catch (RestClientResponseException e) {
            throw wrap("accounts", e);
        } catch (BrokerException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Capital.com {} accounts failed: {}", book, e.getClass().getSimpleName());
            throw new BrokerException("Capital.com " + book + " accounts failed: " + publicMessage(e), e);
        }
    }

    @Override
    public void selectAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return;
        }
        try {
            ResponseEntity<String> entity = authed()
                    .put()
                    .uri("/api/v1/session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("accountId", accountId))
                    .retrieve()
                    .toEntity(String.class);
            String newCst = firstHeader(entity, "CST");
            String newToken = firstHeader(entity, "X-SECURITY-TOKEN");
            if (newCst != null) {
                this.cst = newCst;
            }
            if (newToken != null) {
                this.securityToken = newToken;
            }
        } catch (RestClientResponseException e) {
            // "error.not-different.accountId" = the account is already the active
            // one. That is success, not a failure — Capital just rejects the no-op
            // PUT. Don't log-spam / throw for it (it fired every 30s from the SSE
            // overview refresh).
            if (e.getStatusCode().value() == 400
                    && CapitalJson.errorCode(e.getResponseBodyAsString()).contains("not-different.accountId")) {
                return;
            }
            throw wrap("selectAccount", e);
        } catch (BrokerException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Capital.com {} selectAccount failed: {}", book, e.getClass().getSimpleName());
            throw new BrokerException("Capital.com " + book + " selectAccount failed: " + publicMessage(e), e);
        }
    }

    @Override
    public List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max) {
        String capRes = switch (resolution) {
            case M5 -> "MINUTE_5";
            case M15 -> "MINUTE_15";
            case H1 -> "HOUR";
            case H4 -> "HOUR_4";
            case D1 -> "DAY";
        };
        CapitalJson.PricesResponse body;
        try {
            body = authed()
                    .get()
                    .uri(uri -> uri.path("/api/v1/prices/{epic}")
                            .queryParam("resolution", capRes)
                            .queryParam("max", Math.min(Math.max(max, 1), 1000))
                            .queryParam("from", CAPITAL_TIME.format(LocalDateTime.ofInstant(from, ZoneOffset.UTC)))
                            .queryParam("to", CAPITAL_TIME.format(LocalDateTime.ofInstant(to, ZoneOffset.UTC)))
                            .build(epic))
                    .retrieve()
                    .body(CapitalJson.PricesResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || CapitalJson.isNotFoundEpic(e.getResponseBodyAsString())) {
                log.warn("Capital.com {} candles 404 for epic {}", book, epic);
                throw new BrokerException("Capital.com epic not found: " + epic, e);
            }
            throw wrap("candles " + epic, e);
        }
        if (body == null || body.prices() == null) {
            return List.of();
        }
        List<Candle> candles = new ArrayList<>();
        for (CapitalJson.PriceJson p : body.prices()) {
            Instant time = parseUtc(p.snapshotTimeUTC() != null ? p.snapshotTimeUTC() : p.snapshotTime());
            candles.add(new Candle(
                    time,
                    mid(p.openPrice()),
                    mid(p.highPrice()),
                    mid(p.lowPrice()),
                    mid(p.closePrice()),
                    p.lastTradedVolume() == null ? 0 : p.lastTradedVolume()
            ));
        }
        return candles;
    }

    @Override
    public MarketPrice marketPrice(String epic) {
        CapitalJson.MarketResponse body = authed()
                .get()
                .uri("/api/v1/markets/{epic}", epic)
                .retrieve()
                .body(CapitalJson.MarketResponse.class);
        if (body == null || body.snapshot() == null) {
            throw new BrokerException("No market snapshot for " + epic);
        }
        CapitalJson.SnapshotJson s = body.snapshot();
        Instant updated = parseUtc(s.updateTimeUTC());
        double bid = nz(s.bid());
        double ask = nz(s.askOrOffer());
        return new MarketPrice(epic, bid, ask, updated);
    }

    @Override
    public OrderAck placeWorkingOrder(OrderRequest request) {
        Map<String, Object> payload = workingPayload(request);
        CapitalJson.DealReferenceResponse body = authed()
                .post()
                .uri("/api/v1/workingorders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(CapitalJson.DealReferenceResponse.class);
        return ack(body);
    }

    @Override
    public OrderAck amendWorkingOrder(String dealId, OrderRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.level() != null) {
            payload.put("level", request.level());
        }
        if (request.stopLevel() != null) {
            payload.put("stopLevel", request.stopLevel());
        }
        if (request.profitLevel() != null) {
            payload.put("profitLevel", request.profitLevel());
        }
        payload.put("trailingStop", request.trailingStop());
        CapitalJson.DealReferenceResponse body = authed()
                .put()
                .uri("/api/v1/workingorders/{dealId}", dealId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(CapitalJson.DealReferenceResponse.class);
        return ack(body);
    }

    @Override
    public OrderAck closeWorkingOrder(String dealId) {
        CapitalJson.DealReferenceResponse body = authed()
                .delete()
                .uri("/api/v1/workingorders/{dealId}", dealId)
                .retrieve()
                .body(CapitalJson.DealReferenceResponse.class);
        return ack(body);
    }

    @Override
    public OrderAck placeMarketOrder(OrderRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("epic", request.epic());
        payload.put("direction", request.direction().name());
        payload.put("size", request.size());
        if (request.stopLevel() != null) {
            payload.put("stopLevel", request.stopLevel());
        }
        CapitalJson.DealReferenceResponse body = authed()
                .post()
                .uri("/api/v1/positions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(CapitalJson.DealReferenceResponse.class);
        return ack(body);
    }

    @Override
    public OrderAck closePosition(String dealId, double size) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (size > 0) {
            payload.put("size", size);
        }
        CapitalJson.DealReferenceResponse body = authed()
                .delete()
                .uri(uri -> {
                    var b = uri.path("/api/v1/positions/{dealId}");
                    if (size > 0) {
                        b = b.queryParam("size", size);
                    }
                    return b.build(dealId);
                })
                .retrieve()
                .body(CapitalJson.DealReferenceResponse.class);
        return ack(body);
    }

    @Override
    public OrderAck amendPosition(String dealId, Double stopLevel, boolean trailingStop) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (stopLevel != null) {
            payload.put("stopLevel", stopLevel);
        }
        payload.put("trailingStop", trailingStop);
        CapitalJson.DealReferenceResponse body = authed()
                .put()
                .uri("/api/v1/positions/{dealId}", dealId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(CapitalJson.DealReferenceResponse.class);
        return ack(body);
    }

    @Override
    public List<BrokerTransaction> transactionHistory(Instant from, Instant to) {
        return transactionHistory(from, to, (java.time.Duration) null);
    }

    @Override
    public List<BrokerTransaction> transactionHistory(Instant from, Instant to, java.time.Duration budget) {
        // Capital.com caps a single broad query to ~100 newest rows and can echo
        // duplicates when paging. So the FULL range (from..to) is walked in
        // 7-day chunks: every chunk returns complete data, and together the
        // chunks cover the whole history, not just the last 7 days.
        //
        // When a budget is given (HTTP-triggered sync), stop walking once it is
        // spent and return what we have — a rate-limiting broker must not make
        // the request outlast Heroku's 30 s router timeout.
        long deadline = budget == null ? Long.MAX_VALUE : System.nanoTime() + budget.toNanos();
        List<BrokerTransaction> out = new ArrayList<>();
        java.time.ZonedDateTime cursor = java.time.ZonedDateTime.ofInstant(from, java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime end = java.time.ZonedDateTime.ofInstant(to, java.time.ZoneOffset.UTC);
        int guard = 0;
        while (!cursor.isAfter(end) && guard < 4000) {
            if (System.nanoTime() > deadline) {
                log.warn("Capital.com {} transactions: {} budget spent at {} ({} rows so far); stopping walk",
                        book, budget, cursor.toLocalDate(), out.size());
                break;
            }
            java.time.ZonedDateTime next = cursor.plusDays(7);
            java.time.ZonedDateTime toDay = next.isAfter(end) ? end : next;
            List<BrokerTransaction> window;
            try {
                window = fetchTxWindow(cursor, toDay);
            } catch (BrokerException e) {
                // The FIRST window failing means bad creds / no session / a config
                // problem — surface that so the sync does not silently write nothing.
                // A LATER window failing (rate limit, session expiry mid-run, 5xx)
                // must not discard the rows we already have: log and stop with what
                // we've gathered.
                if (out.isEmpty()) {
                    throw e;
                }
                log.warn("Capital.com {} transactions: window {}..{} failed ({}); returning {} rows gathered so far",
                        book, cursor.toLocalDate(), toDay.toLocalDate(), e.getMessage(), out.size());
                break;
            }
            out.addAll(window);
            cursor = next;
            guard++;
        }
        java.util.LinkedHashMap<String, BrokerTransaction> byRef = new java.util.LinkedHashMap<>();
        for (BrokerTransaction t : out) {
            if (t.reference() != null && !t.reference().isBlank()) {
                byRef.putIfAbsent(t.reference(), t);
            } else {
                byRef.putIfAbsent(t.time() + "|" + t.type() + "|" + t.amount(), t);
            }
        }
        return new ArrayList<>(byRef.values());
    }

    private List<BrokerTransaction> fetchTxWindow(java.time.ZonedDateTime from, java.time.ZonedDateTime to) {
        String fromStr = CAPITAL_TIME.format(from.toLocalDateTime());
        String toStr = CAPITAL_TIME.format(to.toLocalDateTime());
        List<BrokerTransaction> window = new ArrayList<>();
        int page = 0;
        while (page <= TX_MAX_PAGES) {
            List<BrokerTransaction> pageTx = fetchTxPage(fromStr, toStr, page);
            window.addAll(pageTx);
            if (pageTx.size() < TX_PAGE_SIZE) {
                break;
            }
            page++;
        }
        return window;
    }

    private List<BrokerTransaction> fetchTxPage(String from, String to, int page) {
        for (int attempt = 0; ; attempt++) {
            try {
                String raw = authed()
                        .get()
                        .uri(uri -> uri.path("/api/v1/history/transactions")
                                .queryParam("from", from)
                                .queryParam("to", to)
                                .queryParam("pageSize", TX_PAGE_SIZE)
                                .queryParam("page", page)
                                .build())
                        .retrieve()
                        .body(String.class);
                return CapitalJson.parseTransactions(raw);
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                if (code == 400) {
                    log.warn("Capital.com {} transactions rejected range {}..{} (no data or bad range)",
                            book, from, to);
                    return List.of();
                }
                if (code == 429 && attempt < TX_RATE_LIMIT_RETRIES) {
                    log.warn("Capital.com {} transactions rate-limited (429), retry {}/{}",
                            book, attempt + 1, TX_RATE_LIMIT_RETRIES);
                    if (!backoff(attempt)) {
                        throw wrap("transactions", e);
                    }
                    continue;
                }
                throw wrap("transactions", e);
            } catch (org.springframework.web.client.ResourceAccessException e) {
                // Transient I/O to Capital (connection reset / read timeout / DNS).
                if (attempt < TX_RATE_LIMIT_RETRIES) {
                    log.warn("Capital.com {} transactions I/O error ({}), retry {}/{}",
                            book, e.getMessage(), attempt + 1, TX_RATE_LIMIT_RETRIES);
                    if (backoff(attempt)) {
                        continue;
                    }
                }
                throw new BrokerException("Capital.com " + book + " transactions I/O error", e);
            }
        }
    }

    /** Sleeps {@code TX_RATE_LIMIT_BACKOFF_MS * (attempt + 1)}; false if interrupted. */
    private static boolean backoff(int attempt) {
        try {
            Thread.sleep(TX_RATE_LIMIT_BACKOFF_MS * (attempt + 1L));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final int TX_PAGE_SIZE = 50;
    private static final int TX_MAX_PAGES = 20;
    private static final int TX_RATE_LIMIT_RETRIES = 3;
    private static final long TX_RATE_LIMIT_BACKOFF_MS = 800L;

    @Override
    public List<Position> openPositions() {
        CapitalJson.PositionsResponse body = authed()
                .get()
                .uri("/api/v1/positions")
                .retrieve()
                .body(CapitalJson.PositionsResponse.class);
        if (body == null || body.positions() == null) {
            return List.of();
        }
        List<Position> out = new ArrayList<>();
        for (CapitalJson.PositionWrapper w : body.positions()) {
            CapitalJson.PositionBody p = w.position();
            if (p == null) {
                continue;
            }
            String epic = w.market() == null ? "" : w.market().epic();
            out.add(new Position(
                    p.dealId(),
                    p.dealReference(),
                    epic,
                    Direction.valueOf(p.direction()),
                    nz(p.size()),
                    nz(p.level()),
                    p.stopLevel(),
                    p.profitLevel(),
                    nz(p.upl()),
                    p.currency(),
                    parseUtc(p.createdDateUTC())
            ));
        }
        return out;
    }

    @Override
    public Confirmation confirm(String dealReference) {
        CapitalJson.ConfirmResponse body = authed()
                .get()
                .uri("/api/v1/confirms/{dealReference}", dealReference)
                .retrieve()
                .body(CapitalJson.ConfirmResponse.class);
        if (body == null) {
            throw new BrokerException("No confirmation for " + dealReference);
        }
        Direction direction = body.direction() == null ? null : Direction.valueOf(body.direction());
        return new Confirmation(
                body.dealReference(),
                body.dealId(),
                body.status(),
                body.dealStatus(),
                body.epic(),
                direction,
                body.level(),
                body.size()
        );
    }

    private RestClient authed() {
        ensureSession();
        return restClient.mutate()
                .defaultHeader("CST", cst)
                .defaultHeader("X-SECURITY-TOKEN", securityToken)
                .defaultHeader("X-CAP-API-KEY", endpoint.getApiKey() == null ? "" : endpoint.getApiKey())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private BrokerException wrap(String action, RestClientResponseException e) {
        String code = CapitalJson.errorCode(e.getResponseBodyAsString());
        String extra = code.isBlank() ? "" : " " + code;
        log.warn("Capital.com {} {} failed: HTTP {}{}", book, action, e.getStatusCode().value(), extra);
        return new BrokerException(
                "Capital.com " + book + " " + action + " failed: HTTP " + e.getStatusCode().value() + extra,
                e
        );
    }

    private static String publicMessage(Throwable e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        if (e.getCause() != null && e.getCause().getMessage() != null && !e.getCause().getMessage().isBlank()) {
            return e.getCause().getMessage();
        }
        return e.getClass().getSimpleName();
    }

    private synchronized void ensureSession() {
        if (!isSessionOpen()) {
            login();
        }
    }

    private static String firstHeader(ResponseEntity<?> entity, String name) {
        List<String> values = entity.getHeaders().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    private static Map<String, Object> workingPayload(OrderRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("epic", request.epic());
        payload.put("direction", request.direction().name());
        payload.put("size", request.size());
        payload.put("level", Objects.requireNonNull(request.level(), "working order level"));
        payload.put("type", request.type() == null ? "LIMIT" : request.type());
        if (request.stopLevel() != null) {
            payload.put("stopLevel", request.stopLevel());
        }
        if (request.stopDistance() != null) {
            payload.put("stopDistance", request.stopDistance());
        }
        if (request.profitLevel() != null) {
            payload.put("profitLevel", request.profitLevel());
        }
        payload.put("trailingStop", request.trailingStop());
        return payload;
    }

    private static OrderAck ack(CapitalJson.DealReferenceResponse body) {
        if (body == null || body.dealReference() == null) {
            throw new BrokerException("Capital.com returned an empty deal reference");
        }
        return new OrderAck(body.dealReference(), null, "SUBMITTED");
    }

    private static double mid(CapitalJson.BidAsk pair) {
        if (pair == null) {
            return Double.NaN;
        }
        if (pair.bid() != null && pair.ask() != null) {
            return (pair.bid() + pair.ask()) / 2.0;
        }
        if (pair.bid() != null) {
            return pair.bid();
        }
        return pair.ask() == null ? Double.NaN : pair.ask();
    }

    private static Instant parseUtc(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.EPOCH;
        }
        String trimmed = raw.trim().replace(' ', 'T');
        if (trimmed.length() > 19) {
            trimmed = trimmed.substring(0, 19);
        }
        return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
