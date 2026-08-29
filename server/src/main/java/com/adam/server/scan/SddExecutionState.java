package com.adam.server.scan;

import com.adam.server.broker.Direction;
import com.adam.server.persistence.SddExecutionEntity;
import com.adam.server.persistence.SddExecutionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SDD execution entries, per book. RAM is a cache; Postgres is the source of truth
 * (same DATABASE_URL / Liquibase as broker_snapshots). Every transition is written
 * through so a Heroku dyno restart keeps the two-ticket deal ids, the tp/trail
 * flags and the idempotency keys.
 *
 * <p>Each fullStack entry is split into up to two tickets: {@code ticketA} (the TP
 * ticket, has the hard 1R profit level) and {@code ticketB} (the runner, no TP,
 * H1-trailed after {@code tpFilled}; may be null when the entry could not split).
 * Idempotency is keyed off {@code book|symbol|direction|barTime} so a webhook retry or
 * a re-scan of the same M15 bar never opens a second entry. A name stays open until
 * the broker no longer has this entry's tickets. Idempotency keys survive row removal
 * so the same bar cannot re-enter; a later fullStack bar can.
 */
@Component
public class SddExecutionState {

    public static class Entry {
        public final String book;
        public final String symbol;
        public final String epic;
        public final Direction direction;
        public final Instant barTime;
        public final double entry;
        public final double atr;   // 1R = H1 ATR
        public final double stop;  // 2.5 x ATR stop at entry
        public final String ticketA; // TP ticket (has the 1R profit level)
        public final String ticketB; // runner (no TP); may be null if single ticket
        public final boolean twoTickets;
        public volatile boolean tpFilled; // the TP ticket is gone on the broker (took 1R)
        public volatile boolean trailing; // the runner is being H1-trailed

        public Entry(String book, String symbol, String epic, Direction direction, Instant barTime,
                     double entry, double atr, double stop, String ticketA, String ticketB, boolean twoTickets) {
            this(book, symbol, epic, direction, barTime, entry, atr, stop, ticketA, ticketB, twoTickets,
                    false, false);
        }

        public Entry(String book, String symbol, String epic, Direction direction, Instant barTime,
                     double entry, double atr, double stop, String ticketA, String ticketB, boolean twoTickets,
                     boolean tpFilled, boolean trailing) {
            this.book = book;
            this.symbol = symbol;
            this.epic = epic;
            this.direction = direction;
            this.barTime = barTime;
            this.entry = entry;
            this.atr = atr;
            this.stop = stop;
            this.ticketA = ticketA;
            this.ticketB = ticketB;
            this.twoTickets = twoTickets;
            this.tpFilled = tpFilled;
            this.trailing = trailing;
        }

        /** A name is open until BOTH tickets are gone — never allows pyramid/re-entry. */
        public boolean isNameOpen() {
            return true;
        }
    }

    private final SddExecutionRepository repository;
    /** book -> symbol -> entry (RAM cache) */
    private final Map<String, Map<String, Entry>> entries = new ConcurrentHashMap<>();
    /** idempotency keys: book|symbol|direction|barTimeEpoch */
    private final Set<String> placedKeys = ConcurrentHashMap.newKeySet();

    public SddExecutionState(SddExecutionRepository repository) {
        this.repository = repository;
    }

    /**
     * Reload all in-progress entries from the DB (called on ApplicationReady, before
     * any scan runs). Entries whose tickets are gone on the broker are reconciled
     * separately by the ExecutionGate; here we only hydrate RAM + idempotency keys.
     */
    public void loadFromDb() {
        entries.clear();
        placedKeys.clear();
        for (SddExecutionEntity row : repository.findAll()) {
            Entry e = toEntry(row);
            if (e != null) {
                entries.computeIfAbsent(e.book, k -> new ConcurrentHashMap<>()).put(e.symbol, e);
                placedKeys.add(placedKey(e.book, e.symbol, e.direction, e.barTime));
            }
        }
    }

    public List<Entry> entriesFor(String book) {
        Map<String, Entry> m = entries.get(book);
        if (m == null) {
            return List.of();
        }
        return new ArrayList<>(m.values());
    }

