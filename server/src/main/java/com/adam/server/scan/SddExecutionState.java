package com.adam.server.scan;

import com.adam.server.broker.Direction;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tracking of SDD execution entries, per book.
 *
 * <p>Each fullStack entry is split into up to two tickets: {@code ticketA} (the
 * one closed whole at 2R) and {@code ticketB} (the runner, stopped to BE then
 * H1-trailed). Idempotency is keyed off {@code book|symbol|direction|barTime} so a
 * webhook retry or a re-scan of the same M15 bar never opens a second entry.
 *
 * <p>State is intentionally in-memory: on a dyno restart the broker's open
 * positions are re-read and treated conservatively (name open → no re-entry).
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
        public final String ticketA; // closed whole at 2R
        public final String ticketB; // runner (BE + H1 trail); may be null if single ticket
        public final boolean twoTickets;
        public volatile boolean closedAt2R;
        public volatile boolean runnerAtBe;

        public Entry(String book, String symbol, String epic, Direction direction, Instant barTime,
                     double entry, double atr, double stop, String ticketA, String ticketB, boolean twoTickets) {
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
        }

        /** A name whose 2R has already been taken and whose runner is at BE may be re-entered. */
        public boolean allowsPyramid() {
            return closedAt2R && runnerAtBe;
        }
    }

    /** book -> symbol -> entry */
    private final Map<String, Map<String, Entry>> entries = new ConcurrentHashMap<>();
    /** idempotency keys: book|symbol|direction|barTimeEpoch */
    private final Set<String> placedKeys = ConcurrentHashMap.newKeySet();

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

    public void put(Entry entry) {
        entries.computeIfAbsent(entry.book, k -> new ConcurrentHashMap<>()).put(entry.symbol, entry);
        placedKeys.add(placedKey(entry.book, entry.symbol, entry.direction, entry.barTime));
    }

    public void remove(String book, String symbol) {
        Map<String, Entry> m = entries.get(book);
        if (m != null) {
            m.remove(symbol);
        }
    }

    public boolean alreadyPlaced(String book, String symbol, Direction direction, Instant barTime) {
        return placedKeys.contains(placedKey(book, symbol, direction, barTime));
    }

    /** Unique SDD names currently open per book (from tracked entries, re-entry-eligible excluded). */
    public int openNameCount(String book) {
        int n = 0;
        for (Entry e : entriesFor(book)) {
            if (!e.allowsPyramid()) {
                n++;
            }
        }
        return n;
    }

    public boolean isNameOpen(String book, String symbol) {
        Entry e = get(book, symbol);
        return e != null && !e.allowsPyramid();
    }

    public void clear() {
        entries.clear();
        placedKeys.clear();
    }

    static String placedKey(String book, String symbol, Direction direction, Instant barTime) {
        return book + "|" + symbol + "|" + (direction == null ? "?" : direction.name()) + "|"
                + (barTime == null ? 0 : barTime.toEpochMilli());
    }
}
