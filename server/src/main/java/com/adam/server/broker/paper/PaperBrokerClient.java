package com.adam.server.broker.paper;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process stub that proves the SPI can be swapped without rewriting strategy.
 * Not a second live broker.
 */
public class PaperBrokerClient implements BrokerClient {

    private final Clock clock;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();
    private final List<OrderAck> orders = new CopyOnWriteArrayList<>();
    private volatile boolean sessionOpen;

    public PaperBrokerClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String id() {
        return "paper";
    }

    @Override
    public String displayName() {
        return "Paper (stub)";
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
        sessionOpen = true;
    }

    @Override
    public boolean isSessionOpen() {
        return sessionOpen;
    }

    @Override
    public List<Account> accounts() {
        login();
        return List.of(new Account("paper-1", "paper", "PLN", 1000, 1000, 0, true));
    }

    @Override
    public List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max) {
        login();
        Duration step = switch (resolution) {
            case M15 -> Duration.ofMinutes(15);
            case H1 -> Duration.ofHours(1);
            case H4 -> Duration.ofHours(4);
            case D1 -> Duration.ofDays(1);
        };
        Instant end = to.truncatedTo(ChronoUnit.MINUTES);
        int count = Math.min(max, 400);
        List<Candle> out = new ArrayList<>(count);
        double seed = Math.abs(epic.hashCode() % 1000) + 100.0;
        Instant cursor = end.minus(step.multipliedBy(count));
        if (cursor.isBefore(from)) {
            cursor = from;
        }
        int i = 0;
        while (!cursor.isAfter(end) && out.size() < count) {
            double drift = i * 0.05;
            double open = seed + drift;
            double close = open + 0.2;
            double high = close + 0.15;
            double low = open - 0.1;
            out.add(new Candle(cursor, open, high, low, close, 100));
            cursor = cursor.plus(step);
            i++;
        }
        return out;
    }

    @Override
    public MarketPrice marketPrice(String epic) {
        login();
        List<Candle> last = candles(epic, Resolution.M15, clock.instant().minus(Duration.ofHours(1)), clock.instant(), 1);
        double px = last.isEmpty() ? 100 : last.getLast().close();
        return new MarketPrice(epic, px - 0.1, px + 0.1, clock.instant());
    }

    @Override
    public OrderAck placeWorkingOrder(OrderRequest request) {
        return recordOrder(request, "WORKING");
    }

    @Override
    public OrderAck amendWorkingOrder(String dealId, OrderRequest request) {
        return new OrderAck(dealId, dealId, "AMENDED");
    }

    @Override
    public OrderAck closeWorkingOrder(String dealId) {
        return new OrderAck(dealId, dealId, "DELETED");
    }

    @Override
    public OrderAck placeMarketOrder(OrderRequest request) {
        OrderAck ack = recordOrder(request, "FILLED");
        positions.put(ack.dealId(), new Position(
                ack.dealId(),
                ack.dealReference(),
                request.epic(),
                request.direction(),
                request.size(),
                request.level() == null ? 0 : request.level(),
                request.stopLevel(),
                request.profitLevel(),
                0,
                "PLN",
                clock.instant()
        ));
        return ack;
    }

    @Override
    public OrderAck closePosition(String dealId, double size) {
        positions.remove(dealId);
        return new OrderAck(dealId, dealId, "CLOSED");
    }

    @Override
    public OrderAck amendPosition(String dealId, Double stopLevel, boolean trailingStop) {
        Position current = positions.get(dealId);
        if (current != null) {
            positions.put(dealId, new Position(
                    current.dealId(),
                    current.dealReference(),
                    current.epic(),
                    current.direction(),
                    current.size(),
                    current.level(),
                    stopLevel,
                    current.profitLevel(),
                    current.unrealizedPnl(),
                    current.currency(),
                    current.openedAt()
            ));
        }
        return new OrderAck(dealId, dealId, "AMENDED");
    }

    @Override
    public List<Position> openPositions() {
        login();
        return List.copyOf(positions.values());
    }

    @Override
    public Confirmation confirm(String dealReference) {
        return new Confirmation(dealReference, dealReference, "OPEN", "ACCEPTED", "", Direction.BUY, 0.0, 0.0);
    }

    private OrderAck recordOrder(OrderRequest request, String status) {
        login();
        String id = UUID.randomUUID().toString();
        OrderAck ack = new OrderAck(id, id, status);
        orders.add(ack);
        return ack;
    }
}
