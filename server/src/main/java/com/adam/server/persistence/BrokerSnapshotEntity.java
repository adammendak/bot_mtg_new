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
@Table(name = "broker_snapshots")
@Getter
@Setter
public class BrokerSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String book;

    @Column(length = 64)
    private String broker;

    @Column(name = "account_name", length = 128)
    private String accountName;

    private Double equity;

    @Column(name = "available")
    private Double available;

    @Column(name = "day_pnl")
    private Double dayPnl;

    @Column(length = 16)
    private String currency;

    @Column(nullable = false)
    private boolean connected;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(columnDefinition = "text")
    private String payload;
}
