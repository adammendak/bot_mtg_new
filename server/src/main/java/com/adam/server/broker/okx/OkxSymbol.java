package com.adam.server.broker.okx;

import java.util.List;

/**
 * Crypto universe for the OKX book. The OKX EEA entity lists <b>linear USDT
 * perpetual swaps</b> ({@code BTC-USDT-SWAP}, …) — not dated USDT futures — so
 * that is what the bot trades: {@code code()} is the short symbol (BTC, ETH, …),
 * {@code underlying()} is the OKX underlying ({@code BTC-USDT}), and
 * {@code instId()} is the perpetual ({@code BTC-USDT-SWAP}). A perpetual never
 * expires, so {@link OkxBrokerClient#resolveEpic(String)} returns it unchanged
 * and {@link com.adam.server.hts.OkxRolloverWatcher} is dormant.
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

    /** OKX underlying, e.g. {@code BTC-USDT}. */
    public String underlying() {
        return underlying;
    }

    /**
     * The instrument to trade: the linear USDT perpetual ({@code BTC-USDT-SWAP}).
     * OKX EEA lists these live; only the API key's market scope must include
     * perpetuals. (EEA has no linear dated futures — those are inverse BTC-USD.)
     */
    public String instId() {
        return underlying + "-SWAP";
    }

    public static List<OkxSymbol> universe() {
        return List.of(values());
    }
}
