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
 * One SDD-SWING (H1) signal, stored so the swing strategy can be compared with
 * SDD-M15 over the same period. Written by the swing scan; never traded from
 * here (execution is a separate, opt-in step).
 */
@Entity
@Table(name = "swing_signals")
@Getter
@Setter
public class SwingSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(length = 64)
    private String epic;

    @Column(length = 8)
    private String direction;

    private Double entry;

    @Column(name = "stop_level")
    private Double stopLevel;

    @Column(name = "target_level")
    private Double targetLevel;

    @Column(name = "h4_trend", length = 8)
    private String h4Trend;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
