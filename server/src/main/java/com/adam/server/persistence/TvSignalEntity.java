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

/** One TradingView alert event (entry / tp / invalid) for an external-indicator forward test. */
@Entity
@Table(name = "tv_signals")
@Getter
@Setter
public class TvSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = 32)
    private String strat;

    /** entry | tp | invalid */
    @Column(nullable = false, length = 16)
    private String event;

    /** BUY | SELL */
    @Column(nullable = false, length = 8)
    private String side;

    @Column(name = "raw_symbol", length = 48)
    private String rawSymbol;

    /** Normalised to the bot's codes (US500, GER40, XAU, BTC, ...). */
    @Column(nullable = false, length = 24)
    private String symbol;

    @Column(length = 16)
    private String tf;

    @Column(nullable = false)
    private double price;

    private Double sl;

    private Double tp;

    @Column(name = "bar_time", length = 40)
    private String barTime;

    @Column(name = "dedupe_key", nullable = false, length = 200, unique = true)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
