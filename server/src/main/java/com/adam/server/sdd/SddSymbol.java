package com.adam.server.sdd;

import com.adam.server.config.AppProperties;

import java.util.List;

public enum SddSymbol {
    GER40("GER40", false),
    XAU("XAU", false),
    US100("US100", false),
    EURUSD("EURUSD", false),
    BTC("BTC", true);

    private final String code;
    private final boolean skipPivot;

    SddSymbol(String code, boolean skipPivot) {
        this.code = code;
        this.skipPivot = skipPivot;
    }

    public String code() {
        return code;
    }

    public boolean skipPivot() {
        return skipPivot;
    }

    public String epic(AppProperties properties) {
        AppProperties.Epics epics = properties.getSdd().getEpics();
        return switch (this) {
            case GER40 -> epics.getGer40();
            case XAU -> epics.getXau();
            case US100 -> epics.getUs100();
            case EURUSD -> epics.getEurusd();
            case BTC -> epics.getBtc();
        };
    }

    public static List<SddSymbol> universe() {
        return List.of(values());
    }
}
