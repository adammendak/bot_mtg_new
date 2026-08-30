package com.adam.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One recorded failure (E-5). Written best-effort by the scheduler catch blocks,
 * the HTS execution gate and the scheduler watchdog so "what last went wrong"
 * outlives Heroku's rotating log. {@code source} is the subsystem
 * (sdd-scan / hts-scan / hts-monitor / hts-exec / watchdog), {@code scope} the
 * book or variant, {@code detail} the symbol or a short tag.
 */
@Entity
@Table(name = "error_events")
@Getter
@Setter
public class ErrorEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(length = 32)
    private String scope;

    @Column(length = 64)
    private String detail;

    @Column(length = 128)
    private String exception;

    @Column(length = 1024)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
