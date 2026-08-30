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
 * One HTS ("wstęgi") signal, stored so the 3rd strategy can be compared with
 * SDD-M15 and SDD-SWING over the same period. Written by the HTS scan; never
 * traded from here (execution is a separate, opt-in step).
 */
@Entity
@Table(name = "hts_signals")
@Getter
@Setter
public class HtsSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    /** Timeframe model: CORE (H4/M15) / SWING (D1/H1) / FAST (H1/M5). */
    @Column(length = 16)
    private String variant;

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

    @Column(name = "htf_up")
    private Boolean htfUp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
