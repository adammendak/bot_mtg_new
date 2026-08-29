package com.adam.server.swing;

/**
 * Universe for the swing strategy (SDD-SWING). Same instruments as SDD-M15 but
 * traded on a higher timeframe. Placeholder only — nothing is wired to a broker.
 */
public enum SwingSymbol {
    GER40("DE40"),
    XAU("GOLD"),
    US100("US100"),
    EURUSD("EURUSD"),
    BTC("BTCUSD");

    private final String epic;

    SwingSymbol(String epic) {
        this.epic = epic;
    }

    public String code() {
        return name();
    }

    public String epic() {
        return epic;
    }

    public static SwingSymbol[] universe() {
        return values();
    }
}
