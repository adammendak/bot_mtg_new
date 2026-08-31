package com.adam.server.broker.okx;

import com.adam.server.broker.model.Account;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * OKX REST v5 payloads. Kept in this package so strategy/UI never see them.
 * Parsing is lenient (JsonNode) because OKX returns strings for every number
 * and omits fields it has nothing to say about.
 */
final class OkxJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OkxJson() {
    }

    static JsonNode root(String raw) {
        if (raw == null || raw.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("OKX JSON could not be parsed: " + raw, e);
        }
    }

    /** The {@code data} array of an OKX response, empty when absent. */
    static JsonNode data(JsonNode root) {
        JsonNode d = root == null ? null : root.get("data");
        return d == null || d.isNull() || d.isMissingNode() || !d.isArray() ? JSON.createArrayNode() : d;
    }

    /** OKX {@code code}: {@code "0"} = success. */
    static boolean ok(JsonNode root) {
        return root != null && "0".equals(stringOf(root.get("code")));
    }

    /** The first element of {@code data}, or a missing node. */
    static JsonNode first(JsonNode root) {
        JsonNode d = data(root);
        return d.isEmpty() ? JSON.createObjectNode() : d.get(0);
    }

    // ---- candles ----
    // data: [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]  (newest first)

    /** @return candles ascending by time, filtered to {@code [from, to]}. */
    static List<double[]> candles(JsonNode root, long fromMs, long toMs) {
        List<double[]> out = new ArrayList<>();
        for (JsonNode n : data(root)) {
            if (n == null || !n.isArray() || n.size() < 6) {
                continue;
            }
            // The last row OKX returns is the CURRENT (still forming) candle:
            // confirm=0 → not yet closed. HTS evaluates only closed bars, so drop it.
            if (n.size() > 8) {
                String confirm = n.get(8) == null ? null : n.get(8).asString();
                if ("0".equals(confirm)) {
                    continue;
                }
            }
            double ts = num(n.get(0));
            if (ts < fromMs || ts > toMs) {
                continue;
            }
            out.add(new double[]{
                    ts,
                    num(n.get(1)), // o
                    num(n.get(2)), // h
                    num(n.get(3)), // l
                    num(n.get(4)), // c
                    num(n.get(5))  // vol
            });
        }
        out.sort((a, b) -> Double.compare(a[0], b[0]));
        return out;
    }

    // ---- ticker ----
    static double tickerBid(JsonNode root) {
        return num(first(root).get("bidPx"));
    }

    static double tickerAsk(JsonNode root) {
        return num(first(root).get("askPx"));
    }

    static String tickerTs(JsonNode root) {
        return stringOf(first(root).get("ts"));
    }

    // ---- balance / accounts ----
    static List<Account> accounts(JsonNode root, String bookLabel) {
        JsonNode first = first(root);
        String ccy = stringOf(first.get("details"));
        double totalEq = num(first.get("totalEq"));
        double availEq = num(first.get("availEq"));
        double upl = num(first.get("upl"));
        JsonNode details = first.get("details");
        if (details != null && details.isArray() && !details.isEmpty()) {
            JsonNode d = details.get(0);
            ccy = stringOf(d.get("ccy"));
        }
        List<Account> out = new ArrayList<>();
        out.add(new Account("trading", bookLabel, ccy == null ? "USDT" : ccy,
                totalEq, availEq, upl, true));
        return out;
    }

    // ---- instruments → market rules ----
    static String instTickSz(JsonNode root) {
        return stringOf(first(root).get("tickSz"));
    }

    static String instLotSz(JsonNode root) {
        return stringOf(first(root).get("lotSz"));
    }

    static String instMinSz(JsonNode root) {
        return stringOf(first(root).get("minSz"));
    }

    static String instCtVal(JsonNode root) {
        return stringOf(first(root).get("ctVal"));
    }

    static String instState(JsonNode root) {
        return stringOf(first(root).get("state"));
    }

    static String instLever(JsonNode root) {
        return stringOf(first(root).get("lever"));
    }

    static String instSettleCcy(JsonNode root) {
        return stringOf(first(root).get("settleCcy"));
    }

    static String instQuoteCcy(JsonNode root) {
        return stringOf(first(root).get("quoteCcy"));
    }

    static String instCtType(JsonNode root) {
        return stringOf(first(root).get("ctType"));
    }

    // ---- positions ----
    // data[]: posId, instId, pos (signed, + long / − short), avgPx, upl, ccy, uTime ...

    static List<OkxPosition> positions(JsonNode root) {
        List<OkxPosition> out = new ArrayList<>();
        for (JsonNode n : data(root)) {
            String posId = stringOf(n.get("posId"));
            String instId = stringOf(n.get("instId"));
            double pos = num(n.get("pos"));
            if (posId == null || instId == null) {
                continue;
            }
            out.add(new OkxPosition(
                    posId,
                    instId,
                    pos,
                    num(n.get("avgPx")),
                    num(n.get("upl")),
                    stringOf(n.get("ccy")),
                    num(n.get("uTime"))
            ));
        }
        return out;
    }

    record OkxPosition(String posId, String instId, double pos, double avgPx, double upl,
                       String ccy, double uTimeMs) {
        boolean longPos() {
            return pos > 0;
        }
    }

    // ---- order ack ----
    static String orderId(JsonNode root) {
        return stringOf(first(root).get("ordId"));
    }

    static String orderSCode(JsonNode root) {
        return stringOf(first(root).get("sCode"));
    }

    // ---- order detail (confirm) ----
    static String orderState(JsonNode root) {
        return stringOf(first(root).get("state"));
    }

    static double orderAvgPx(JsonNode root) {
        return num(first(root).get("avgPx"));
    }

    static double orderFillPx(JsonNode root) {
        return num(first(root).get("fillPx"));
    }

    static double orderFillSz(JsonNode root) {
        return num(first(root).get("fillSz"));
    }

    static String orderSide(JsonNode root) {
        return stringOf(first(root).get("side"));
    }

    // ---- algo (SL/TP) ----
    static String algoId(JsonNode root) {
        return stringOf(first(root).get("algoId"));
    }

    static String algoState(JsonNode root) {
        return stringOf(first(root).get("state"));
    }

    static List<JsonNode> algoRows(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : data(root)) {
            out.add(n);
        }
        return out;
    }

    // ---- positions history (realized P/L per closed position) ----

    static List<ClosedPosition> closedPositions(JsonNode root) {
        List<ClosedPosition> out = new ArrayList<>();
        for (JsonNode n : data(root)) {
            String posId = stringOf(n.get("posId"));
            String instId = stringOf(n.get("instId"));
            String uTime = stringOf(n.get("uTime"));
            if (posId == null || instId == null || uTime == null) {
                continue;
            }
            out.add(new ClosedPosition(
                    posId,
                    instId,
                    num(n.get("realizedPnl")),
                    num(n.get("closeTotalPos")),
                    uTime
            ));
        }
        return out;
    }

    record ClosedPosition(String posId, String instId, double realizedPnl, double closeTotalPos,
                          String uTimeMs) {
    }

    private static double num(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return 0;
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        String raw = stringOf(n);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String stringOf(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isTextual()) {
            String v = n.asString();
            return v == null || v.isBlank() ? null : v;
        }
        return n.isValueNode() ? n.asString() : n.toString();
    }
}
