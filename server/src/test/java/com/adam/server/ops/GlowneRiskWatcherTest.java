package com.adam.server.ops;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Books;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.scan.Mailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GlowneRiskWatcherTest {

    private BrokerClient g;
    private BrokerBooks books;
    private Mailer mailer;
    private ErrorLog errorLog;
    private AppProperties props;

    @BeforeEach
    void setUp() {
        g = mock(BrokerClient.class);
        books = mock(BrokerBooks.class);
        mailer = mock(Mailer.class);
        errorLog = mock(ErrorLog.class);
        props = new AppProperties(); // epics default: US100 / DE40 / GOLD / US500
        when(books.forBook(Books.GLOWNE)).thenReturn(g);
        when(g.configured()).thenReturn(true);
        when(g.isSessionOpen()).thenReturn(true);
        when(g.accounts()).thenReturn(List.of(
                new Account("main", "Główne", "PLN", 10_000, 10_000, 0, true)));
        // marketRules / fxRate left unstubbed → point-value falls back to 1.0
    }

    private GlowneRiskWatcher watcher() {
        return new GlowneRiskWatcher(books, props, mailer, errorLog, true, 3.0);
    }

    private Position pos(String epic, Direction dir, double size, double level, Double stop) {
        return new Position("d-" + epic, "r", epic, dir, size, level, stop, null, 0, "USD", Instant.now());
    }

    @Test
    void alertsWhenAggregateStopRiskExceedsTheLimit() {
        when(g.openPositions()).thenReturn(List.of(
                pos("US100", Direction.SELL, 2, 20_000, 20_150.0),   // 150 * 2 = 300
                pos("DE40", Direction.BUY, 1, 18_000, 17_850.0)));    // 150 * 1 = 150  → 450 = 4.5%

        watcher().run();

        verify(mailer).sendThrottled(eq("glowne-risk"), contains("4.50%"), contains("US100"));
    }

    @Test
    void silentAndReArmsWhenUnderTheLimit() {
        when(g.openPositions()).thenReturn(List.of(
                pos("GOLD", Direction.BUY, 1, 4_300, 4_100.0)));      // 200 = 2.0%

        watcher().run();

        verify(mailer, never()).sendThrottled(anyString(), anyString(), anyString());
        verify(mailer).clearThrottle("glowne-risk");
    }

    @Test
    void stoplessPositionIsListedNotSummedAndStillAlerts() {
        when(g.openPositions()).thenReturn(List.of(
                pos("US500", Direction.BUY, 1, 5_000, null)));        // no stop → 0 risk, but flagged

        watcher().run();

        verify(mailer).sendThrottled(eq("glowne-risk"), anyString(), contains("NO STOP"));
    }

    @Test
    void ignoresInstrumentsOutsideTheWatchedFour() {
        when(g.openPositions()).thenReturn(List.of(
                pos("EURUSD", Direction.BUY, 100_000, 1.10, 1.05),    // huge risk but not watched
                pos("BTCUSD", Direction.BUY, 1, 60_000, 30_000.0)));

        watcher().run();

        verify(mailer, never()).sendThrottled(anyString(), anyString(), anyString());
    }

    @Test
    void noopWhenGlowneBookIsNotConfigured() {
        when(g.configured()).thenReturn(false);

        watcher().run();

        verifyNoInteractions(mailer);
    }

    @Test
    void disabledFlagIsAHardOff() {
        new GlowneRiskWatcher(books, props, mailer, errorLog, false, 3.0).run();
        verifyNoInteractions(mailer);
    }
}
