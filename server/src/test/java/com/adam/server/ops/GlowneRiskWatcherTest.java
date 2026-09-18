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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GlowneRiskWatcher} mails on two triggers now, not a fixed alert-only
 * threshold: a 3x/day scheduled check-in (08:30/12:30/18:00 Warsaw) and a
 * ≥2-point change since whatever was last actually mailed. The very first
 * {@code run()} ever only establishes the baseline (no mail) — every test
 * that exercises mailing therefore primes with one {@code run()} before the
 * one under test.
 */
class GlowneRiskWatcherTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    /** A weekday instant matching none of the 08:30/12:30/18:00 check-ins (10:00 Warsaw). */
    private static final Instant NOT_SCHEDULED = Instant.parse("2026-09-16T08:00:00Z");
    /** 08:30:20 Warsaw the same day — lands on the first scheduled check-in. */
    private static final Instant AT_0830 = Instant.parse("2026-09-16T06:30:20Z");

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
        props = new AppProperties(); // epics default: GOLD/SILVER/US100/US500/US30/DE40/J225/EURUSD/USDJPY
        when(books.forBook(Books.GLOWNE)).thenReturn(g);
        when(g.configured()).thenReturn(true);
        when(g.isSessionOpen()).thenReturn(true);
        when(g.accounts()).thenReturn(List.of(
                new Account("main", "Główne", "PLN", 10_000, 10_000, 0, true)));
        // marketRules / fxRate left unstubbed → point-value falls back to 1.0
    }

    private GlowneRiskWatcher watcher(Clock clock) {
        return new GlowneRiskWatcher(books, props, mailer, errorLog, clock, true, 3.0);
    }

    private GlowneRiskWatcher watcher(Instant at) {
        return watcher(Clock.fixed(at, ZONE));
    }

    private Position pos(String epic, Direction dir, double size, double level, Double stop) {
        return new Position("d-" + epic, "r", epic, dir, size, level, stop, null, 0, "USD", Instant.now());
    }

    @Test
    void firstRunEverOnlyBaselinesAndNeverMails() {
        when(g.openPositions()).thenReturn(List.of(
                pos("US100", Direction.SELL, 2, 20_000, 20_150.0))); // 300 = 3.0%

        watcher(NOT_SCHEDULED).run();

        verifyNoInteractions(mailer);
    }

    @Test
    void mailsWhenRiskChangesByTwoPointsOrMoreSinceTheLastMail() {
        GlowneRiskWatcher w = watcher(NOT_SCHEDULED);
        when(g.openPositions()).thenReturn(List.of()); // baseline: 0%
        w.run(); // primes lastMailedPct = 0, no mail

        when(g.openPositions()).thenReturn(List.of(
                pos("US100", Direction.SELL, 2, 20_000, 20_150.0),   // 150 * 2 = 300
                pos("DE40", Direction.BUY, 1, 18_000, 17_850.0)));    // 150 * 1 = 150  → 450 = 4.5%
        w.run(); // 0% -> 4.5%, a 4.5-point jump

        verify(mailer).send(contains("4.50%"), contains("US100"));
    }

    @Test
    void staysSilentWhenChangeIsStrictlyUnderTheTwoPointTrigger() {
        GlowneRiskWatcher w = watcher(NOT_SCHEDULED);
        when(g.openPositions()).thenReturn(List.of(
                pos("GOLD", Direction.BUY, 1, 4_300, 4_230.0)));   // 70 = 0.70%
        w.run(); // primes lastMailedPct = 0.70%, no mail

        when(g.openPositions()).thenReturn(List.of(
                pos("GOLD", Direction.BUY, 1, 4_300, 4_031.0)));   // 269 = 2.69% -> delta 1.99pp < 2.0
        w.run();

        verify(mailer, never()).send(anyString(), anyString());
    }

    @Test
    void mailsExactlyOnTheTwoPointBoundary() {
        GlowneRiskWatcher w = watcher(NOT_SCHEDULED);
        when(g.openPositions()).thenReturn(List.of()); // baseline: 0%
        w.run();

        when(g.openPositions()).thenReturn(List.of(
                pos("GOLD", Direction.BUY, 1, 4_300, 4_100.0)));   // 200 = 2.0% -> delta exactly 2.0pp
        w.run();

        verify(mailer).send(contains("2.00%"), anyString());
    }

    @Test
    void scheduledCheckInMailsEvenWithNoChangeAtAll() {
        MutableClock clock = new MutableClock(NOT_SCHEDULED, ZONE);
        GlowneRiskWatcher w = watcher(clock);
        List<Position> stable = List.of(pos("GOLD", Direction.BUY, 1, 4_300, 4_200.0)); // 100 = 1.0%
        when(g.openPositions()).thenReturn(stable);
        w.run(); // primes lastMailedPct = 1.0% off-schedule, no mail

        clock.set(AT_0830); // next cron tick lands exactly on the 08:30 check-in
        w.run(); // same 1.0%, unchanged, but it's a scheduled slot

        verify(mailer).send(contains("raport okresowy"), anyString());
    }

    @Test
    void scheduledSlotMailsAtMostOncePerDay() {
        MutableClock clock = new MutableClock(NOT_SCHEDULED, ZONE);
        GlowneRiskWatcher w = watcher(clock);
        List<Position> stable = List.of(pos("GOLD", Direction.BUY, 1, 4_300, 4_200.0));
        when(g.openPositions()).thenReturn(stable);
        w.run(); // baseline

        clock.set(AT_0830);
        w.run(); // scheduled -> mails once
        w.run(); // same slot again (e.g. a retried cron tick) -> must NOT double-mail

        verify(mailer, org.mockito.Mockito.times(1)).send(anyString(), anyString());
    }

    @Test
    void stoplessPositionMailsOnceOnTheTransitionNotEveryCycle() {
        GlowneRiskWatcher w = watcher(NOT_SCHEDULED);
        when(g.openPositions()).thenReturn(List.of()); // baseline: nothing open
        w.run(); // primes, no mail

        when(g.openPositions()).thenReturn(List.of(
                pos("US500", Direction.BUY, 1, 5_000, null))); // no stop → 0 risk, but flagged
        w.run(); // stopless just appeared -> mails

        w.run(); // still stopless, unchanged -> must NOT mail again

        verify(mailer).send(anyString(), contains("NO STOP"));
    }

    @Test
    void ignoresInstrumentsOutsideTheWatchedNine() {
        GlowneRiskWatcher w = watcher(NOT_SCHEDULED);
        when(g.openPositions()).thenReturn(List.of(
                pos("GBPUSD", Direction.BUY, 100_000, 1.30, 1.25),    // huge risk but not watched
                pos("BTCUSD", Direction.BUY, 1, 60_000, 30_000.0)));
        w.run(); // baseline, no mail regardless
        w.run(); // unchanged (still 0%, not scheduled) -> no mail

        verifyNoInteractions(mailer);
    }

    @Test
    void sumsAllNineWatchedInstruments() {
        GlowneRiskWatcher w = watcher(NOT_SCHEDULED);
        when(g.openPositions()).thenReturn(List.of()); // baseline: 0%
        w.run();

        when(g.openPositions()).thenReturn(List.of(
                pos("SILVER", Direction.BUY, 10, 50, 49.0),        // 10
                pos("J225", Direction.BUY, 1, 40_000, 39_900.0),   // 100
                pos("EURUSD", Direction.BUY, 1_000, 1.10, 1.05),   // 50
                pos("USDJPY", Direction.BUY, 100, 150, 149.0),     // 100
                pos("US30", Direction.BUY, 1, 40_000, 39_900.0))); // 100  -> 360 = 3.6%
        w.run();

        verify(mailer).send(contains("3.60%"), anyString());
    }

    @Test
    void noopWhenGlowneBookIsNotConfigured() {
        when(g.configured()).thenReturn(false);

        watcher(NOT_SCHEDULED).run();

        verifyNoInteractions(mailer);
    }

    @Test
    void disabledFlagIsAHardOff() {
        new GlowneRiskWatcher(books, props, mailer, errorLog, Clock.fixed(NOT_SCHEDULED, ZONE), false, 3.0).run();
        verifyNoInteractions(mailer);
    }

    /** A {@link Clock} whose instant can be advanced mid-test, so a single watcher
     *  instance (and its internal state) can be driven across simulated cron ticks. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
