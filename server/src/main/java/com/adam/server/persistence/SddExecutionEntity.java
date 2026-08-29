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
 * Persisted SDD execution state so a Heroku dyno restart keeps the two-ticket
 * deal ids, the tp/trail flags and the idempotency keys. RAM stays the cache;
 * every state transition is written through to this table.
 */
@Entity
@Table(name = "sdd_execution_entries")
@Getter
@Setter
public class SddExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String book;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(length = 64)
    private String epic;

    @Column(length = 8)
    private String direction;

    @Column(name = "bar_time", nullable = false)
    private Instant barTime;

    private Double entry;

    @Column(name = "atr_h1")
    private Double atrH1;

    private Double stop;

    @Column(name = "ticket_a", length = 64)
    private String ticketA;

    @Column(name = "ticket_b", length = 64)
    private String ticketB;

    @Column(name = "two_tickets", nullable = false)
    private boolean twoTickets;

    @Column(name = "tp_filled", nullable = false)
    private boolean tpFilled;

    @Column(name = "trailing_runner", nullable = false)
    private boolean trailing;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
