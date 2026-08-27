package com.adam.server.broker;

import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;

import java.time.Instant;
import java.util.List;

/**
 * Placeholder when a book has no credentials. The app still boots; the pane shows disconnected.
 */
public class UnavailableBrokerClient implements BrokerClient {

    private final String book;
    private final String reason;

    public UnavailableBrokerClient(String book, String reason) {
        this.book = book;
        this.reason = reason;
    }

    @Override
    public String id() {
        return "unavailable";
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
        return false;
    }

    @Override
    public void login() {
        throw new BrokerException(reason);
    }

    @Override
    public boolean isSessionOpen() {
        return false;
    }

    @Override
    public List<Account> accounts() {
        return List.of();
    }

    @Override
    public List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max) {
        throw new BrokerException(reason);
    }

    @Override
    public MarketPrice marketPrice(String epic) {
        throw new BrokerException(reason);
    }

    @Override
    public OrderAck placeWorkingOrder(OrderRequest request) {
        throw new BrokerException(reason);
    }

    @Override
    public OrderAck amendWorkingOrder(String dealId, OrderRequest request) {
        throw new BrokerException(reason);
    }

    @Override
    public OrderAck closeWorkingOrder(String dealId) {
        throw new BrokerException(reason);
    }

    @Override
    public OrderAck placeMarketOrder(OrderRequest request) {
        throw new BrokerException(reason);
    }

    @Override
    public OrderAck closePosition(String dealId, double size) {
        throw new BrokerException(reason);
    }

    @Override
    public OrderAck amendPosition(String dealId, Double stopLevel, boolean trailingStop) {
        throw new BrokerException(reason);
    }

    @Override
    public List<Position> openPositions() {
        return List.of();
    }

    @Override
    public Confirmation confirm(String dealReference) {
        throw new BrokerException(reason);
    }
}
