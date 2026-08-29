package com.adam.server.sdd;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SddSymbolTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final List<SddSymbol> WEEKDAY = List.of(
            SddSymbol.GER40, SddSymbol.XAU, SddSymbol.US100, SddSymbol.EURUSD, SddSymbol.BTC);

    @Test
    void weekdayUniverseIsAllSddNames() {
        // Friday 29 Aug 2025 23:59 Warsaw is still the full universe.
        Instant friday = ZonedDateTime.of(2025, 8, 29, 23, 59, 0, 0, WARSAW).toInstant();
        Instant monday = ZonedDateTime.of(2026, 8, 24, 12, 0, 0, 0, WARSAW).toInstant();
        assertThat(SddSymbol.universeFor(friday, WARSAW)).containsExactlyElementsOf(WEEKDAY);
        assertThat(SddSymbol.universeFor(monday, WARSAW)).containsExactlyElementsOf(WEEKDAY);
    }

    @Test
    void warsawSaturdayAndSundayScanBtcOnly() {
        Instant saturdayOpen = ZonedDateTime.of(2026, 8, 29, 0, 0, 0, 0, WARSAW).toInstant();
        Instant sunday = ZonedDateTime.of(2026, 8, 30, 18, 1, 0, 0, WARSAW).toInstant();
        assertThat(SddSymbol.universeFor(saturdayOpen, WARSAW)).containsExactly(SddSymbol.BTC);
        assertThat(SddSymbol.universeFor(sunday, WARSAW)).containsExactly(SddSymbol.BTC);
        assertThat(SddSymbol.weekendUniverse()).containsExactly(SddSymbol.BTC);
    }

    @Test
    void calendarIsWarsawNotUtc() {
        // Saturday 00:30 Warsaw is still Friday 22:30 UTC — must stay BTC-only.
        Instant saturdayWarsaw = ZonedDateTime.of(2026, 8, 29, 0, 30, 0, 0, WARSAW).toInstant();
        assertThat(saturdayWarsaw.atZone(ZoneId.of("UTC")).getDayOfWeek().name()).isEqualTo("FRIDAY");
        assertThat(SddSymbol.universeFor(saturdayWarsaw, WARSAW)).containsExactly(SddSymbol.BTC);
    }
}
