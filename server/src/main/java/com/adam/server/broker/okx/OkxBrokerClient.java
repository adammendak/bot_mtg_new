package com.adam.server.broker.okx;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OKX (crypto exchange) adapter — REST v5.
 *
 * <p>Auth is per-request: every private call carries {@code OK-ACCESS-KEY},
 * {@code OK-ACCESS-SIGN} (Base64 HMAC-SHA256 of {@code timestamp+method+path+body}),
 * {@code OK-ACCESS-TIMESTAMP} and {@code OK-ACCESS-PASSPHRASE}; the demo flag adds
 * {@code x-simulated-trading: 1}. There is no session to hold, so
 * {@link #login()} only validates the credentials once and {@link #isSessionOpen()}
 * reports whether the last validation is still fresh.
 *
 * <p>Instruments are OKX SWAP (perpetual) ids like {@code BTC-USDT-SWAP}. Strategy
 * code keeps talking epics — for OKX the epic IS the instrument id. Sizing maps
 * OKX contract rules onto the shared {@link MarketRules}: size steps from
 * {@code lotSz}/{@code minSz}, price precision from {@code tickSz}, contract value
 * {@code ctVal} as the point value and {@code ctVal/lever} as the margin factor.
 */
public class OkxBrokerClient implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(OkxBrokerClient.class);
    private static final DateTimeFormatter OKX_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final long SESSION_TTL_MS = 10 * 60_000L;
    /**
     * Margin mode for every OKX order / close / SL amend. Isolated: a losing
     * position can only lose the margin allocated to it, never the rest of the
     * (small, real-money) account — no cross-position contagion.
     */
    private static final String MARGIN_MODE = "isolated";
    private static final int CANDLE_PAGE = 300;
    private static final int HISTORY_PAGE = 100;
    private static final long PAGE_SLEEP_MS = 150L;
    /** Roll the resolved contract to next-quarter once the front one is this close to expiry. */
    private static final long ROLL_BEFORE_DAYS = 14;
    /** How long a resolved front-quarter instId is reused before re-querying OKX. */
    private static final long CONTRACT_TTL_MS = 60 * 60_000L;
    /** Warn (via OkxRolloverWatcher) about an open position on a contract this close to expiry. */
    public static final long ROLL_WARN_DAYS = 7;

    private final String book;
    private final AppProperties.Okx endpoint;
    private final RestClient rest;
    private final String missingCredsMessage;

    /** underlying (BTC-USDT) → [resolved dated instId, resolvedAt epochMs]. */
    private final Map<String, Object[]> contractCache = new ConcurrentHashMap<>();

    private volatile long validatedAt = 0;

    /** ordId → instId for orders we placed, so confirm() can query details. */
    private final Map<String, String> ordToInst = new ConcurrentHashMap<>();
    private final Map<String, String> posToInst = new ConcurrentHashMap<>();

    public OkxBrokerClient(RestClient.Builder builder, String book,
                           AppProperties.Okx endpoint, String missingCredsMessage) {
        this.book = book;
        this.endpoint = endpoint;
        this.missingCredsMessage = missingCredsMessage;
        this.rest = builder.clone()
                .baseUrl(endpoint.getHost())
                .build();
    }

    @Override
    public String id() {
        return "okx";
    }

    @Override
    public String book() {
        return book;
    }

    @Override
    public String displayName() {
        return "OKX (" + (endpoint.isDemo() ? "demo" : "live") + ")";
    }

    @Override
    public boolean configured() {
        return endpoint.credentialsPresent();
    }

    @Override
    public void login() {
        if (!configured()) {
            throw new BrokerException(missingCredsMessage);
        }
        get("/api/v5/account/balance", Map.of(), true);
        validatedAt = System.currentTimeMillis();
        log.info("OKX {} credentials validated against {}", book, endpoint.getHost());
    }

    @Override
    public boolean isSessionOpen() {
        return System.currentTimeMillis() - validatedAt < SESSION_TTL_MS;
    }

    @Override
    public List<Account> accounts() {
        return OkxJson.accounts(get("/api/v5/account/balance", Map.of(), true), "OKX " + book);
    }

    @Override
    public List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max) {
        String bar = bar(resolution);
        long fromMs = from.toEpochMilli();
        long toMs = to.toEpochMilli();
        List<double[]> rows = new ArrayList<>();
        long cursor = toMs;
        int guard = 0;
        boolean history = false;
        while (guard++ < 100) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("instId", epic);
            q.put("bar", bar);
            q.put("after", Long.toString(cursor));
            q.put("limit", Integer.toString(history ? HISTORY_PAGE : CANDLE_PAGE));
            JsonNode root = get(history ? "/api/v5/market/history-candles" : "/api/v5/market/candles", q, false);
            List<double[]> page = OkxJson.candles(root, fromMs, toMs);
            if (page.isEmpty()) {
                if (!history) {
                    history = true; // recent window exhausted → older history endpoint
                    continue;
                }
                break;
            }
            rows.addAll(page);
            long oldest = (long) page.get(0)[0];
            if (oldest <= fromMs || page.size() < (history ? HISTORY_PAGE : CANDLE_PAGE)) {
                break;
            }
            cursor = oldest;
            sleep(PAGE_SLEEP_MS);
            if (!history && guard >= 4) {
                history = true; // deep lookback → history endpoint
            }
        }
        rows.sort((a, b) -> Double.compare(a[0], b[0]));
        List<Candle> out = new ArrayList<>();
        double prev = -1;
        for (double[] r : rows) {
            if (r[0] == prev) {
                continue;
            }
            prev = r[0];
            out.add(new Candle(
                    Instant.ofEpochMilli((long) r[0]),
                    r[1], r[2], r[3], r[4], r[5]
            ));
            if (out.size() >= Math.max(max, 1)) {
                break;
            }
        }
        return out;
    }

    @Override
    public MarketPrice marketPrice(String epic) {
        JsonNode root = get("/api/v5/market/ticker", Map.of("instId", epic), false);
        double bid = OkxJson.tickerBid(root);
        double ask = OkxJson.tickerAsk(root);
        if (bid <= 0 && ask <= 0) {
            throw new BrokerException("No OKX ticker for " + epic);
        }
        long ts = 0;
        try {
            ts = Long.parseLong(OkxJson.tickerTs(root));
        } catch (NumberFormatException ignored) {
            // leave EPOCH
        }
        return new MarketPrice(epic, bid, ask, Instant.ofEpochMilli(ts));
    }

    /**
     * An underlying ({@code BTC-USDT}) → the front-quarter dated future to trade
     * now; rolls to next-quarter once the front one is within
     * {@link #ROLL_BEFORE_DAYS} of expiry. A concrete {@code -YYMMDD} id is
     * returned unchanged. Cached {@link #CONTRACT_TTL_MS}; on any lookup failure
     * the (possibly stale) cached id is reused, else the input is returned.
     */
    @Override
    public String resolveEpic(String symbolOrUnderlying) {
        if (symbolOrUnderlying == null || symbolOrUnderlying.isBlank()
                || symbolOrUnderlying.matches(".*-\\d{6}$")) {
            return symbolOrUnderlying; // already a dated contract
        }
        String uly = symbolOrUnderlying;
        Object[] cached = contractCache.get(uly);
        if (cached != null && System.currentTimeMillis() - (long) cached[1] < CONTRACT_TTL_MS) {
            return (String) cached[0];
        }
        try {
            JsonNode root = get("/api/v5/public/instruments",
                    Map.of("instType", "FUTURES", "uly", uly), false);
            List<OkxJson.FutureInst> all = OkxJson.futures(root);
            long rollCutoff = System.currentTimeMillis() + ROLL_BEFORE_DAYS * 86_400_000L;
            OkxJson.FutureInst pick = null;
            for (OkxJson.FutureInst f : all) {           // sorted oldest-expiry first
                if (!"live".equalsIgnoreCase(f.state())) {
                    continue;
                }
                pick = f;
                if (f.expiryMs() > rollCutoff) {
                    break;                              // first contract with enough runway
                }
            }
            if (pick == null) {
                throw new BrokerException("no live FUTURES contract for " + uly);
            }
            contractCache.put(uly, new Object[]{pick.instId(), System.currentTimeMillis()});
            log.info("OKX {} contract for {}: {} (alias {}, {}d to expiry)",
                    book, uly, pick.instId(), pick.alias(), daysToExpiry(pick.instId()));
            return pick.instId();
        } catch (Exception e) {
            if (cached != null) {
                log.warn("OKX {} contract lookup failed for {} ({}) — reusing {}",
                        book, uly, e.getClass().getSimpleName(), cached[0]);
                return (String) cached[0];
            }
            log.warn("OKX {} contract lookup failed for {} ({}) — no cached id",
                    book, uly, e.getClass().getSimpleName());
            return symbolOrUnderlying;
        }
    }

    /** Days from now to the expiry encoded in a {@code ...-YYMMDD} instId; -1 if none. */
    public static long daysToExpiry(String instId) {
        if (instId == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-(\\d{2})(\\d{2})(\\d{2})$").matcher(instId);
        if (!m.find()) {
            return -1;
        }
        try {
            java.time.LocalDate exp = java.time.LocalDate.of(
                    2000 + Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            return java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(java.time.ZoneOffset.UTC), exp);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String instTypeOf(String instId) {
        return instId != null && instId.endsWith("-SWAP") ? "SWAP" : "FUTURES";
    }

    @Override
    public MarketRules marketRules(String epic) {
        try {
            JsonNode root = get("/api/v5/public/instruments",
                    Map.of("instType", instTypeOf(epic), "instId", epic), false);
            double tickSz = parse(OkxJson.instTickSz(root));
            double lotSz = parse(OkxJson.instLotSz(root));
            double minSz = parse(OkxJson.instMinSz(root));
            double ctVal = parse(OkxJson.instCtVal(root));
            double lever = parse(OkxJson.instLever(root));
            String state = OkxJson.instState(root);
            boolean tradeable = "live".equalsIgnoreCase(state);
            int dp = decimals(tickSz);
            double minDeal = minSz > 0 ? minSz : lotSz;
            double marginFactor = ctVal > 0 && lever > 0 ? ctVal / lever : 0;
            String ccy = OkxJson.instSettleCcy(root);
            if (ccy == null) {
                ccy = OkxJson.instQuoteCcy(root);
            }
            log.info("OKX {} rules {}: minDeal={} dp={} ctVal={} lever={} state={} ccy={}",
                    book, epic, minDeal, dp, ctVal, lever, state, ccy);
            return new MarketRules(epic, minDeal, dp, 0, 0, tradeable, marginFactor, ccy, ctVal, tickSz);
        } catch (Exception e) {
            log.warn("OKX {} market rules unavailable for {} ({}) — using permissive",
                    book, epic, e.getClass().getSimpleName());
            return MarketRules.permissive(epic);
        }
    }

    @Override
    public OrderAck placeWorkingOrder(OrderRequest request) {
        Map<String, Object> body = orderBody(request);
        body.put("ordType", request.type() == null ? "limit" : request.type());
        if (request.level() != null) {
            body.put("px", fmt(request.level()));
        }
        JsonNode root = post("/api/v5/trade/order", body);
        String ordId = OkxJson.orderId(root);
        if (ordId == null) {
            throw new BrokerException("OKX returned no order id for working order " + request.epic());
        }
        ordToInst.put(ordId, request.epic());
        return ack(ordId, root);
    }

    @Override
    public OrderAck amendWorkingOrder(String dealId, OrderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", request.epic());
        body.put("ordId", dealId);
        if (request.level() != null) {
            body.put("newPx", fmt(request.level()));
        }
        if (request.stopLevel() != null) {
            body.put("newSlTriggerPx", fmt(request.stopLevel()));
            body.put("newSlOrdPx", "-1");
        }
        if (request.profitLevel() != null) {
            body.put("newTpTriggerPx", fmt(request.profitLevel()));
            body.put("newTpOrdPx", "-1");
        }
        JsonNode root = post("/api/v5/trade/amend-order", body);
        return ack(dealId, root);
    }

    @Override
    public OrderAck closeWorkingOrder(String dealId) {
        String inst = ordToInst.get(dealId);
        if (inst == null) {
            throw new BrokerException("OKX cancel: unknown ordId " + dealId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", inst);
        body.put("ordId", dealId);
        JsonNode root = post("/api/v5/trade/cancel-order", body);
        return ack(dealId, root);
    }

    @Override
    public OrderAck placeMarketOrder(OrderRequest request) {
        Map<String, Object> body = orderBody(request);
        body.put("ordType", "market");
        if (request.stopLevel() != null) {
            Map<String, Object> sl = new LinkedHashMap<>();
            sl.put("slTriggerPx", fmt(request.stopLevel()));
            sl.put("slOrdPx", "-1"); // market SL
            body.put("attachAlgoOrds", List.of(sl));
        }
        JsonNode root = post("/api/v5/trade/order", body);
        String ordId = OkxJson.orderId(root);
        if (ordId == null) {
            throw new BrokerException("OKX returned no order id for market order " + request.epic());
        }
        ordToInst.put(ordId, request.epic());
        return ack(ordId, root);
    }

    @Override
    public OrderAck closePosition(String dealId, double size) {
        String inst = posToInst.get(dealId);
        if (inst == null) {
            inst = resolveInstForPos(dealId);
        }
        if (inst == null) {
            throw new BrokerException("OKX close: unknown position " + dealId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", inst);
        body.put("mgnMode", MARGIN_MODE);
        body.put("posSide", "net");
        if (size > 0) {
            body.put("sz", fmt(size));
        }
        JsonNode root = post("/api/v5/trade/close-position", body);
        String ordId = OkxJson.orderId(root);
        return new OrderAck(ordId == null ? dealId : ordId, dealId, "CLOSED");
    }

    @Override
    public OrderAck amendPosition(String dealId, Double stopLevel, boolean trailingStop) {
        String inst = posToInst.get(dealId);
        if (inst == null) {
            inst = resolveInstForPos(dealId);
        }
        if (inst == null) {
            throw new BrokerException("OKX amend: unknown position " + dealId);
        }
        if (stopLevel == null) {
            return new OrderAck(dealId, dealId, "NOOP");
        }
        String algoId = findSlAlgo(inst);
        if (algoId != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("instId", inst);
            body.put("algoId", algoId);
            body.put("newSlTriggerPx", fmt(stopLevel));
            body.put("newSlOrdPx", "-1");
            post("/api/v5/trade/amend-algos", body);
            return new OrderAck(dealId, dealId, "AMENDED");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", inst);
        body.put("tdMode", MARGIN_MODE);
        body.put("posSide", "net");
        body.put("ordType", "conditional");
        body.put("slTriggerPx", fmt(stopLevel));
        body.put("slOrdPx", "-1");
        post("/api/v5/trade/order-algo", body);
        return new OrderAck(dealId, dealId, "REATTACHED");
    }

    @Override
    public List<Position> openPositions() {
        JsonNode root = get("/api/v5/account/positions", Map.of(), true);
        List<Position> out = new ArrayList<>();
        for (OkxJson.OkxPosition p : OkxJson.positions(root)) {
            posToInst.put(p.posId(), p.instId());
            out.add(new Position(
                    p.posId(),
                    p.posId(),
                    p.instId(),
                    p.longPos() ? Direction.BUY : Direction.SELL,
                    Math.abs(p.pos()),
                    p.avgPx(),
                    null,
                    null,
                    p.upl(),
                    p.ccy(),
                    Instant.ofEpochMilli((long) p.uTimeMs())
            ));
        }
        return out;
    }

    @Override
    public Confirmation confirm(String dealReference) {
        String inst = ordToInst.get(dealReference);
        if (inst == null) {
            throw new BrokerException("OKX confirm: unknown order " + dealReference);
        }
        JsonNode root = get("/api/v5/trade/order",
                Map.of("instId", inst, "ordId", dealReference), true);
        String state = OkxJson.orderState(root);
        boolean accepted = "filled".equalsIgnoreCase(state) || "partially_filled".equalsIgnoreCase(state);
        double level = OkxJson.orderFillPx(root);
        if (level <= 0) {
            level = OkxJson.orderAvgPx(root);
        }
        Direction dir = "sell".equalsIgnoreCase(OkxJson.orderSide(root)) ? Direction.SELL : Direction.BUY;
        String posId = null;
        JsonNode posRoot = get("/api/v5/account/positions", Map.of("instId", inst), true);
        for (OkxJson.OkxPosition p : OkxJson.positions(posRoot)) {
            if (p.longPos() == (dir == Direction.BUY)) {
                posId = p.posId();
                posToInst.put(posId, inst);
                break;
            }
        }
        if (accepted && posId == null) {
            posId = dealReference; // fall back to the order id if the position lags
        }
        return new Confirmation(
                dealReference,
                posId,
                state,
                accepted ? "ACCEPTED" : state,
                accepted ? null : ("order state: " + state),
                inst,
                dir,
                level,
                OkxJson.orderFillSz(root)
        );
    }

    @Override
    public List<BrokerTransaction> transactionHistory(Instant from, Instant to) {
        return transactionHistory(from, to, null);
    }

    @Override
    public List<BrokerTransaction> transactionHistory(Instant from, Instant to, java.time.Duration budget) {
        long deadline = budget == null ? Long.MAX_VALUE : System.nanoTime() + budget.toNanos();
        List<BrokerTransaction> out = new ArrayList<>();
        // Closed SWAP positions carry the FULL net realised result (pnl+fee+funding)
        // per posId. This is the single source of truth for both the equity-history
        // backfill (one P/L row per position, no double-counting with fill bills)
        // and the HTS trade attribution (reference = posId == the trade's dealId).
        // OKX's posId survives ~30 days after the last full close, which covers the
        // history window this repo reconstructs.
        String after = null;
        for (int page = 0; page < 30; page++) {
            if (System.nanoTime() > deadline) {
                break;
            }
            Map<String, String> q = new LinkedHashMap<>();
            q.put("instType", "SWAP"); // the OKX book trades linear USDT perpetuals
            q.put("limit", "100");
            if (after != null) {
                q.put("after", after);
            }
            JsonNode posRoot = get("/api/v5/account/positions-history", q, true);
            List<OkxJson.ClosedPosition> got = OkxJson.closedPositions(posRoot);
            if (got.isEmpty()) {
                break;
            }
            for (OkxJson.ClosedPosition p : got) {
                long uTime;
                try {
                    uTime = Long.parseLong(p.uTimeMs());
                } catch (NumberFormatException e) {
                    continue;
                }
                Instant when = Instant.ofEpochMilli(uTime);
                if (when.isBefore(from) || when.isAfter(to)) {
                    continue;
                }
                out.add(new BrokerTransaction(when, "TRADE", p.instId(), p.realizedPnl(),
                        p.posId(), "OKX closed position"));
            }
            after = got.get(got.size() - 1).uTimeMs();
        }
        return out;
    }

    // ---- helpers ----

    private String resolveInstForPos(String posId) {
        JsonNode root = get("/api/v5/account/positions", Map.of(), true);
        for (OkxJson.OkxPosition p : OkxJson.positions(root)) {
            if (posId.equals(p.posId())) {
                posToInst.put(posId, p.instId());
                return p.instId();
            }
        }
        return null;
    }

    /** algoId of the live conditional SL attached to {@code inst}, or null. */
    private String findSlAlgo(String inst) {
        try {
            JsonNode root = get("/api/v5/trade/orders-algo-pending",
                    Map.of("instId", inst, "ordType", "conditional"), true);
            for (JsonNode row : OkxJson.algoRows(root)) {
                String state = OkxJson.algoState(row);
                if ("effective".equalsIgnoreCase(state) || "live".equalsIgnoreCase(state)) {
                    String id = OkxJson.algoId(row);
                    if (id != null && !id.isBlank()) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("OKX {} SL algo lookup failed for {} ({})", book, inst, e.getClass().getSimpleName());
        }
        return null;
    }

    private Map<String, Object> orderBody(OrderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", request.epic());
        body.put("tdMode", MARGIN_MODE);
        body.put("side", request.direction() == Direction.SELL ? "sell" : "buy");
        body.put("sz", fmt(request.size()));
        return body;
    }

    private static OrderAck ack(String dealId, JsonNode root) {
        String scode = OkxJson.orderSCode(root);
        boolean ok = scode == null || "0".equals(scode);
        return new OrderAck(dealId, dealId, ok ? "SUBMITTED" : "REJECTED");
    }

    // ---- HTTP ----

    /**
     * Signed or public GET. {@code query} is appended to the path and — for private
     * calls — included verbatim in the signature's requestPath, exactly as OKX
     * requires (the signature signs the path including its query string).
     */
    private JsonNode get(String path, Map<String, String> query, boolean privateCall) {
        try {
            String full = buildQuery(path, query);
            String raw = privateCall ? signedGet(full) : rest.get().uri(full).retrieve().body(String.class);
            return check(raw, path);
        } catch (RestClientResponseException e) {
            throw wrap(path, e);
        }
    }

    /** Signed POST with a JSON body. */
    private JsonNode post(String path, Map<String, Object> body) {
        try {
            String payload = body == null ? "{}" : toJson(body);
            return check(signedPost(path, payload), path);
        } catch (RestClientResponseException e) {
            throw wrap(path, e);
        }
    }

    private String signedGet(String fullPath) {
        String timestamp = OKX_TS.format(Instant.now());
        String sign = sign(timestamp, "GET", fullPath, "");
        var s = rest.get().uri(fullPath)
                .header("OK-ACCESS-KEY", endpoint.getApiKey())
                .header("OK-ACCESS-SIGN", sign)
                .header("OK-ACCESS-TIMESTAMP", timestamp)
                .header("OK-ACCESS-PASSPHRASE", endpoint.getPassphrase());
        if (endpoint.isDemo()) {
            s = s.header(demoHeader(), "1");
        }
        return s.retrieve().body(String.class);
    }

    private String signedPost(String fullPath, String payload) {
        String timestamp = OKX_TS.format(Instant.now());
        String sign = sign(timestamp, "POST", fullPath, payload);
        var s = rest.post().uri(fullPath)
                .header("OK-ACCESS-KEY", endpoint.getApiKey())
                .header("OK-ACCESS-SIGN", sign)
                .header("OK-ACCESS-TIMESTAMP", timestamp)
                .header("OK-ACCESS-PASSPHRASE", endpoint.getPassphrase())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
        if (endpoint.isDemo()) {
            s = s.header(demoHeader(), "1");
        }
        return s.retrieve().body(String.class);
    }

    /** OKX demo trading is signalled per request via {@code x-simulated-trading: 1}. */
    private String demoHeader() {
        return "x-simulated-trading";
    }

    private JsonNode check(String raw, String path) {
        JsonNode root = OkxJson.root(raw);
        if (!OkxJson.ok(root)) {
            throw new BrokerException("OKX " + path + " failed: code=" + str(root, "code")
                    + " msg=" + str(root, "msg"));
        }
        return root;
    }

    private static String str(JsonNode root, String name) {
        JsonNode n = root == null ? null : root.get(name);
        return n == null || n.isNull() ? "" : n.asString();
    }

    private static String buildQuery(String path, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private String sign(String timestamp, String method, String path, String body) {
        String prehash = timestamp + method + path + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(endpoint.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BrokerException("OKX signing failed", e);
        }
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new BrokerException("OKX payload serialization failed", e);
        }
    }

    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Decimal places implied by a tick size like {@code "0.1"} → 1, {@code "1"} → 0. */
    private static int decimals(double tick) {
        if (tick <= 0 || tick >= 1) {
            return 0;
        }
        String s = Double.toString(tick);
        int dot = s.indexOf('.');
        if (dot < 0) {
            return 0;
        }
        int d = s.length() - dot - 1;
        while (d > 0 && s.charAt(s.length() - 1) == '0') {
            d--;
        }
        return d;
    }

    private static String bar(Resolution r) {
        return switch (r) {
            case M5 -> "5m";
            case M15 -> "15m";
            case H1 -> "1H";
            case H4 -> "4H";
            case D1 -> "1Dutc";
        };
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static BrokerException wrap(String path, RestClientResponseException e) {
        String extra = "";
        try {
            extra = " " + str(OkxJson.root(e.getResponseBodyAsString()), "msg");
        } catch (Exception ignored) {
            // no body
        }
        return new BrokerException("OKX " + path + " failed: HTTP " + e.getStatusCode().value() + extra, e);
    }
}
