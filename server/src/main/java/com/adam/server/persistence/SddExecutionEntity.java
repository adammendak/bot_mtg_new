package com.adam.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persisted SDD execution state so a Heroku dyno restart keeps the two-ticket
 * deal ids, the 2R/BE stage flags and the idempotency keys. RAM stays the cache;
 * every state transition is written through to this table.
 */
@Entity
@Table(name = "sdd_execution_entries")
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

    @Column(name = "closed_at_2r", nullable = false)
    private boolean closedAt2R;

    @Column(name = "runner_at_be", nullable = false)
    private boolean runnerAtBe;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBook() {
        return book;
    }

    public void setBook(String book) {
        this.book = book;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getEpic() {
        return epic;
    }

    public void setEpic(String epic) {
        this.epic = epic;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public Instant getBarTime() {
        return barTime;
    }

    public void setBarTime(Instant barTime) {
        this.barTime = barTime;
    }

    public Double getEntry() {
        return entry;
    }

    public void setEntry(Double entry) {
        this.entry = entry;
    }

    public Double getAtrH1() {
        return atrH1;
    }

    public void setAtrH1(Double atrH1) {
        this.atrH1 = atrH1;
    }

    public Double getStop() {
        return stop;
    }

    public void setStop(Double stop) {
        this.stop = stop;
    }

    public String getTicketA() {
        return ticketA;
    }

    public void setTicketA(String ticketA) {
        this.ticketA = ticketA;
    }

    public String getTicketB() {
        return ticketB;
    }

    public void setTicketB(String ticketB) {
        this.ticketB = ticketB;
    }

    public boolean isTwoTickets() {
        return twoTickets;
    }

    public void setTwoTickets(boolean twoTickets) {
        this.twoTickets = twoTickets;
    }

    public boolean isClosedAt2R() {
        return closedAt2R;
    }

    public void setClosedAt2R(boolean closedAt2R) {
        this.closedAt2R = closedAt2R;
    }

    public boolean isRunnerAtBe() {
        return runnerAtBe;
    }

    public void setRunnerAtBe(boolean runnerAtBe) {
        this.runnerAtBe = runnerAtBe;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
