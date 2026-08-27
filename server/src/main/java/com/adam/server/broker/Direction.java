package com.adam.server.broker;

public enum Direction {
    BUY,
    SELL;

    public boolean bullish() {
        return this == BUY;
    }
}