    public Entry get(String book, String symbol) {
        Map<String, Entry> m = entries.get(book);
        return m == null ? null : m.get(symbol);
    }

    /**
     * Write-through: persist a fresh entry (upsert by book+symbol). RAM and the
     * idempotency key are updated first so a later persist failure cannot look
     * like "never placed" while Capital tickets are already live.
     */
    @Transactional
    public void put(Entry entry) {
        remember(entry);
        upsert(entry);
    }

    /**
     * RAM + same-bar idempotency only. Used when the DB write fails after a
     * successful Capital fill so the name stays tracked for this process.
     */
    public void remember(Entry entry) {
        entries.computeIfAbsent(entry.book, k -> new ConcurrentHashMap<>()).put(entry.symbol, entry);
        placedKeys.add(placedKey(entry.book, entry.symbol, entry.direction, entry.barTime));
    }

    /**
     * Write-through: drop the in-progress row. The idempotency key is kept so the
     * same M15 bar cannot re-enter; a later fullStack bar uses a different key.
     */
    @Transactional
    public void remove(String book, String symbol) {
        Map<String, Entry> m = entries.get(book);
        if (m != null) {
            m.remove(symbol);
        }
        repository.deleteByBookAndSymbol(book, symbol);
    }

    /** Write-through: persist a stage change (tpFilled / trailing). */
    @Transactional
    public void update(Entry entry) {
        upsert(entry);
    }

    public boolean alreadyPlaced(String book, String symbol, Direction direction, Instant barTime) {
        return placedKeys.contains(placedKey(book, symbol, direction, barTime));
    }

    /** Unique SDD names currently open per book (all tracked entries count). */
    public int openNameCount(String book) {
        return entriesFor(book).size();
    }

    public boolean isNameOpen(String book, String symbol) {
        return get(book, symbol) != null;
    }

    public void clear() {
        entries.clear();
        placedKeys.clear();
        repository.deleteAll();
    }

    static String placedKey(String book, String symbol, Direction direction, Instant barTime) {
        return book + "|" + symbol + "|" + (direction == null ? "?" : direction.name()) + "|"
                + (barTime == null ? 0 : barTime.toEpochMilli());
    }

    private static Entry toEntry(SddExecutionEntity row) {
        try {
            Direction dir = row.getDirection() == null ? null : Direction.valueOf(row.getDirection());
            return new Entry(
                    row.getBook(),
                    row.getSymbol(),
                    row.getEpic(),
                    dir,
                    row.getBarTime(),
                    row.getEntry() == null ? 0 : row.getEntry(),
                    row.getAtrH1() == null ? 0 : row.getAtrH1(),
                    row.getStop() == null ? 0 : row.getStop(),
                    row.getTicketA(),
                    row.getTicketB(),
                    row.isTwoTickets(),
                    row.isTpFilled(),
                    row.isTrailing()
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Merge into the existing book+symbol row (or insert). Never {@code persist()} a
     * new entity while a row for that name already exists — that was delete-then-
     * {@code save(new)} and the derived delete threw {@code InvalidDataAccessApiUsageException}
     * outside a transaction. ticketB may be null (single-ticket entry).
     */
    private void upsert(Entry e) {
        List<SddExecutionEntity> existing = repository.findByBookAndSymbol(e.book, e.symbol);
        SddExecutionEntity row = existing.isEmpty() ? new SddExecutionEntity() : existing.get(0);
        copyOnto(e, row);
        repository.save(row);
        for (int i = 1; i < existing.size(); i++) {
            repository.delete(existing.get(i));
        }
    }

    private static void copyOnto(Entry e, SddExecutionEntity row) {
        row.setBook(e.book);
        row.setSymbol(e.symbol);
        row.setEpic(e.epic);
        row.setDirection(e.direction == null ? null : e.direction.name());
        row.setBarTime(e.barTime);
        row.setEntry(e.entry);
        row.setAtrH1(e.atr);
        row.setStop(e.stop);
        row.setTicketA(e.ticketA);
        row.setTicketB(e.ticketB);
        row.setTwoTickets(e.twoTickets);
        row.setTpFilled(e.tpFilled);
        row.setTrailing(e.trailing);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(Instant.now());
        }
    }
}
