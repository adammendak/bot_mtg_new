package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Books;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.FeatureFlags;
import com.adam.server.scan.Mailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HtsWeekendFlattenerTest {

    private final BrokerBooks books = mock(BrokerBooks.class);
    private final BrokerClient hts = mock(BrokerClient.class);
    private final HtsTradeService trades = mock(HtsTradeService.class);
    private final ErrorLog errorLog = mock(ErrorLog.class);
    private final AppProperties props = new AppProperties(); // BTC epic defaults to "BTCUSD"
    private FeatureFlags flags;
    private HtsWeekendFlattener flattener;

    @BeforeEach
    void setUp() {
        flags = FeatureFlags.forTest();
        when(books.forBook(Books.HTS)).thenReturn(hts);
        when(hts.configured()).thenReturn(true);
        when(hts.isSessionOpen()).thenReturn(true);
        flattener = new HtsWeekendFlattener(books, flags, props, trades, Mailer.disabled(), errorLog);
    }

    private static Position pos(String dealId, String epic) {
        return new Position(dealId, "r", epic, Direction.BUY, 1.0, 100, 99.0, null, 0, "PLN", Instant.now());
    }

    @Test
    void closesNonBtcPositionsAndKeepsBtc() {
        when(hts.openPositions()).thenReturn(List.of(
                pos("d1", "DE40"), pos("d2", "BTCUSD"), pos("d3", "GOLD")));

        flattener.run();

        verify(hts).closePosition("d1", 1.0);
        verify(hts).closePosition("d3", 1.0);
        verify(hts, never()).closePosition(eq("d2"), anyDouble()); // BTC stays
        verify(trades).tagWeekend(Books.HTS, "BTCUSD");
        verify(trades).manage();
    }

    @Test
    void doesNothingWhenOnlyBtcIsOpen() {
        when(hts.openPositions()).thenReturn(List.of(pos("d2", "BTCUSD")));

        flattener.run();

        verify(hts, never()).closePosition(anyString(), anyDouble());
        verify(trades, never()).tagWeekend(anyString(), anyString());
        verify(trades, never()).manage();
    }

    @Test
    void disabledFlagShortCircuits() {
        flags.set("hts.weekend-flatten", false, "test");

        flattener.run();

        verifyNoInteractions(hts, trades);
    }

    @Test
    void oneFailedCloseIsLoggedAndTheRestContinue() {
        when(hts.openPositions()).thenReturn(List.of(pos("d1", "DE40"), pos("d3", "GOLD")));
        when(hts.closePosition("d1", 1.0)).thenThrow(new RuntimeException("market closed"));

        flattener.run();

        verify(errorLog).record(eq("hts-weekend"), eq(Books.HTS), eq("DE40"), any(Throwable.class));
        verify(hts).closePosition("d3", 1.0); // still closes the next one
        verify(trades).tagWeekend(Books.HTS, "BTCUSD");
    }
}
