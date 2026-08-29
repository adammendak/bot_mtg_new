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
@Table(name = "sdd_signals")
@Getter
@Setter
public class SddSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id")
    private Long scanId;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(length = 64)
    private String epic;

    @Column(length = 8)
    private String direction;

    @Column(name = "full_stack", nullable = false)
    private boolean fullStack;

    @Column(nullable = false)
    private boolean flip;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
