package com.adam.server.broker.capital;

import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.BrokerTransaction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Capital.com REST payloads. Kept in this package so strategy/UI never see them.
 */
final class CapitalJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CapitalJson() {
    }

    /** Parse a {@code /confirms/{ref}} body; caller logs the raw string on reject. */
    static ConfirmResponse parseConfirm(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(raw, ConfirmResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Capital.com confirm JSON could not be parsed: " + raw, e);
        }
    }

    /** Parse a {@code /markets/{epic}} body. */
    static MarketResponse parseMarket(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(raw, MarketResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Capital.com market JSON could not be parsed: " + raw, e);
        }
    }

    static List<Account> parseAccounts(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Capital.com accounts JSON could not be parsed", e);
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        JsonNode accounts = root.get("accounts");
        if (accounts == null || accounts.isNull() || accounts.isMissingNode()) {
            if (root.isArray()) {
                accounts = root;
            } else {
                return List.of();
            }
        }
        if (!accounts.isArray()) {
            return List.of();
        }
        List<Account> out = new ArrayList<>();
        for (JsonNode n : accounts) {
            if (n == null || n.isNull() || !n.isObject()) {
                continue;
            }
            JsonNode balance = n.get("balance");
            if (balance == null || balance.isNull() || !balance.isObject()) {
                balance = n.get("accountInfo");
            }
            out.add(new Account(
                    text(n, "accountId", "id"),
                    text(n, "accountName", "name"),
                    text(n, "currency", "currencyIsoCode"),
                    num(balance, "balance"),
                    num(balance, "available"),
                    num(balance, "profitLoss"),
                    bool(n, "preferred")
            ));
        }
        return out;
    }

    static String errorCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(raw);
            String code = text(node, "errorCode", "error");
            return code == null ? "" : code;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Parse the transactions history response ({@code transactions: [...]}).
     * Each item carries {@code date}, {@code transactionType}, {@code size} (cash
     * amount in account currency; P/L for TRADE, +/- for DEPOSIT/WITHDRAWAL,
     * cost for SWAP), {@code instrumentName}, {@code reference}, {@code note}.
     */
    static List<BrokerTransaction> parseTransactions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Capital.com transactions JSON could not be parsed", e);
        }
        JsonNode tx = root == null ? null : root.get("transactions");
        if (tx == null || !tx.isArray()) {
            return List.of();
        }
        List<BrokerTransaction> out = new ArrayList<>();
        for (JsonNode n : tx) {
            if (n == null || n.isNull() || !n.isObject()) {
                continue;
            }
            Instant time = parseTxTime(text(n, "dateUtc", "date"));
            if (time == null) {
                continue;
            }
            String type = text(n, "transactionType", "type");
            String sizeRaw = stringOf(n.get("size"));
            double amount = 0;
            if (sizeRaw != null && !sizeRaw.isBlank()) {
                try {
                    amount = Double.parseDouble(sizeRaw);
                } catch (NumberFormatException ignored) {
                    // size may be non-numeric for some types
                }
            }
            out.add(new BrokerTransaction(
                    time,
                    type == null ? "UNKNOWN" : type,
                    text(n, "instrumentName", "epic"),
                    amount,
                    text(n, "reference", "dealId"),
                    text(n, "note")
            ));
        }
        return out;
    }

    private static Instant parseTxTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().replace(' ', 'T');
        if (trimmed.endsWith("Z")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.length() > 19) {
            trimmed = trimmed.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isNotFoundEpic(String raw) {
        String code = errorCode(raw);
        if (code.contains("not-found.epic") || code.contains("error.not-found")) {
            return true;
        }
        return raw != null && raw.contains("error.not-found.epic");
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode n = node.get(name);
            if (n == null || n.isNull() || n.isMissingNode()) {
                continue;
            }
            String value = stringOf(n);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static double num(JsonNode parent, String field) {
        if (parent == null) {
            return 0;
        }
        JsonNode n = parent.get(field);
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
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean bool(JsonNode parent, String field) {
        if (parent == null) {
            return false;
        }
        JsonNode n = parent.get(field);
        if (n == null || n.isNull() || n.isMissingNode()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.intValue() != 0;
        }
        return "true".equalsIgnoreCase(stringOf(n));
    }

    private static String stringOf(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isString() || n.isNumber() || n.isBoolean()) {
            return n.asString();
        }
        return n.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionRequest(String identifier, String password, boolean encryptedPassword) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsResponse(List<AccountJson> accounts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountJson(
            String accountId,
            String accountName,
            String currency,
            boolean preferred,
            BalanceJson balance
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceJson(Double balance, Double deposit, Double profitLoss, Double available) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricesResponse(List<PriceJson> prices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceJson(
            String snapshotTime,
            String snapshotTimeUTC,
            BidAsk openPrice,
            BidAsk highPrice,
            BidAsk lowPrice,
            BidAsk closePrice,
            Double lastTradedVolume
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BidAsk(Double bid, Double ask) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketResponse(SnapshotJson snapshot, InstrumentJson instrument, DealingRulesJson dealingRules) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstrumentJson(String epic) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SnapshotJson(Double bid, Double offer, Double ask, String updateTimeUTC,
                        Integer decimalPlacesFactor, Double scalingFactor, String marketStatus) {
        Double askOrOffer() {
            return ask != null ? ask : offer;
        }
    }

    /** {@code /markets/{epic}} dealing rules — size and stop-distance limits. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DealingRulesJson(
            RuleValue minDealSize,
            RuleValue minStepDistance,
            RuleValue minNormalStopOrLimitDistance,
            RuleValue minControlledRiskStopDistance,
            RuleValue maxStopOrLimitDistance
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleValue(String unit, Double value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PositionsResponse(List<PositionWrapper> positions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PositionWrapper(PositionBody position, MarketBody market) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PositionBody(
            String dealId,
            String dealReference,
            Double size,
            String direction,
            Double level,
            Double stopLevel,
            Double profitLevel,
            Double upl,
            String currency,
            String createdDateUTC
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketBody(String epic) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DealReferenceResponse(String dealReference) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfirmResponse(
            String date,
            String status,
            String dealStatus,
            String reason,
            String rejectReason,
            String epic,
            String dealReference,
            String dealId,
            String direction,
            Double level,
            Double size
    ) {
    }
}
