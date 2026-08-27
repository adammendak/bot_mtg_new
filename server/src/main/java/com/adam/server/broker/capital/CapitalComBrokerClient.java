package com.adam.server.broker.capital;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Account;
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
    private final AppProperties.Capital capital;

    private volatile String cst;
    private volatile String securityToken;
    private volatile Instant sessionAt = Instant.EPOCH;

    public CapitalComBrokerClient(RestClient.Builder builder, AppProperties properties) {
        this.capital = properties.getCapital();
        this.restClient = builder.clone()
                .baseUrl(capital.host())
                .build();
    }

    @Override
    public String id() {
        return "capital";
    }

    @Override
    public String displayName() {
        return capital.isLive() ? "Capital.com (live)" : "Capital.com (demo)";
    }

    @Override
    public synchronized void login() {
        if (!capital.credentialsPresent()) {
            throw new BrokerException("Capital.com credentials are not set (CAPITAL_API_KEY, CAPITAL_EMAIL, CAPITAL_API_PASSWORD)");
        }
        try {
            ResponseEntity<String> entity = restClient.post()
                    .uri("/api/v1/session")
                    .header("X-CAP-API-KEY", capital.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CapitalJson.SessionRequest(capital.getEmail(), capital.getPassword(), false))
                    .retrieve()
                    .toEntity(String.class);
            this.cst = firstHeader(entity, "CST");
            this.securityToken = firstHeader(entity, "X-SECURITY-TOKEN");
            if (cst == null || securityToken == null) {
                throw new BrokerException("Capital.com session did not return CST / X-SECURITY-TOKEN");
            }
            this.sessionAt = Instant.now();
            log.info("Capital.com session opened against {}", capital.host());
        } catch (RestClientResponseException e) {
            log.warn("Capital.com login failed: HTTP {}", e.getStatusCode().value());
            throw new BrokerException("Capital.com login failed", e);
        }
    }

    @Override
    public boolean isSessionOpen() {
        return cst != null && securityToken != null && sessionAt.plusSeconds(9 * 60).isAfter(Instant.now());
    }

    @Override
    public List<Account> accounts() {
        CapitalJson.AccountsResponse body = authed()
                .get()
                .uri("/api/v1/accounts")
                .retrieve()
                .body(CapitalJson.AccountsResponse.class);
        if (body == null || body.accounts() == null) {
            return List.of();
        }
        List<Account> out = new ArrayList<>();
        for (CapitalJson.AccountJson a : body.accounts()) {
            CapitalJson.BalanceJson b = a.balance();
            out.add(new Account(
                    a.accountId(),
                    a.accountName(),
                    a.currency(),
                    nz(b == null ? null : b.balance()),
                    nz(b == null ? null : b.available()),
                    nz(b == null ? null : b.profitLoss()),
                    a.preferred()
            ));
        }
        return out;
    }

    @Override
    public List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max) {
        String capRes = switch (resolution) {
            case M15 -> "MINUTE_15";
            case H1 -> "HOUR";
            case H4 -> "HOUR_4";
            case D1 -> "DAY";
        };
        CapitalJson.PricesResponse body = authed()
                .get()
                .uri(uri -> uri.path("/api/v1/prices/{epic}")
                        .queryParam("resolution", capRes)
                        .queryParam("max", Math.min(Math.max(max, 1), 1000))
                        .queryParam("from", CAPITAL_TIME.format(LocalDateTime.ofInstant(from, ZoneOffset.UTC)))
                        .queryParam("to", CAPITAL_TIME.format(LocalDateTime.ofInstant(to, ZoneOffset.UTC)))
                        .build(epic))
                .retrieve()
                .body(CapitalJson.PricesResponse.class);
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
                .build();
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
