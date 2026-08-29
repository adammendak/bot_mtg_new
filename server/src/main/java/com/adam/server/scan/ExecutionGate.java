package com.adam.server.scan;

import com.adam.server.broker.Books;
import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.sdd.SddEngine;
import com.adam.server.sdd.SddScan;
import com.adam.server.sdd.SddSymbol;
import com.adam.server.web.dto.AccountView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places SDD-M15 fullStack entries on Capital.com DEMO and LIVE (account
 * {@code bot trading konto}) behind {@code EXECUTION_ENABLED=true}. Implements
 * Computron's method 1:1. Glowne is never executed.
 *
 * <p>Rules implemented here:
 * <ul>
 *   <li>Only a fullStack signal places; flip-but-not-fullStack never places, never flattens.</li>
 *   <li>Two separate deals (two tickets) when the per-ticket size clears
 *       {@code app.execution.min-deal-size}; a single ticket otherwise.</li>
 *   <li>Stop 2.5× H1 ATR on BOTH deals at entry. 1R = 1× H1 ATR. No broker trailingStop.</li>
 *   <li>Right after fill a hard 1R TP is set on ONE deal only (the TP ticket,
 *       {@code ticketA}); the other deal (runner, {@code ticketB}) has no TP.
 *       Capital quirk: on the TP ticket the stopLevel is PUT together with the
 *       profitLevel (setting profitLevel alone wipes the stop).</li>
 *   <li>When the TP ticket is gone on the broker (it took 1R), the runner KEEPS its
 *       original 2.5× stop (never moved to break-even, never amended to entry) and is
 *       then H1-trailed: the stop only ratchets in the trade's favour, never worse
 *       than the original 2.5× stop. Implemented with PUT stopLevel, not trailingStop.</li>
 *   <li>Single-ticket entry: that one deal gets stop 2.5× AND 1R TP PUT together; when
 *       it is gone on the broker the row is deleted (even if {@code tpFilled} is false).</li>
 *   <li>Skip if the SDD name is already open (broker positions + persisted rows); max 4
 *       unique SDD names per book. NO pyramid while a name is open — blocked until the
 *       broker no longer has this entry's tickets. Manual close / SL / TP all drop the
 *       row so a later M15 fullStack can place again. A stray open position on the same
 *       epic+direction still keeps the row. Same-bar idempotency is unchanged.</li>
 *   <li>Demo ~10 PLN (DEMO_RISK_PLN); live 1% of the bot-konto equity. Day-P/L halt for
 *       new entries: demo −30, live −18 (per book).</li>
 *   <li>Idempotent keyed on {@code book|symbol|direction|barTime} — a webhook retry or
 *       a re-scan of the same bar never opens a second entry.</li>
 *   <li>Fills and skips are POSTed to Computron (type=execution) so it audits instead
 *       of polling every 15 minutes.</li>
 *   <li>neverFlatten (TQQQ / CRCL / SPOT / SHOP) stays as a guard only — those names are
 *       gone from DEMO and from live bot-konto; nothing ever touches Glowne or those epics.</li>
 * </ul>
 */
