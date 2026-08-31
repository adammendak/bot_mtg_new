package com.adam.server.hts;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only candle feed for HTS backtests. Serves stored OHLC; refuses orders.
 */
final class ReplayBrokerClient implements BrokerClient {

    private final Map<String, Map<Resolution, List<Candle>>> byEpic = new HashMap<>();

    void put(String epic, Resolution res, List<Candle> candles) {
        List<Candle> copy = new ArrayList<>(candles);
        copy.sort(Comparator.comparing(Candle::time));
        byEpic.computeIfAbsent(epic, k -> new EnumMap<>(Resolution.class)).put(res, List.copyOf(copy));
    }

    /** Load {@code <epic>_<RES>.csv} files ({@code time,open,high,low,close,volume}). */
    static ReplayBrokerClient fromCsvDir(Path dir) throws IOException {
        ReplayBrokerClient client = new ReplayBrokerClient();
        if (dir == null || !Files.isDirectory(dir)) {
            return client;
        }
        try (var stream = Files.list(dir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".csv")).toList()) {
                String name = f.getFileName().toString();
                int under = name.lastIndexOf('_');
                if (under < 1 || !name.endsWith(".csv")) {
                    continue;
                }
                String epic = name.substring(0, under);
                String resName = name.substring(under + 1, name.length() - 4);
                Resolution res;
                try {
                    res = Resolution.valueOf(resName);
                } catch (RuntimeException e) {
                    continue;
                }
                client.put(epic, res, readCsv(f));
            }
        }
        return client;
    }

    static List<Candle> readCsv(Path f) throws IOException {
        List<Candle> out = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
            if (line.isBlank() || line.startsWith("time") || line.startsWith("Time")) {
                continue;
            }
            String[] p = line.split(",");
            if (p.length < 5) {
                continue;
            }
            Instant t = Instant.parse(p[0].trim());
            double o = Double.parseDouble(p[1]);
            double h = Double.parseDouble(p[2]);
            double l = Double.parseDouble(p[3]);
            double c = Double.parseDouble(p[4]);
            double v = p.length > 5 && !p[5].isBlank() ? Double.parseDouble(p[5]) : 0;
            if (h < l || Double.isNaN(o) || Double.isNaN(c)) {
                continue;
            }
            out.add(new Candle(t, o, h, l, c, v));
        }
        out.sort(Comparator.comparing(Candle::time));
        return out;
    }

    int size(String epic, Resolution res) {
        List<Candle> rows = series(epic, res);
        return rows == null ? 0 : rows.size();
    }

    Instant first(String epic, Resolution res) {
        List<Candle> rows = series(epic, res);
        return rows == null || rows.isEmpty() ? null : rows.getFirst().time();
    }

    Instant last(String epic, Resolution res) {
        List<Candle> rows = series(epic, res);
        return rows == null || rows.isEmpty() ? null : rows.getLast().time();
    }

    private List<Candle> series(String epic, Resolution res) {
        Map<Resolution, List<Candle>> m = byEpic.get(epic);
        return m == null ? null : m.get(res);
    }

    @Override
    public String id() {
        return "replay";
    }

    @Override
    public String displayName() {
        return "Replay OHLC";
    }

    @Override
    public String book() {
        return "demo";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void login() {
        // read-only
    }

    @Override
    public boolean isSessionOpen() {
        return true;
    }

    @Override
    public List<Account> accounts() {
        return List.of(new Account("replay-1", "replay", "PLN", 1000, 1000, 0, true));
    }

    @Override
    public List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max) {
        List<Candle> all = series(epic, resolution);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<Candle> out = new ArrayList<>();
        for (Candle c : all) {
            if (from != null && c.time().isBefore(from)) {
                continue;
            }
            if (to != null && c.time().isAfter(to)) {
                continue;
            }
            out.add(c);
        }
        int cap = Math.max(max, 1);
        if (out.size() > cap) {
            return out.subList(out.size() - cap, out.size());
        }
        return out;
    }

    @Override
    public MarketPrice marketPrice(String epic) {
        throw new BrokerException("replay is candles-only");
    }

    @Override
    public OrderAck placeWorkingOrder(OrderRequest request) {
        return reject();
    }

    @Override
    public OrderAck amendWorkingOrder(String dealId, OrderRequest request) {
        return reject();
    }

    @Override
    public OrderAck closeWorkingOrder(String dealId) {
        return reject();
    }

    @Override
    public OrderAck placeMarketOrder(OrderRequest request) {
        return reject();
    }

    @Override
    public OrderAck closePosition(String dealId, double size) {
        return reject();
    }

    @Override
    public OrderAck amendPosition(String dealId, Double stopLevel, boolean trailingStop) {
        return reject();
    }

    @Override
    public List<Position> openPositions() {
        return List.of();
    }

    @Override
    public Confirmation confirm(String dealReference) {
        throw new BrokerException("replay is candles-only");
    }

    private static OrderAck reject() {
        throw new BrokerException("replay is candles-only — no orders");
    }
}
