package com.adam.server.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On application ready (e.g. right after a Heroku dyno restart), reload the persisted
 * SDD execution entries from Postgres and reconcile them with the broker's open
 * positions, so 2R/BE management and idempotency survive restarts. Runs regardless of
 * EXECUTION_ENABLED (state is always kept current; placement is still gated by the flag).
 */
@Component
public class ExecutionStateHydrator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateHydrator.class);

    private final ExecutionGate executionGate;

    public ExecutionStateHydrator(ExecutionGate executionGate) {
        this.executionGate = executionGate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            executionGate.reloadAndReconcile();
            log.info("Execution state reloaded from DB and reconciled with broker positions");
        } catch (Exception e) {
            log.warn("Execution state reload/reconcile failed: {}", e.getClass().getSimpleName());
        }
    }
}
