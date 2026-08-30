package com.adam.server.broker;

import java.util.List;

/**
 * Canonical broker book identifiers. Use these constants instead of bare
 * {@code "demo"} / {@code "live"} / {@code "glowne"} literals so a typo is a
 * compile error and the set of books is defined in one place.
 */
public final class Books {

    private Books() {
    }

    public static final String DEMO = "demo";
    public static final String LIVE = "live";
    public static final String GLOWNE = "glowne";
    /** Separate Capital.com demo account reserved for the SDD-SWING (H1) strategy. */
    public static final String SWING = "swing";
    /** Separate Capital.com demo account reserved for the HTS ("wstęgi") strategy. */
    public static final String HTS = "hts";

    /** Wire / config alias accepted for {@link #GLOWNE}. */
    public static final String GLOWNE_ALIAS = "main";

    /** All books, dashboard order (Demo | Live | Główne | Swing | HTS). */
    public static final List<String> ALL = List.of(DEMO, LIVE, GLOWNE, SWING, HTS);

    /**
     * Books the SDD-M15 execution gate may trade. Główne and Swing are never
     * traded by SDD-M15 (Swing has its own strategy; Główne is view-only).
     */
    public static final List<String> EXECUTABLE = List.of(DEMO, LIVE);

    /** True when {@code id} names the Główne book (accepts the {@code "main"} alias). */
    public static boolean isGlowne(String id) {
        return id != null && (id.equalsIgnoreCase(GLOWNE) || id.equalsIgnoreCase(GLOWNE_ALIAS));
    }
}
