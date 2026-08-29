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

@Entity
@Table(name = "sdd_scans")
@Getter
@Setter
public class SddScanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    @Column(name = "broker_id", nullable = false, length = 64)
    private String brokerId;

    @Column(name = "broker_name", length = 128)
    private String brokerName;

    @Column(name = "execution_enabled", nullable = false)
    private boolean executionEnabled;

    @Column(name = "news_blackout", nullable = false)
    private boolean newsBlackout;

    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
