package com.adam.server.ops;

import com.adam.server.persistence.ErrorEventEntity;
import com.adam.server.persistence.ErrorEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorLogTest {

    private final ErrorEventRepository repo = mock(ErrorEventRepository.class);

    @Test
    void recordMapsTheThrowableAndSaves() {
        ErrorLog log = new ErrorLog(repo, 30);
        log.record("hts-scan", "CORE", "GER40", new IllegalStateException("boom"));

        ArgumentCaptor<ErrorEventEntity> c = ArgumentCaptor.forClass(ErrorEventEntity.class);
        verify(repo).save(c.capture());
        ErrorEventEntity e = c.getValue();
        assertThat(e.getSource()).isEqualTo("hts-scan");
        assertThat(e.getScope()).isEqualTo("CORE");
        assertThat(e.getDetail()).isEqualTo("GER40");
        assertThat(e.getException()).isEqualTo("java.lang.IllegalStateException");
        assertThat(e.getMessage()).isEqualTo("boom");
        assertThat(e.getAt()).isNotNull();
    }

    @Test
    void longMessageIsTruncated() {
        ErrorLog log = new ErrorLog(repo, 30);
        log.record("watchdog", null, null, "SomeException", "x".repeat(5000));

        ArgumentCaptor<ErrorEventEntity> c = ArgumentCaptor.forClass(ErrorEventEntity.class);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getMessage()).hasSize(1000);
    }

    @Test
    void aBrokenRepositoryNeverPropagates() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        ErrorLog log = new ErrorLog(repo, 30);
        assertThatCode(() -> log.record("hts-exec", null, null, new RuntimeException("x")))
                .doesNotThrowAnyException();
    }

    @Test
    void purgeDeletesRowsOlderThanRetention() {
        ErrorLog log = new ErrorLog(repo, 7);
        Instant before = Instant.now();
        log.purge();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByAtBefore(cutoff.capture());
        long days = ChronoUnit.DAYS.between(cutoff.getValue(), before.plusSeconds(1));
        assertThat(days).isEqualTo(7);
    }

    @Test
    void recentCapsTheLimit() {
        ErrorLog log = new ErrorLog(repo, 30);
        log.recent(9999);
        verify(repo).findAllByOrderByIdDesc(any());
    }
}
