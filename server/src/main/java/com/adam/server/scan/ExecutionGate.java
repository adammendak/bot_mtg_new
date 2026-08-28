package com.adam.server.scan;

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
 * {@code bot trading konto}) behind {@code EXECUTION_ENABLED=true}.
 *
 * <p>Rules implemented here:
 * <ul>
 *   <li>Only a fullStack signal places; flip-but-not-fullStack never places, never flattens.</li>
 *   <li>Two separate deals (two tickets) when the per-ticket size clears
 *       {@code app.execution.min-deal-size}; a single ticket otherwise.</li>
 *   <li>Stop 2.5× H1 ATR at entry, no TP at entry. At 2R one whole ticket is closed
 *       (NEVER {@code DELETE + size=}, which flattens the whole ticket); the runner
 *       moves to break-even then H1-trails.</li>
 *   <li>Demo ~10 PLN (1% of demo); live 1% of the bot-konto equity. Day-P/L halt for
 *       new entries: demo −30, live −18 (per book).</li>
 *   <li>Skip if the SDD name is already open; max 4 unique SDD names per book;
 *       no pyramid while a name is open unless 2R is taken and the runner is at BE.</li>
 *   <li>Idempotent keyed on {@code book|symbol|direction|barTime} — a webhook retry or
 *       a re-scan of the same bar never opens a second entry.</li>
 *   <li>Fills and skips are POSTed to Computron (type=execution) so it audits instead
 *       of polling every 15 minutes.</li>
 *   <li>Never touches TQQQ / CRCL / SPOT / SHOP (stocks book, not SDD).</li>
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
    private final Map<String, String> epicToSddName;

    public ExecutionGate(
            AppProperties properties,
            BrokerBooks books,
            RiskPolicy risk,
            SddExecutionState state,
            SignalWebhookPublisher webhooks
    ) {
        this.properties = properties;
        this.books = books;
        this.risk = risk;
        this.state = state;
        this.webhooks = webhooks;
        this.epicToSddName = new LinkedHashMap<>();
        for (SddSymbol symbol : SddSymbol.universe()) {
            epicToSddName.put(symbol.epic(properties).toUpperCase(), symbol.code());
        }
    }

    /**
     * After a dyno restart, reload the persisted entries from Postgres and reconcile
     * them against the broker's open positions: a tracked ticket that no longer exists
     * on the broker is treated as closed (marked 2R-closed so it stops being managed;
     * a single-ticket entry is removed). Never touches the stocks book.
     * Called on ApplicationReady, before any scan runs.
     */
    public void reloadAndReconcile() {
        state.loadFromDb();
        for (String book : new String[]{"demo", "live"}) {
            BrokerClient client = books.forBook(book);
            if (client == null || !client.configured()) {
                continue;
            }
            List<Position> open = safeOpenPositions(client);
            List<String> openEpics = new ArrayList<>();
            for (Position p : open) {
                if (!risk.neverFlatten(p.epic())) {
                    openEpics.add(p.epic().toUpperCase());
                }
            }
            for (SddExecutionState.Entry entry : new ArrayList<>(state.entriesFor(book))) {
                if (risk.neverFlatten(entry.epic)) {
                    continue;
                }
                if (openEpics.contains(entry.epic.toUpperCase())) {
                    continue; // still open — keep as-is
                }
                // Ticket(s) gone on the broker -> entry effectively finished.
                if (!entry.twoTickets) {
                    state.remove(book, entry.symbol);
                } else if (!entry.closedAt2R) {
                    entry.closedAt2R = true;
                    entry.runnerAtBe = true; // nothing left to manage
                    state.update(entry);
                }
            }
        }
    }

    /**
     * Run execution for one book after a scan. Only fullStack signals place; flip-only
     * signals never place and never flatten. Existing entries are managed first (2R
     * close, BE, H1 trail), then new fullStack signals are considered.
     */
    public void executeBook(String book, List<SddScan> symbols, AccountView view, boolean newsBlackout) {
        if (!properties.isExecutionEnabled()) {
            return;
        }
        BrokerClient client = books.forBook(book);
        if (client == null || !client.configured()) {
            return;
        }
        boolean live = "live".equals(client.book());
        Account account = resolveAccount(client, live);
        List<Position> open = safeOpenPositions(client);

        manageOpen(client, open);

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
                log.info("SDD entry placed {} {} {} (tickets recorded)", book, scan.symbol(), scan.direction());
            } else {
                webhooks.publishExecution(book, scan.symbol(),
                        scan.direction() == null ? null : scan.direction().name(),
                        "skip", reason);
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
            if (!e.allowsPyramid()) {
                openNames.add(e.symbol);
            }
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
     * Places one or two market tickets (stop at entry). Two separate deals are used
     * whenever the per-ticket size clears {@code minDealSize} — this lets us close ONE
     * whole ticket at 2R without flattening the other. Returns null on success or a
     * reason on failure.
     */
    String placeTickets(BrokerClient client, String book, SddScan scan, double size) {
        double perTicket = size / 2.0;
        boolean twoTickets = perTicket >= properties.getMinDealSize();
        double[] sizes = twoTickets ? new double[]{perTicket, perTicket} : new double[]{size};

        String refA = null;
        String refB = null;
        int placed = 0;
        for (double s : sizes) {
            OrderAck ack;
            try {
                ack = client.placeMarketOrder(OrderRequest.market(scan.epic(), scan.direction(), s, scan.stop()));
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
        state.put(new SddExecutionState.Entry(
                book, scan.symbol(), scan.epic(), scan.direction(), scan.timestamp(),
                scan.entry(), scan.atrH1(), scan.stop(), idA, idB, placed == 2));
        return null;
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
    // Position management: 2R close-one, BE, H1 trail
    // ------------------------------------------------------------------

    /**
     * For each tracked entry: at 2R close ONE whole ticket (never DELETE+size) and move
     * the runner to break-even; afterwards H1-trail the runner. Positions that are not
     * tracked SDD names (TQQQ/CRCL/SPOT/SHOP etc.) are never touched.
     */
    public void manageOpen(BrokerClient client, List<Position> open) {
        for (SddExecutionState.Entry entry : state.entriesFor(client.book())) {
            if (risk.neverFlatten(entry.epic)) {
                continue;
            }
            Position pos = findOpen(open, entry);
            if (pos == null) {
                continue;
            }
            try {
                if (!entry.closedAt2R) {
                    maybeCloseAt2R(client, entry, pos);
                } else if (entry.runnerAtBe) {
                    trailRunner(client, entry, pos);
                }
            } catch (Exception e) {
                log.warn("Manage failed for {} {}: {}", client.book(), entry.symbol, e.getClass().getSimpleName());
            }
        }
    }

    private void maybeCloseAt2R(BrokerClient client, SddExecutionState.Entry entry, Position pos) {
        double twoR = 2.0 * entry.atr;
        boolean hit2R = entry.direction == Direction.BUY
                ? pos.level() + twoR <= currentMid(client, entry.epic)
                : pos.level() - twoR >= currentMid(client, entry.epic);
        if (!hit2R) {
            return;
        }
        // Close ONE whole deal (the 2R ticket) with NO size param — Capital flattens
        // the whole ticket when a size is passed. The runner stays open at its stop.
        if (entry.twoTickets && entry.ticketA != null) {
            client.closePosition(entry.ticketA, 0);
            entry.closedAt2R = true;
            // Move the runner to break-even (entry price) and enable H1 trailing.
            if (entry.ticketB != null) {
                client.amendPosition(entry.ticketB, entry.entry, false);
                entry.runnerAtBe = true;
            }
            state.update(entry); // write-through: 2R closed + runner at BE survive a restart
            log.info("SDD 2R: closed {} on {} {}, runner to BE", entry.ticketA, client.book(), entry.symbol);
        } else {
            // Single ticket: close it whole (no size) — the whole position takes profit.
            client.closePosition(pos.dealId(), 0);
            entry.closedAt2R = true;
            log.info("SDD 2R: closed single ticket {} on {} {}", pos.dealId(), client.book(), entry.symbol);
            state.remove(entry.book, entry.symbol);
        }
    }

    private void trailRunner(BrokerClient client, SddExecutionState.Entry entry, Position pos) {
        if (!entry.twoTickets || entry.ticketB == null) {
            return;
        }
        double mid = currentMid(client, entry.epic);
        double trail = entry.direction == Direction.BUY ? mid - SddEngine.STOP_ATR_MULT * entry.atr
                : mid + SddEngine.STOP_ATR_MULT * entry.atr;
        double currentStop = pos.stopLevel() == null ? entry.entry : pos.stopLevel();
        // Never worse than break-even, only ratchet in the favourable direction.
        double newStop = entry.direction == Direction.BUY
                ? Math.max(entry.entry, Math.max(currentStop, trail))
                : Math.min(entry.entry, Math.min(currentStop, trail));
        if (Math.abs(newStop - currentStop) > 1e-9) {
            client.amendPosition(entry.ticketB, newStop, false);
            log.info("SDD trail {} {} {} → {}", client.book(), entry.symbol, currentStop, newStop);
        }
    }

    private static double currentMid(BrokerClient client, String epic) {
        return client.marketPrice(epic).mid();
    }

    private static Position findOpen(List<Position> open, SddExecutionState.Entry entry) {
        if (open == null) {
            return null;
        }
        for (Position p : open) {
            if (epicMatches(p.epic(), entry.epic)) {
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

    private List<Position> safeOpenPositions(BrokerClient client) {
        try {
            return client.openPositions();
        } catch (Exception e) {
            log.warn("Open positions failed for {}: {}", client.book(), e.getClass().getSimpleName());
            return List.of();
        }
    }
}
