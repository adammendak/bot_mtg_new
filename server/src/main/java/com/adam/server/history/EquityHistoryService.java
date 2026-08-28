package com.adam.server.history;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.persistence.BrokerSnapshotEntity;
import com.adam.server.persistence.BrokerSnapshotRepository;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.web.dto.AccountView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs daily equity from the broker's transaction history and stores it
 * as {@code broker_snapshots} rows. Used by the manual "Sync history" endpoint,
 * the {@code @Scheduled} nightly job, and on application startup.
 *
 * <p>How it works:
 * <ol>
 *   <li>Reads the broker's current account balance (the equity as of now).</li>
 *   <li>Fetches all transactions from the broker (paginated).</li>
 *   <li>Walks backwards day by day: {@code equity(end of day D) = current_balance
 *       - sum(transactions after day D)}. Day P/L = P/L-type transactions on day D.</li>
 *   <li>Persists one snapshot per calendar day per book (Warsaw timezone),
 *       leaving existing rows untouched unless {@code replace} is requested.</li>
 * </ol>
 */
@Service
public class EquityHistoryService {

    private static final Logger log = LoggerFactory.getLogger(EquityHistoryService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final BrokerBooks books;
    private final BrokerSnapshotRepository snapshots;
    private final RiskPolicy risk;
    private final ObjectMapper mapper;

    public EquityHistoryService(
            BrokerBooks books,
            BrokerSnapshotRepository snapshots,
            RiskPolicy risk,
            ObjectMapper mapper
    ) {
        this.books = books;
        this.snapshots = snapshots;
        this.risk = risk;
        this.mapper = mapper;
    }

    /**
     * @param book   "demo", "live" or "glowne"
     * @param replace when true, deletes existing snapshots for the book first
     * @return a short human summary of what was done
     */
    @Transactional
    public SyncResult sync(String book, boolean replace) {
        BrokerClient client = books.forBook(book);
        if (!client.configured()) {
            return SyncResult.failed("broker not configured for book " + book);
        }
        Account account = currentAccount(client);
        if (account == null || Double.isNaN(account.balance())) {
            return SyncResult.failed("could not read current account balance for " + book);
        }
        String accountName = account.name() == null ? book : account.name();
        String currency = account.currency() == null ? "" : account.currency();

        LocalDate today = LocalDate.now(ZONE);
        // Full backfill: start from 2020 so the earliest available transactions
        // (the whole life of the account) are pulled. Capital.com rejects ranges
        // ending exactly "now", so the range ends at yesterday and today's P/L
        // (usually none) is handled by the daily snapshots the scanner writes.
        Instant earliest = Instant.parse("2020-01-01T00:00:00Z");
        Instant to = today.minusDays(1).atTime(23, 59, 59).atZone(ZONE).toInstant();

        List<BrokerTransaction> tx;
        try {
            tx = client.transactionHistory(earliest, to);
        } catch (Exception e) {
            log.warn("EquityHistory sync failed to fetch transactions for {}: {}", book, e.getMessage());
            return SyncResult.failed("transaction fetch failed: " + e.getClass().getSimpleName());
        }
        log.info("EquityHistory sync [{}]: fetched {} transactions from {} to {}", book, tx.size(), earliest, to);
        if (tx.isEmpty()) {
            return SyncResult.ok("broker returned no transaction history for " + book, 0, 0);
        }
        log.info("EquityHistory sync [{}]: first tx at {}, last tx at {}", book, tx.get(0).time(), tx.get(tx.size() - 1).time());

        // Group daily P/L (non-deposit) per Warsaw day.
        Map<LocalDate, Double> dailyPnl = new LinkedHashMap<>();
        for (BrokerTransaction t : tx) {
            if ("DEPOSIT".equalsIgnoreCase(t.type()) || "WITHDRAWAL".equalsIgnoreCase(t.type())) {
                continue;
            }
            LocalDate day = t.time().atZone(ZONE).toLocalDate();
            dailyPnl.merge(day, t.amount(), Double::sum);
        }
        if (dailyPnl.isEmpty()) {
            return SyncResult.ok("no P/L transactions for " + book, 0, 0);
        }

        // All days from earliest tx day .. today (fill gaps with 0 P/L).
        LocalDate firstDay = dailyPnl.keySet().stream().min(LocalDate::compareTo).orElse(today);
        List<LocalDate> allDays = new ArrayList<>();
        LocalDate cursor = firstDay;
        while (!cursor.isAfter(today)) {
            allDays.add(cursor);
            cursor = cursor.plusDays(1);
        }

        // P/L after each day (backwards from the end): pnlAfter[i] = sum of daily P/L for days strictly after allDays[i].
        double[] pnlAfter = new double[allDays.size() + 1];
        pnlAfter[allDays.size()] = 0;
        for (int i = allDays.size() - 1; i >= 0; i--) {
            double day = dailyPnl.getOrDefault(allDays.get(i), 0.0);
            pnlAfter[i] = pnlAfter[i + 1] + day;
        }
        // equity at end of day i = current balance - (P/L realized after that day)
        // i.e. balance today already includes all P/L up to today; going back, subtract each later day's P/L.
        // equity(end of day) = balance - pnlAfter[i+1]  (P/L strictly after this day)
        // dayPnl(end of day) = dailyPnl of that day (the P/L realized during it)

        if (replace) {
            snapshots.deleteByBook(book);
        }

        int written = 0;
        int skipped = 0;
        for (int i = 0; i < allDays.size(); i++) {
            LocalDate day = allDays.get(i);
            // Equity at the end of this day = current balance - (P/L that happened strictly AFTER this day).
            double equity = account.balance() - pnlAfter[i + 1];
            double dayPnl = dailyPnl.getOrDefault(day, 0.0);
            // Skip only if a snapshot for this exact day already exists (e.g. from the
            // 15-min scanner); backfill missing days (24..26 Aug) regardless of the newest row.
            Instant dayStart = day.atStartOfDay(ZONE).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(ZONE).toInstant();
            boolean exists = !replace && snapshots.existsByBookAndCapturedAtBetween(book, dayStart, dayEnd);
            if (!exists) {
                if (persistDay(book, client, accountName, currency, day, equity, dayPnl)) {
                    written++;
                } else {
                    skipped++;
                }
            } else {
                skipped++;
            }
        }

        return SyncResult.ok("synced " + book + " from " + firstDay + " to " + today, written, skipped);
    }

    /**
     * Syncs all books (demo, live, glowne) in one call. Each book is isolated:
     * a failure on one book never aborts the others. Unconfigured books simply
     * return a {@code failed} result and are skipped.
     */
    public List<SyncResult> syncAll(boolean replace) {
        List<SyncResult> results = new ArrayList<>();
        for (String book : new String[]{"demo", "live", "glowne"}) {
            try {
                results.add(sync(book, replace));
            } catch (Exception e) {
                log.warn("EquityHistory syncAll failed for {}: {}", book, e.getClass().getSimpleName());
                results.add(SyncResult.failed("sync " + book + " failed: " + e.getClass().getSimpleName()));
            }
        }
        return results;
    }

    private boolean persistDay(String book, BrokerClient client, String accountName, String currency,
                               LocalDate day, double equity, double dayPnl) {
        try {
            BrokerSnapshotEntity row = new BrokerSnapshotEntity();
            row.setBook(book);
            row.setBroker(client.id());
            row.setAccountName(accountName);
            row.setEquity(round2(equity));
            row.setAvailable(round2(Math.max(0, equity * 0.95)));
            row.setDayPnl(round2(dayPnl));
            row.setCurrency(currency);
            row.setConnected(true);
            row.setCapturedAt(day.atTime(21, 0).atZone(ZONE).toInstant());
            row.setPayload(mapper.writeValueAsString(Map.of(
                    "book", book,
                    "broker", client.id(),
                    "accountName", accountName,
                    "equity", round2(equity),
                    "dayPnl", round2(dayPnl),
                    "currency", currency,
                    "connected", true
            )));
            snapshots.save(row);
            return true;
        } catch (Exception e) {
            log.warn("EquityHistory snapshot write failed for {} {}: {}", book, day, e.getClass().getSimpleName());
            return false;
        }
    }

    private LocalDate lastSnapshotDate(String book) {
        return snapshots.findTopByBookOrderByCapturedAtDesc(book)
                .map(e -> e.getCapturedAt().atZone(ZONE).toLocalDate())
                .orElse(null);
    }

    private Account currentAccount(BrokerClient client) {
        try {
            if (!client.isSessionOpen()) {
                client.login();
            }
            List<Account> accounts = client.accounts();
            Account chosen = null;
            if ("live".equals(client.book())) {
                RiskPolicy.LivePick pick = risk.pickLiveAccount(accounts);
                if (pick.visible()) {
                    chosen = pick.account();
                }
            } else if ("glowne".equals(client.book())) {
                chosen = risk.pickGlowneAccount(accounts);
            } else {
                chosen = risk.pickDemoAccount(accounts);
            }
            // Must select the account in the session, or the transaction history
            // comes from the session's default (first) account instead.
            try {
                client.selectAccount(chosen.id());
            } catch (Exception e) {
                log.warn("EquityHistory selectAccount failed for {}: {}", client.book(), e.getClass().getSimpleName());
            }
            return chosen;
        } catch (Exception e) {
            log.warn("EquityHistory could not read account for {}: {}", client.book(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public record SyncResult(String status, String message, int written, int skipped) {
        static SyncResult ok(String message, int written, int skipped) {
            return new SyncResult("ok", message, written, skipped);
        }

        static SyncResult failed(String message) {
            return new SyncResult("error", message, 0, 0);
        }
    }
}
