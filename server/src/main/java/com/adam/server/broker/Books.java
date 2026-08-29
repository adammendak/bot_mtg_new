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

    /** Wire / config alias accepted for {@link #GLOWNE}. */
    public static final String GLOWNE_ALIAS = "main";

    /** All books, dashboard order (Demo | Live | Główne). */
    public static final List<String> ALL = List.of(DEMO, LIVE, GLOWNE);

    /** Books the execution gate may trade — Główne never executes. */
    public static final List<String> EXECUTABLE = List.of(DEMO, LIVE);

    /** True when {@code id} names the Główne book (accepts the {@code "main"} alias). */
    public static boolean isGlowne(String id) {
        return id != null && (id.equalsIgnoreCase(GLOWNE) || id.equalsIgnoreCase(GLOWNE_ALIAS));
    }
}