@Component
public class ExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(ExecutionGate.class);

    private final AppProperties properties;
    private final BrokerBooks books;
    private final RiskPolicy risk;
    private final SddExecutionState state;
    private final SignalWebhookPublisher webhooks;
    private final TelegramNotifier telegram;
    private final MonitoringService monitor;
    private final Map<String, String> epicToSddName;

    public ExecutionGate(
            AppProperties properties,
            BrokerBooks books,
            RiskPolicy risk,
            SddExecutionState state,
            SignalWebhookPublisher webhooks,
            TelegramNotifier telegram,
            MonitoringService monitor
    ) {
        this.properties = properties;
        this.books = books;
        this.risk = risk;
        this.state = state;
        this.webhooks = webhooks;
        this.telegram = telegram;
        this.monitor = monitor;
        this.epicToSddName = new LinkedHashMap<>();
        for (SddSymbol symbol : SddSymbol.universe()) {
            epicToSddName.put(symbol.epic(properties).toUpperCase(), symbol.code());
        }
    }

    /**
     * After a dyno restart, reload the persisted entries from Postgres and reconcile
     * them against the broker's open positions. Only entries that have a row in
     * {@code sdd_execution_entries} are managed — leftover SDD tickets opened by
     * Computron before persistence are NOT adopted. Never touches Glowne or the
     * stocks book. Called on ApplicationReady, before any scan runs.
     */
    public void reloadAndReconcile() {
        state.loadFromDb();
        for (String book : Books.EXECUTABLE) {
            BrokerClient client = books.forBook(book);
            if (client == null || !client.configured()) {
                continue;
            }
            List<Position> open = fetchOpenPositionsOrNull(client);
            if (open == null) {
                log.warn("SDD reconcile {}: positions unavailable, leaving rows", book);
                continue;
            }
            applyBrokerTruth(client, open, false);
        }
    }

    /**
     * Run execution for one book after a scan. Only fullStack signals place; flip-only
     * signals never place and never flatten. Existing entries are managed first (TP
     * detection, runner H1-trail), then new fullStack signals are considered.
     */
    public void executeBook(String book, List<SddScan> symbols, AccountView view, boolean newsBlackout) {
        if (!properties.isExecutionEnabled()) {
            return;
        }
        BrokerClient client = books.forBook(book);
        if (client == null || !client.configured()) {
            return;
        }
        boolean live = Books.LIVE.equals(client.book());
        Account account = resolveAccount(client, live);
        List<Position> open = fetchOpenPositionsOrNull(client);
        if (open == null) {
            log.warn("Open positions unavailable for {}; not reconciling entries", book);
            open = List.of();
        } else {
            manageOpen(client, open);
        }

        String halt = view == null || view.dayPnl() == null ? null : risk.dayHalt(view.dayPnl(), live);

        if (symbols == null) {
            return;
        }
        for (SddScan scan : symbols) {
            if (!scan.fullStack()) {
                continue; // flip but not full stack: do NOT place, do NOT flatten, do NOT re-scan
            }
            String reason = maybeEnter(client, book, live, scan, open, account, newsBlackout, halt);
            if (reason == null) {
                webhooks.publishExecution(book, scan.symbol(),
                        scan.direction() == null ? null : scan.direction().name(),
                        "placed", "");
                double cash = account == null ? 0 : risk.riskAmount(account, live);
                double size = risk.sizeFor(cash, scan.oneR(), SddEngine.STOP_ATR_MULT);
                telegram.onFill(book, scan.symbol(),
                        scan.direction() == null ? null : scan.direction().name(),
                        size, scan.entry(), scan.stop());
                monitor.record(book, scan.symbol(), "placed",
                        scan.direction() + " @ " + scan.entry() + " stop " + scan.stop());
                log.info("SDD entry placed {} {} {} (tickets recorded)", book, scan.symbol(), scan.direction());
            } else {
                webhooks.publishExecution(book, scan.symbol(),
                        scan.direction() == null ? null : scan.direction().name(),
                        "skip", reason);
                monitor.record(book, scan.symbol(), "skip", reason);
                log.info("SDD entry skipped {} {}: {}", book, scan.symbol(), reason);
            }
        }
    }

    // ------------------------------------------------------------------
    // Entry placement
    // ------------------------------------------------------------------

    private String maybeEnter(BrokerClient client, String book, boolean live, SddScan scan,
                              List<Position> open, Account account, boolean newsBlackout, String halt) {
        if (newsBlackout) {
            return "news blackout";
        }
        if (halt != null) {
            return halt;
        }
        if (live) {
            String gate = risk.liveGate(account, true);
            if (gate != null) {
                return gate;
            }
        }
        if (account == null) {
            return "no account";
        }
        // Idempotency: webhook retry / second scan of the same bar must not re-enter.
        if (state.alreadyPlaced(book, scan.symbol(), scan.direction(), scan.timestamp())) {
            return "duplicate bar already placed";
        }
        // Name open / 4-name cap (broker positions + tracked state, restart-safe).
        Set<String> openNames = openSddNames(open);
        for (SddExecutionState.Entry e : state.entriesFor(book)) {
            openNames.add(e.symbol); // no pyramid: any tracked entry blocks the name
        }
        if (openNames.contains(scan.symbol())) {
            return "name already open";
        }
        if (openNames.size() >= properties.getMaxOpenNames()) {
            return "max " + properties.getMaxOpenNames() + " names open";
        }
        double cash = risk.riskAmount(account, live);
        double size = risk.sizeFor(cash, scan.oneR(), SddEngine.STOP_ATR_MULT);
        if (size <= 0) {
            return "size is zero";
        }
        try {
            return placeTickets(client, book, scan, size);
        } catch (Exception e) {
            log.warn("Entry failed for {} on {}: {}", scan.symbol(), book, e.getClass().getSimpleName());
            return "entry failed: " + e.getClass().getSimpleName();
        }
    }

    /**
     * Places one or two market tickets. Stop 2.5× H1 ATR on BOTH deals; a hard 1R TP on
     * ONE deal only (the TP ticket). On the TP ticket the stopLevel is PUT together with
     * the profitLevel (Capital quirk: profitLevel alone wipes the stop). Returns null on
     * success or a reason on failure.
     */
    String placeTickets(BrokerClient client, String book, SddScan scan, double size) {
        double perTicket = size / 2.0;
        boolean twoTickets = perTicket >= properties.getMinDealSize();
        double[] sizes = twoTickets ? new double[]{perTicket, perTicket} : new double[]{size};

        // 1R profit level on the TP ticket only.
        double oneR = scan.oneR();
        double tpLevel = scan.direction() == Direction.BUY ? scan.entry() + oneR : scan.entry() - oneR;

        String refA = null;
        String refB = null;
        int placed = 0;
        for (double s : sizes) {
            OrderRequest request;
            if (placed == 0) {
                // TP ticket: stop AND 1R profit level PUT together.
                request = new OrderRequest(scan.epic(), scan.direction(), s, null, "MARKET",
                        scan.stop(), null, tpLevel, false);
            } else {
                // runner: stop only, no profit level.
                request = new OrderRequest(scan.epic(), scan.direction(), s, null, "MARKET",
                        scan.stop(), null, null, false);
            }
            OrderAck ack;
            try {
                ack = client.placeMarketOrder(request);
            } catch (Exception e) {
                log.warn("Place failed {} {} size {}: {}", book, scan.symbol(), s, e.getClass().getSimpleName());
                continue;
            }
            if (ack != null && ack.dealReference() != null) {
                if (placed == 0) {
                    refA = ack.dealReference();
                } else {
                    refB = ack.dealReference();
                }
                placed++;
            }
        }
        if (placed == 0) {
            return "entry failed (no deal reference)";
        }
        String[] dealIds = resolveFreshDealIds(client, scan, placed, refA, refB);
        String idA = dealIds[0] != null ? dealIds[0] : refA;
        String idB = placed > 1 ? (dealIds[1] != null ? dealIds[1] : refB) : null;
        SddExecutionState.Entry recorded = new SddExecutionState.Entry(
                book, scan.symbol(), scan.epic(), scan.direction(), scan.timestamp(),
                scan.entry(), scan.atrH1(), scan.stop(), idA, idB, placed == 2);
        persistWithRetry(book, scan, recorded);
        return null;
    }

    /** Backoff in ms between {@code state.put} attempts; index 0 is the first try (no wait). */
    private static final long[] PERSIST_BACKOFF_MS = {0L, 250L, 750L};

    /**
     * Persist the fresh entry through to Postgres, retrying a transient DB failure
     * a couple of times before falling back to a RAM-only record. Capital already
     * accepted at least one ticket by the time we get here, so a persist failure
     * must never surface as a skip: the name stays tracked in this process (no
     * pyramid), the scan continues, and — because a dyno restart before a later
     * successful write would orphan the position — the last-resort path alerts on
     * Telegram and the execution webhook instead of only logging.
     */
    private void persistWithRetry(String book, SddScan scan, SddExecutionState.Entry recorded) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < PERSIST_BACKOFF_MS.length; attempt++) {
            if (PERSIST_BACKOFF_MS[attempt] > 0L) {
                try {
                    Thread.sleep(PERSIST_BACKOFF_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                state.put(recorded);
                if (attempt > 0) {
                    log.warn("SDD persist for {} {} recovered on attempt {}", book, scan.symbol(), attempt + 1);
                }
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("SDD persist attempt {}/{} failed for {} {}: {}",
                        attempt + 1, PERSIST_BACKOFF_MS.length, book, scan.symbol(), e.toString());
            }
        }
        state.remember(recorded);
        String dir = scan.direction() == null ? null : scan.direction().name();
        log.error("SDD persist FAILED after Capital fill {} {} — RAM-only; a restart before the next "
                + "write orphans this position: {}", book, scan.symbol(), last == null ? "?" : last.toString());
        try {
            webhooks.publishExecution(book, scan.symbol(), dir, "persist_failed",
                    "Capital fill not saved to DB after retries — manual check needed");
            telegram.onExecutionPersistFailure(book, scan.symbol(), dir);
        } catch (Exception alertEx) {
            log.warn("SDD persist-failure alert failed for {} {}: {}",
                    book, scan.symbol(), alertEx.getClass().getSimpleName());
        }
    }

    private String[] resolveFreshDealIds(BrokerClient client, SddScan scan, int placed,
                                         String refA, String refB) {
        String[] out = new String[2];
        if (refA != null) {
            out[0] = confirmDealId(client, refA);
        }
        if (placed > 1 && refB != null) {
            out[1] = confirmDealId(client, refB);
        }
        if ((out[0] != null || refA == null) && (placed == 1 || out[1] != null || refB == null)) {
            return out;
        }
        // Fallback: match fresh positions by epic + direction.
        try {
            List<Position> fresh = new ArrayList<>();
            for (Position p : client.openPositions()) {
                if (p.direction() == scan.direction() && epicMatches(p.epic(), scan.epic())) {
                    fresh.add(p);
                }
            }
            if (fresh.size() >= 1 && out[0] == null) {
                out[0] = fresh.get(0).dealId();
            }
            if (fresh.size() >= 2 && out[1] == null) {
                out[1] = fresh.get(1).dealId();
            }
        } catch (Exception ignored) {
            // keep refs
        }
        return out;
    }

    private static String confirmDealId(BrokerClient client, String reference) {
        if (reference == null) {
            return null;
        }
        try {
            var c = client.confirm(reference);
            if (c != null && c.dealId() != null) {
                return c.dealId();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Position management: TP detection + runner H1-trail (no 2R, no BE)
    // ------------------------------------------------------------------

    /**
     * For each tracked entry: when the TP ticket (ticketA) is gone on the broker it took
     * its 1R — the runner (ticketB) keeps the original 2.5× stop and starts H1-trailing.
     * The runner's stop is NEVER moved to break-even or amended to entry. When the broker
     * no longer has any of this entry's tickets (and no stray epic+direction position
     * remains), the row is removed even if {@code tpFilled} is false — so a later M15
     * fullStack can re-enter after a manual close or SL. Positions that are not tracked
     * SDD names (TQQQ/CRCL/SPOT/SHOP etc.) are never touched.
     */
    public void manageOpen(BrokerClient client, List<Position> open) {
        applyBrokerTruth(client, open, true);
    }

    /**
     * Broker-truth reconcile: drop a row when its tracked dealIds are gone on two
     * consecutive position reads and no leftover epic+direction ticket remains.
     * Does not flatten, place, or touch neverFlatten / Glowne. {@code trailRunners}
     * is true on the scan path (1R webhook + H1 trail) and false on hydrate.
     */
    private void applyBrokerTruth(BrokerClient client, List<Position> open, boolean trailRunners) {
        if (open == null) {
            return;
        }
        List<Position> confirm = null;
        boolean confirmFailed = false;
        for (SddExecutionState.Entry entry : new ArrayList<>(state.entriesFor(client.book()))) {
            if (risk.neverFlatten(entry.epic)) {
                continue;
            }
            try {
                if (trackedTicketsGone(open, entry) && !hasOpenOnEpicAndDirection(open, entry)) {
                    if (!confirmFailed && confirm == null) {
                        List<Position> second = fetchOpenPositionsOrNull(client);
                        if (second == null) {
                            confirmFailed = true;
                        } else {
                            confirm = second;
                        }
                    }
                    if (confirmFailed) {
                        continue;
                    }
                    if (trackedTicketsGone(confirm, entry) && !hasOpenOnEpicAndDirection(confirm, entry)) {
                        dropClosedEntry(entry);
                        continue;
                    }
                }
                if (!entry.twoTickets) {
                    continue;
                }
                Position posA = findOpen(open, entry.epic, entry.ticketA);
                Position posB = findOpen(open, entry.epic, entry.ticketB);
                if (!entry.tpFilled && posA == null && posB != null) {
                    // TP ticket took its 1R — start trailing the runner, keep its stop.
                    entry.tpFilled = true;
                    state.update(entry);
                    if (trailRunners) {
                        webhooks.publishExecution(client.book(), entry.symbol,
                                entry.direction == null ? null : entry.direction.name(), "tp_fill", "");
                        monitor.record(client.book(), entry.symbol, "tp_closed",
                                "TP ticket took 1R; runner trailing from stop " + entry.stop);
                    }
                    log.info("SDD manage {} {}: TP ticket gone, runner trails", client.book(), entry.symbol);
                }
                if (posB == null) {
                    // runner gone, TP still open — keep the row (do not re-enter).
                    continue;
                }
                if (trailRunners && entry.tpFilled && entry.ticketB != null) {
                    trailRunner(client, entry, posB);
                    if (!entry.trailing) {
                        entry.trailing = true;
                        state.update(entry);
                        webhooks.publishExecution(client.book(), entry.symbol,
                                entry.direction == null ? null : entry.direction.name(), "trail", "");
                        monitor.record(client.book(), entry.symbol, "trail", "H1-trailing started");
                    }
                }
            } catch (Exception e) {
                log.warn("Manage failed for {} {}: {}", client.book(), entry.symbol, e.getClass().getSimpleName());
            }
        }
    }

    private void dropClosedEntry(SddExecutionState.Entry entry) {
        boolean vanishedWithoutTp = !entry.tpFilled;
        String book = entry.book;
        String symbol = entry.symbol;
        String dir = entry.direction == null ? null : entry.direction.name();
        state.remove(book, symbol);
        if (vanishedWithoutTp) {
            webhooks.publishExecution(book, symbol, dir, "closed", "tickets gone (manual or SL)");
            monitor.record(book, symbol, "closed", "tickets gone (manual or SL)");
            log.info("SDD {} {}: tickets gone (manual or SL), row removed", book, symbol);
        } else {
            log.info("SDD {} {}: both tickets gone, row removed", book, symbol);
        }
    }

    private static boolean trackedTicketsGone(List<Position> open, SddExecutionState.Entry entry) {
        if (!entry.twoTickets) {
            return findOpen(open, entry.epic, entry.ticketA) == null;
        }
        return findOpen(open, entry.epic, entry.ticketA) == null
                && findOpen(open, entry.epic, entry.ticketB) == null;
    }

    private static boolean hasOpenOnEpicAndDirection(List<Position> open, SddExecutionState.Entry entry) {
        if (open == null || entry.epic == null) {
            return false;
        }
        for (Position p : open) {
            if (epicMatches(p.epic(), entry.epic)
                    && (entry.direction == null || p.direction() == entry.direction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * H1-trail the runner only: the stop ratchets in the trade's favour, never worse than
     * the original 2.5× ATR stop. Implemented with PUT stopLevel (not trailingStop API).
     */
    private void trailRunner(BrokerClient client, SddExecutionState.Entry entry, Position pos) {
        if (entry.ticketB == null) {
            return;
        }
        double mid = currentMid(client, entry.epic);
        double trail = entry.direction == Direction.BUY ? mid - SddEngine.STOP_ATR_MULT * entry.atr
                : mid + SddEngine.STOP_ATR_MULT * entry.atr;
        double currentStop = pos.stopLevel() == null ? entry.stop : pos.stopLevel();
        // Floor = original 2.5× stop. Never worse than that; only ratchet in favour.
        double newStop = entry.direction == Direction.BUY
                ? Math.max(entry.stop, Math.max(currentStop, trail))
                : Math.min(entry.stop, Math.min(currentStop, trail));
        if (Math.abs(newStop - currentStop) > 1e-9) {
            client.amendPosition(entry.ticketB, newStop, false);
            log.info("SDD trail {} {} {} → {}", client.book(), entry.symbol, currentStop, newStop);
            monitor.record(client.book(), entry.symbol, "trail",
                    "stop " + currentStop + " -> " + newStop);
        }
    }

    private static double currentMid(BrokerClient client, String epic) {
        return client.marketPrice(epic).mid();
    }

    private static Position findOpen(List<Position> open, String epic, String dealId) {
        if (open == null) {
            return null;
        }
        if (dealId != null && !dealId.isBlank()) {
            for (Position p : open) {
                if (dealId.equals(p.dealId())) {
                    return p;
                }
            }
            // A ticket we track by deal id is not among the open positions.
            return null;
        }
        // dealId unknown (e.g. after a restart when only epic is known): match by epic.
        for (Position p : open) {
            if (epicMatches(p.epic(), epic)) {
                return p;
            }
        }
        return null;
    }

    private static boolean epicMatches(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private Set<String> openSddNames(List<Position> open) {
        Set<String> names = new HashSet<>();
        if (open == null) {
            return names;
        }
        for (Position p : open) {
            if (risk.neverFlatten(p.epic())) {
                continue;
            }
            String name = epicToSddName.get(p.epic().toUpperCase());
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private Account resolveAccount(BrokerClient client, boolean live) {
        try {
            if (!client.isSessionOpen()) {
                client.login();
            }
            List<Account> accounts = client.accounts();
            Account chosen = live ? risk.pickLiveAccount(accounts).account()
                    : risk.pickDemoAccount(accounts);
            if (chosen != null) {
                try {
                    client.selectAccount(chosen.id());
                } catch (Exception ignored) {
                    // selecting the already-selected account is a no-op
                }
            }
            return chosen;
        } catch (Exception e) {
            log.warn("Execution account resolve failed for {}: {}", client.book(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * {@code null} means the broker call failed — callers must not treat that as
     * "no positions" and drop rows. An empty list is a successful empty book.
     */
    private List<Position> fetchOpenPositionsOrNull(BrokerClient client) {
        try {
            List<Position> open = client.openPositions();
            return open == null ? List.of() : open;
        } catch (Exception e) {
            log.warn("Open positions failed for {}: {}", client.book(), e.getClass().getSimpleName());
            return null;
        }
    }
}
