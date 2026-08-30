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
 * One executed HTS trade, from entry to close. Written by {@code HtsExecutionGate}
 * on a successful order and flipped {@code OPEN -> CLOSED} by
 * {@code HtsPositionMonitor} once the broker no longer reports the deal open.
 * Tagged with the timeframe model ({@code variant} + {@code htf}/{@code ltf}) and
 * the account it ran on, so every strategy variant is trackable on its own.
 */
@Entity
@Table(name = "hts_trades")
@Getter
@Setter
public class HtsTradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String variant;

    @Column(length = 8)
    private String htf;

    @Column(length = 8)
    private String ltf;

    @Column(length = 16)
    private String book;

    @Column(name = "account_name", length = 64)
    private String accountName;

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

    private Double size;

    @Column(name = "deal_id", length = 64)
    private String dealId;

    @Column(name = "deal_reference", length = 64)
    private String dealReference;

    /** Execution-TF bar close that produced the signal — the idempotency key. */
    @Column(name = "bar_time")
    private Instant barTime;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** OPEN / CLOSED / ERROR. */
    @Column(nullable = false, length = 16)
    private String status;

    // ---- runner management (one position: half off at TP1, the rest trails) ----

    /** Size still open after the TP1 partial close (null until TP1). */
    @Column(name = "remaining_size")
    private Double remainingSize;

    @Column(name = "tp1_at")
    private Instant tp1At;

    /** Realised P/L from the TP1 half. */
    @Column(name = "tp1_pnl")
    private Double tp1Pnl;

    /** Current trailing stop on the runner (what the broker stop is amended to). */
    @Column(name = "runner_stop")
    private Double runnerStop;

    @Column(name = "exit_price")
    private Double exitPrice;

    @Column(name = "exit_at")
    private Instant exitAt;

    /** Signed result in units of the entry→stop distance (a full stop-out ≈ −1.0). */
    @Column(name = "r_multiple")
    private Double rMultiple;

    /** Realised P/L in the account currency. */
    private Double pnl;

    @Column(name = "pnl_ccy", length = 8)
    private String pnlCcy;

    /** STOP / TARGET / MANUAL / UNKNOWN. */
    @Column(name = "close_reason", length = 32)
    private String closeReason;

    /** External-sink handle (E-2 Notion). */
    @Column(name = "notion_page_id", length = 64)
    private String notionPageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
