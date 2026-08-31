package com.adam.server.broker.okx;

import java.util.List;

/**
 * Crypto universe for the OKX book — OKX SWAP (perpetual) instrument ids, which
 * trade 24/7 (no weekend filtering, unlike the Capital.com SDD universe).
 *
 * <p>The HTS engine treats these as opaque epics, so the OKX instrument id
 * doubles as the epic. {@code code()} is the short symbol used in signals and
 * the UI (BTC, ETH, …), {@code instId()} is the broker instrument id.
 */
public enum OkxSymbol {

    BTC("BTC", "BTC-USDT-SWAP"),
    ETH("ETH", "ETH-USDT-SWAP"),
    SOL("SOL", "SOL-USDT-SWAP"),
    XRP("XRP", "XRP-USDT-SWAP"),
    DOGE("DOGE", "DOGE-USDT-SWAP"),
    LTC("LTC", "LTC-USDT-SWAP");

    private final String code;
    private final String instId;

    OkxSymbol(String code, String instId) {
        this.code = code;
        this.instId = instId;
    }

    public String code() {
        return code;
    }

    public String instId() {
        return instId;
    }

    public static List<OkxSymbol> universe() {
        return List.of(values());
    }
}
