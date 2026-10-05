package com.adam.server.hts;

import com.adam.server.broker.Direction;

import java.util.Locale;
import java.util.Set;

/**
 * Correlation clusters for the ST_V3 books. The forward test's worst days were
 * pile-ups of same-direction entries on one risk factor (2026-09-29: 14 closes,
 * -7.4R on m5; 2026-10-01: -5.1R m15 / -6.2R mms), so the execution gate caps
 * how many same-direction trades may be open per cluster on one book.
 *
 * <p>Clusters: equity indices, metals, crypto, and the USD side of an FX pair
 * (a BUY on a {@code xxxUSD} pair and a SELL on a {@code USDxxx} pair are both
 * short-USD). Instruments outside every cluster return {@code null} (uncapped).
 */
final class HtsCluster {

    private static final Set<String> EQUITY = Set.of("US100", "US500", "US30", "GER40", "J225");
    private static final Set<String> METAL = Set.of("XAU", "XAG");
    private static final Set<String> CRYPTO = Set.of("BTC", "ETH", "XRP");

    private HtsCluster() {
    }

    /** Cluster key such as {@code EQUITY|BUY} or {@code USD|SHORT}; {@code null} when uncapped. */
    static String key(String symbol, Direction direction) {
        if (symbol == null || direction == null) {
            return null;
        }
        return key(symbol, direction.name());
    }

    static String key(String symbol, String direction) {
        if (symbol == null || direction == null) {
            return null;
        }
        String sym = symbol.toUpperCase(Locale.ROOT);
        boolean buy = "BUY".equalsIgnoreCase(direction);
        if (EQUITY.contains(sym)) {
            return "EQUITY|" + (buy ? "BUY" : "SELL");
        }
        if (METAL.contains(sym)) {
            return "METAL|" + (buy ? "BUY" : "SELL");
        }
        if (CRYPTO.contains(sym)) {
            return "CRYPTO|" + (buy ? "BUY" : "SELL");
        }
        if (sym.length() == 6) {
            boolean usdFirst = sym.startsWith("USD");
            boolean usdLast = sym.endsWith("USD");
            if (usdFirst != usdLast) {
                // USDxxx BUY = long USD; xxxUSD BUY = short USD.
                boolean longUsd = usdFirst == buy;
                return "USD|" + (longUsd ? "LONG" : "SHORT");
            }
        }
        return null;
    }
}
