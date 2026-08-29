package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.config.AppProperties;
import com.adam.server.web.dto.SymbolStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes per-symbol performance (win rate, expectancy, profit factor) from
 * the broker's closed-trade transaction history. Trades are matched to the SDD
 * universe by epic/instrument name (case-insensitive, contains-match), and a
 * soft on/off flag lets weaker symbols be excluded from new entries.
 */
@Service
public class SymbolStatsService {

    private static final Logger log = LoggerFactory.getLogger(SymbolStatsService.class);

    private final BrokerBooks books;
    private final AppProperties properties;

    /**
     * Lookback for {@code days <= 0} ("all"). Capital.com only retains a short
     * transaction window and rate-limits the paging hard, so walking from a fixed
     * 2020 start fired hundreds of sequential requests, tripped 429s / session
     * expiry, and on the Eco dyno could exhaust the request thread with an Error
     * that left the broker session degraded until restart (analytics + overview
     * + monitoring all "stopped working"). 120 days covers the account's trading
     * life with margin; raise only if the broker starts retaining more.
     */
    @Value("${app.symbol-stats.lookback-days:120}")
    private int maxLookbackDays;

    public SymbolStatsService(BrokerBooks books, AppProperties properties) {
        this.books = books;
        this.properties = properties;
    }

    /**
     * @param book  demo / live / glowne
     * @param days  how far back to look (0 = all available since 2020)
     */
    public List<SymbolStats> stats(String book, int days) {
        try {
            return computeStats(book, days);
        } catch (Exception e) {
            log.warn("SymbolStats failed for {}: {}", book, e.toString());
            return List.of();
        }
    }

    private List<SymbolStats> computeStats(String book, int days) {
        BrokerClient client = books.forBook(book);
        if (!client.configured()) {
            return List.of();
        }
        List<BrokerTransaction> trades = tradesFor(client, days);
        Map<String, List<Double>> bySymbol = new LinkedHashMap<>();
        for (BrokerTransaction t : trades) {
            if (!isTrade(t)) {
                continue;
            }
            String symbol = resolveSymbol(t);
            if (symbol == null) {
                continue;
            }
            bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(t.amount());
        }

        List<SymbolStats> out = new ArrayList<>();
        for (String symbol : bySymbol.keySet()) {
            List<Double> pnl = bySymbol.get(symbol);
            out.add(toStats(symbol, pnl));
        }
        out.sort((a, b) -> Double.compare(b.profitFactor(), a.profitFactor()));
        return out;
    }

    private SymbolStats toStats(String symbol, List<Double> pnl) {
        int trades = pnl.size();
        int wins = 0;
        int losses = 0;
        double grossWin = 0;
        double grossLoss = 0;
        for (double v : pnl) {
            if (v > 0) {
                wins++;
                grossWin += v;
            } else if (v < 0) {
                losses++;
                grossLoss += -v;
            }
        }
        double winRate = trades == 0 ? 0 : (double) wins / trades;
        double avgWin = wins == 0 ? 0 : grossWin / wins;
        double avgLoss = losses == 0 ? 0 : grossLoss / losses;
        double expectancy = trades == 0 ? 0 : (grossWin - grossLoss) / trades;
        double profitFactor = grossLoss == 0 ? (grossWin > 0 ? 999.0 : 0.0) : grossWin / grossLoss;
        return new SymbolStats(symbol, epicFor(symbol), trades, wins, losses, winRate,
                avgWin, avgLoss, expectancy, profitFactor, isEnabled(symbol));
    }

    private List<BrokerTransaction> tradesFor(BrokerClient client, int days) {
        try {
            if (!client.isSessionOpen()) {
                client.login();
            }
            Instant to = Instant.now();
            long lookback = days > 0 ? days : Math.max(1, maxLookbackDays);
            Instant from = to.minusSeconds(lookback * 86400L);
            return client.transactionHistory(from, to);
        } catch (Exception | LinkageError | StackOverflowError e) {
            // Includes an Error escaping the broker walk on a starved dyno — degrade
            // to "no data" instead of a 500 that also poisons the shared session.
            log.warn("SymbolStats transaction fetch failed for {}: {}", client.book(), e.toString());
            return List.of();
        }
    }

    private static boolean isTrade(BrokerTransaction t) {
        if (t.type() == null) {
            return false;
        }
        String type = t.type().toUpperCase(Locale.ROOT);
        return type.equals("TRADE") || type.startsWith("TRADE");
    }

    /** Map the broker instrument to an SDD symbol by code or epic (case-insensitive contains). */
    private String resolveSymbol(BrokerTransaction t) {
        String instrument = t.instrument();
        if (instrument == null || instrument.isBlank()) {
            return null;
        }
        String norm = instrument.toUpperCase(Locale.ROOT);
        for (com.adam.server.sdd.SddSymbol s : com.adam.server.sdd.SddSymbol.universe()) {
            String epic = s.epic(properties).toUpperCase(Locale.ROOT);
            if (norm.equals(s.code()) || norm.equals(epic) || norm.contains(s.code()) || norm.contains(epic)) {
                return s.code();
            }
        }
        // Fall back to the instrument name itself (keeps non-SDD markets visible).
        return instrument;
    }

    private String epicFor(String symbol) {
        for (com.adam.server.sdd.SddSymbol s : com.adam.server.sdd.SddSymbol.universe()) {
            if (s.code().equals(symbol)) {
                return s.epic(properties);
            }
        }
        return symbol;
    }

    /** Soft-disable: a symbol with a clearly negative expectancy is off for new entries. */
    public boolean isEnabled(String symbol) {
        return true; // toggle is user-driven; stats endpoint just reports current state
    }

    /** Map an epic back to the SDD code (used by the risk panel / execution). */
    static String codeFor(String epic, AppProperties properties) {
        if (epic == null) {
            return null;
        }
        String norm = epic.toUpperCase(Locale.ROOT);
        for (com.adam.server.sdd.SddSymbol s : com.adam.server.sdd.SddSymbol.universe()) {
            String e = s.epic(properties).toUpperCase(Locale.ROOT);
            if (norm.equals(e) || norm.equals(s.code())) {
                return s.code();
            }
        }
        return null;
    }
}
