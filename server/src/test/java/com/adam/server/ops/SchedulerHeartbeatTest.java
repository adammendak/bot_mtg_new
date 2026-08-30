package com.adam.server.ops;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerHeartbeatTest {

    private final Instant t0 = Instant.parse("2026-09-01T08:00:00Z");

    /** Clock whose instant we can move forward between assertions. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void freshAfterRegisterThenStaleAfterTheWindowThenFreshAgainOnOk() {
        MovableClock clock = new MovableClock(t0);
        SchedulerHeartbeat hb = new SchedulerHeartbeat(clock, "");
        hb.register("hts-scan", Duration.ofMinutes(13));

        assertThat(hb.stale(clock.instant())).isEmpty();

        clock.advance(Duration.ofMinutes(14));
        assertThat(hb.stale(clock.instant())).containsExactly("hts-scan");
        assertThat(hb.snapshot(clock.instant()).getFirst().stale()).isTrue();

        hb.ok("hts-scan");
        assertThat(hb.stale(clock.instant())).isEmpty();
        SchedulerHeartbeat.HeartbeatView v = hb.snapshot(clock.instant()).getFirst();
        assertThat(v.name()).isEqualTo("hts-scan");
        assertThat(v.stale()).isFalse();
        assertThat(v.ageSeconds()).isZero();
    }

    @Test
    void onlyRegisteredProbesAreWatched() {
        SchedulerHeartbeat hb = new SchedulerHeartbeat(Clock.fixed(t0, ZoneOffset.UTC), "");
        hb.register("hts-scan", Duration.ofMinutes(13));
        hb.ok("never-registered"); // must not create a probe

        List<SchedulerHeartbeat.HeartbeatView> snap = hb.snapshot(t0);
        assertThat(snap).extracting(SchedulerHeartbeat.HeartbeatView::name).containsExactly("hts-scan");
    }

    @Test
    void okWithoutHealthcheckUrlDoesNotThrow() {
        SchedulerHeartbeat hb = new SchedulerHeartbeat(Clock.fixed(t0, ZoneOffset.UTC), "");
        hb.register("hts-scan", Duration.ofMinutes(13));
        hb.ok("hts-scan");
    }
}
