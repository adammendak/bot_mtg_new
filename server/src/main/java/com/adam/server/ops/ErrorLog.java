package com.adam.server.ops;

import com.adam.server.persistence.ErrorEventEntity;
import com.adam.server.persistence.ErrorEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Durable failure log (E-5). {@link #record} is best-effort and never throws —
 * a broken log must not break the caller. Writes run in their own transaction so
 * the row survives a rollback in the caller. Rows older than
 * {@code app.ops.error-retention-days} are purged nightly.
 */
@Service
public class ErrorLog {

    private static final Logger log = LoggerFactory.getLogger(ErrorLog.class);
    private static final int MSG_MAX = 1000;

    private final ErrorEventRepository repo;
    private final int retentionDays;

    public ErrorLog(ErrorEventRepository repo,
                    @Value("${app.ops.error-retention-days:30}") int retentionDays) {
        this.repo = repo;
        this.retentionDays = Math.max(1, retentionDays);
    }

    public void record(String source, String scope, String detail, Throwable t) {
        String ex = t == null ? null : t.getClass().getName();
        String msg = t == null ? null : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        record(source, scope, detail, ex, msg);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String source, String scope, String detail, String exception, String message) {
        try {
            ErrorEventEntity e = new ErrorEventEntity();
            e.setAt(Instant.now());
            e.setSource(trunc(source, 32));
            e.setScope(trunc(scope, 32));
            e.setDetail(trunc(detail, 64));
            e.setException(trunc(exception, 128));
            e.setMessage(trunc(message, MSG_MAX));
            repo.save(e);
        } catch (Exception writeFail) {
            log.warn("ErrorLog write failed: {}", writeFail.getClass().getSimpleName());
        }
    }

    public List<ErrorEventEntity> recent(int limit) {
        int capped = Math.min(Math.max(limit, 1), 500);
        return repo.findAllByOrderByIdDesc(PageRequest.of(0, capped));
    }

    @Scheduled(cron = "${app.ops.error-purge-cron:0 30 4 * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void purge() {
        try {
            int gone = repo.deleteByAtBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
            if (gone > 0) {
                log.info("ErrorLog purge: removed {} event(s) older than {}d", gone, retentionDays);
            }
        } catch (Exception e) {
            log.warn("ErrorLog purge failed: {}", e.getClass().getSimpleName());
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
