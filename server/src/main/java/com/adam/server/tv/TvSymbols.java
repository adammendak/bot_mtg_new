package com.adam.server.tv;

import java.util.Locale;
import java.util.Map;

/** Maps TradingView tickers (optionally {@code EXCHANGE:SYMBOL}) to the bot's symbol codes. */
public final class TvSymbols {

    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("SPX500", "US500"), Map.entry("SPX", "US500"), Map.entry("US500", "US500"),
            Map.entry("NAS100", "US100"), Map.entry("NDX", "US100"), Map.entry("US100", "US100"),
            Map.entry("DJI", "US30"), Map.entry("US30", "US30"), Map.entry("DOW30", "US30"),
            Map.entry("GER40", "GER40"), Map.entry("DE40", "GER40"), Map.entry("DAX", "GER40"),
            Map.entry("DE30", "GER40"), Map.entry("GER30", "GER40"),
            Map.entry("JP225", "J225"), Map.entry("J225", "J225"), Map.entry("NIKKEI225", "J225"),
            Map.entry("XAUUSD", "XAU"), Map.entry("GOLD", "XAU"),
            Map.entry("XAGUSD", "XAG"), Map.entry("SILVER", "XAG"),
            Map.entry("BTCUSD", "BTC"), Map.entry("BTCUSDT", "BTC"),
            Map.entry("ETHUSD", "ETH"), Map.entry("ETHUSDT", "ETH"),
            Map.entry("XRPUSD", "XRP"), Map.entry("XRPUSDT", "XRP"));

    private TvSymbols() {
    }

    /** Upper-cased code without exchange prefix / perpetual suffix; unknown tickers pass through. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        if (s.endsWith(".P")) {
            s = s.substring(0, s.length() - 2);
        }
        return ALIAS.getOrDefault(s, s);
    }
}
