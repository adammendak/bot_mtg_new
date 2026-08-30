package com.adam.server.broker;

import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;

import java.time.Instant;
import java.util.List;

/**
 * Broker-agnostic SPI. Strategy, scheduler, REST, and the Angular UI talk only
 * to this interface plus the SDD engine. Capital.com JSON must not leak out.
 *
 * <p>Bybit and Binance are not in this repository's git history. When a real
 * adapter exists, implement this interface and register beans next to Capital.
 * Do not ship a fake Bybit/Binance client.
 */
public interface BrokerClient {

    String id();

    String displayName();

    void login();

    boolean isSessionOpen();

    List<Account> accounts();

    List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max);

    MarketPrice marketPrice(String epic);

    /**
     * Instrument dealing rules (size step / minimum, price precision, min stop
     * distance) used to round an order into a shape the broker will actually
     * accept. Default: permissive — no adjustment.
     */
    default MarketRules marketRules(String epic) {
        return MarketRules.permissive(epic);
    }

    OrderAck placeWorkingOrder(OrderRequest request);

    OrderAck amendWorkingOrder(String dealId, OrderRequest request);

    OrderAck closeWorkingOrder(String dealId);

    OrderAck placeMarketOrder(OrderRequest request);

    OrderAck closePosition(String dealId, double size);

    OrderAck amendPosition(String dealId, Double stopLevel, boolean trailingStop);

    List<Position> openPositions();

    Confirmation confirm(String dealReference);

    /**
     * Account transaction history in {@code [from, to)} used to reconstruct daily
     * equity. Returns an empty list when the broker has no history or the adapter
     * does not support it.
     */
    default List<BrokerTransaction> transactionHistory(java.time.Instant from, java.time.Instant to) {
        return List.of();
    }

    /**
     * As {@link #transactionHistory(java.time.Instant, java.time.Instant)} but the
     * adapter must stop walking once {@code budget} of wall-clock time has
     * elapsed and return whatever it has gathered so far. Lets an HTTP-triggered
     * sync bound its own latency (Heroku's 30 s router timeout) when the broker
     * is rate-limiting. Default: ignore the budget.
     */
    default List<BrokerTransaction> transactionHistory(java.time.Instant from, java.time.Instant to,
                                                       java.time.Duration budget) {
        return transactionHistory(from, to);
    }

    /** demo or live. */
    default String book() {
        return "demo";
    }

    default boolean configured() {
        return true;
    }

    default void selectAccount(String accountId) {
    }
}
