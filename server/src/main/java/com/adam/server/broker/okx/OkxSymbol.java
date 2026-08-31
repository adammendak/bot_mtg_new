package com.adam.server.broker.okx;

import java.util.List;

/**
 * Crypto universe for the OKX book. The OKX EEA entity does not offer perpetual
 * swaps to retail, so the bot trades <b>dated (quarterly) futures</b> instead:
 * {@code code()} is the short symbol (BTC, ETH, …), {@code underlying()} is the
 * OKX underlying ({@code BTC-USDT}), and the concrete contract id
 * ({@code BTC-USDT-YYMMDD}) is resolved per cycle by
 * {@link OkxBrokerClient#resolveEpic(String)} to the current front quarter.
 */
public enum OkxSymbol {

    BTC("BTC", "BTC-USDT"),
    ETH("ETH", "ETH-USDT"),
    SOL("SOL", "SOL-USDT"),
    XRP("XRP", "XRP-USDT"),
    DOGE("DOGE", "DOGE-USDT"),
    LTC("LTC", "LTC-USDT");

    private final String code;
    private final String underlying;

    OkxSymbol(String code, String underlying) {
        this.code = code;
        this.underlying = underlying;
    }

    public String code() {
        return code;
    }

    /** OKX underlying, e.g. {@code BTC-USDT} — resolved to a dated contract at run time. */
    public String underlying() {
        return underlying;
    }

    public static List<OkxSymbol> universe() {
        return List.of(values());
    }
}
