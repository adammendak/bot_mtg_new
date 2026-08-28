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
     * @param book   "demo" or "live"
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

        // Latest transaction we already have (as a starting backstop).
        LocalDate lastKnown = lastSnapshotDate(book);
        // Capital.com rejects ranges ending "now" — end at yesterday (UTC day boundary
        // minus a little) and start from the last known snapshot day or a sane fallback.
        LocalDate today = LocalDate.now(ZONE);
        Instant earliest = lastKnown == null
                ? Instant.parse("2026-08-20T00:00:00Z")
                : lastKnown.minusDays(1).atStartOfDay(ZONE).toInstant();
        Instant to = today.minusDays(1).atTime(23, 59, 59).atZone(ZONE).toInstant();

        List<BrokerTransaction> tx;
        try {
            tx = client.transactionHistory(earliest, to);
        } catch (Exception e) {
            log.warn("EquityHistory sync failed to fetch transactions for {}: {}", book, e.getMessage());
            return SyncResult.failed("transaction fetch failed: " + e.getClass().getSimpleName());
        }
        log.info("EquityHistory sync [{}]: fetched {} transactions from {} to {}", book, tx.size(), earliest, to);
        if (tx.isEmpty() && lastKnown != null) {
            return SyncResult.ok("no new transactions since " + lastKnown, 0, 0);
        }
        if (tx.isEmpty()) {
            return SyncResult.ok("broker returned no transaction history for " + book, 0, 0);
        }
        log.info("EquityHistory sync [{}]: first tx at {}, last tx at {}", book, tx.get(0).time(), tx.get(tx.size() - 1).time());

        // Group cash impact per day (UTC -> Warsaw).
        Map<LocalDate, Double> dailyPnl = new LinkedHashMap<>();
        for (BrokerTransaction t : tx) {
            LocalDate day = t.time().atZone(ZONE).toLocalDate();
            double impact = t.amount();
            if ("DEPOSIT".equalsIgnoreCase(t.type()) || "WITHDRAWAL".equalsIgnoreCase(t.type())) {
                // deposits/withdrawals change equity but are not P/L; keep them in equity only
                dailyPnl.merge(day, 0.0, Double::sum);
                continue;
            }
            dailyPnl.merge(day, impact, Double::sum);
        }

        // Reconstruct equity backwards from the current balance.
        List<Map.Entry<LocalDate, Double>> days = new ArrayList<>(dailyPnl.entrySet());
        days.sort(Map.Entry.comparingByKey());
        LocalDate firstDay = days.isEmpty() ? LocalDate.now(ZONE) : days.get(0).getKey();

        double[] cumulativeAfter = new double[days.size() + 1];
        cumulativeAfter[days.size()] = 0;
        for (int i = days.size() - 1; i >= 0; i--) {
            cumulativeAfter[i] = cumulativeAfter[i + 1] + days.get(i).getValue();
        }

        if (replace) {
            snapshots.deleteByBook(book);
        }

        // Walk from first day to today; persist a snapshot for each day.
        double runningPnl = 0;
        int dayIndex = 0;
        int written = 0;
        int skipped = 0;
        LocalDate cursor = firstDay;
        while (!cursor.isAfter(today)) {
            // equity at end of this day = current balance - (all P/L strictly after this day)
            double pnlAfter = dayIndex < days.size() ? cumulativeAfter[dayIndex + 1] : 0;
            double equity = account.balance() - pnlAfter;
            if (dayIndex < days.size() && days.get(dayIndex).getKey().equals(cursor)) {
                runningPnl += days.get(dayIndex).getValue();
                dayIndex++;
            }
            double dayPnl = runningPnl; // P/L realized up to and including this day (approx daily = cumulative diff below)

            boolean exists = lastKnown != null && !cursor.isAfter(lastKnown) && !replace;
            if (!exists) {
                if (persistDay(book, client, accountName, currency, cursor, equity, dayPnl)) {
                    written++;
                } else {
                    skipped++;
                }
            } else {
                skipped++;
            }
            cursor = cursor.plusDays(1);
        }

        return SyncResult.ok("synced " + book + " from " + firstDay + " to " + today, written, skipped);
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
