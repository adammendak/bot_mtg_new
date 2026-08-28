package com.adam.server.web.dto;

import java.time.Instant;

/**
 * One execution-audit event: a position lifecycle transition (placed, TP
 * ticket closed at 1R, runner trailed, stop amended, position closed). The
 * dashboard renders these as a timeline so every managed action is visible.
 */
public record AuditEvent(
        Instant at,
        String book,
        String symbol,
        String action,      // placed | tp_closed | trail | be | stop | closed | skip
        String detail
) {
}
