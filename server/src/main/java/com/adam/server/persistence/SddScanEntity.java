package com.adam.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sdd_scans")
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

    public Long getId() {
        return id;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(Instant scannedAt) {
        this.scannedAt = scannedAt;
    }

    public String getBrokerId() {
        return brokerId;
    }

    public void setBrokerId(String brokerId) {
        this.brokerId = brokerId;
    }

    public String getBrokerName() {
        return brokerName;
    }

    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }

    public boolean isExecutionEnabled() {
        return executionEnabled;
    }

    public void setExecutionEnabled(boolean executionEnabled) {
        this.executionEnabled = executionEnabled;
    }

    public boolean isNewsBlackout() {
        return newsBlackout;
    }

    public void setNewsBlackout(boolean newsBlackout) {
        this.newsBlackout = newsBlackout;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
