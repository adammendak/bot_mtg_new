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
 * Broker-agnostic SPI. Strategy, scheduler, REST, and the Angular UI talk only
 * to this interface plus the SDD engine. Capital.com JSON must not leak out.
 */
public interface BrokerClient {

    String id();

    String displayName();

    void login();

    boolean isSessionOpen();

    List<Account> accounts();

    List<Candle> candles(String epic, Resolution resolution, Instant from, Instant to, int max);

    MarketPrice marketPrice(String epic);

    OrderAck placeWorkingOrder(OrderRequest request);

    OrderAck amendWorkingOrder(String dealId, OrderRequest request);

    OrderAck closeWorkingOrder(String dealId);

    OrderAck placeMarketOrder(OrderRequest request);

    OrderAck closePosition(String dealId, double size);

    OrderAck amendPosition(String dealId, Double stopLevel, boolean trailingStop);

    List<Position> openPositions();

    Confirmation confirm(String dealReference);

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
