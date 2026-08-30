package com.adam.server.ops;

import com.adam.server.scan.Mailer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SchedulerWatchdogTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T09:00:00Z"), ZoneOffset.UTC);
    private final SchedulerHeartbeat heartbeat = mock(SchedulerHeartbeat.class);
    private final Mailer mailer = mock(Mailer.class);
    private final ErrorLog errorLog = mock(ErrorLog.class);

    @Test
    void staleProbesTriggerOneThrottledMailAndAnErrorEvent() {
        when(heartbeat.stale(any())).thenReturn(List.of("hts-scan", "hts-monitor"));
        SchedulerWatchdog wd = new SchedulerWatchdog(heartbeat, mailer, errorLog, clock, true);

        wd.check();

        verify(mailer).sendThrottled(eq("scheduler-watchdog"), contains("scheduler"), contains("hts-scan"));
        verify(errorLog).record(eq("watchdog"), any(), contains("hts-scan"), eq("SchedulerStale"), any());
        verify(mailer, never()).clearThrottle(any());
    }

    @Test
    void healthyClearsTheThrottleAndDoesNotMail() {
        when(heartbeat.stale(any())).thenReturn(List.of());
        SchedulerWatchdog wd = new SchedulerWatchdog(heartbeat, mailer, errorLog, clock, true);

        wd.check();

        verify(mailer).clearThrottle("scheduler-watchdog");
        verify(mailer, never()).sendThrottled(any(), any(), any());
        verifyNoInteractions(errorLog);
    }

    @Test
    void disabledWatchdogDoesNothing() {
        SchedulerWatchdog wd = new SchedulerWatchdog(heartbeat, mailer, errorLog, clock, false);

        wd.check();

        verifyNoInteractions(mailer, errorLog, heartbeat);
    }
}
