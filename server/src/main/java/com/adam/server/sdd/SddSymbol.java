package com.adam.server.sdd;

import com.adam.server.config.AppProperties;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
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

    /**
     * Scan universe for {@code now} in {@code zone} (Heroku: Europe/Warsaw).
     * Monday–Friday: GER40, XAU, US100, EURUSD, BTC.
     * Saturday and Sunday: BTC only — GER40 / US100 / XAU / EURUSD are closed.
     */
    public static List<SddSymbol> universeFor(Instant now, ZoneId zone) {
        DayOfWeek day = now.atZone(zone).getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return weekendUniverse();
        }
        return universe();
    }

    /** BTC is the only SDD name open on a Warsaw Saturday or Sunday. */
    public static List<SddSymbol> weekendUniverse() {
        return List.of(BTC);
    }

    /**
     * HTS scan universe for {@code now} in {@code zone}, September forward test:
     * <ul>
     *   <li>Monday–Friday: GER40, XAU, US100, EURUSD <b>and BTC</b>.</li>
     *   <li>Saturday and Sunday: BTC only (the rest are closed).</li>
     * </ul>
     */
    public static List<SddSymbol> htsUniverseFor(Instant now, ZoneId zone) {
        DayOfWeek day = now.atZone(zone).getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return List.of(BTC);
        }
        return universe();
    }
}
