package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.config.AppProperties;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.sdd.RiskPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places HTS ("wstęgi") entries on the book that belongs to the signal's
 * {@link HtsVariant}:
 * <ul>
 *   <li>demo variants — CORE → {@code demo} ("Account m15"), SWING → {@code swing}
 *       ("Account H1"), FAST → {@code hts} ("Account m5"); gated by
 *       {@code HTS_EXECUTION_ENABLED}.</li>
 *   <li>{@code CORE_LIVE} — {@code live} book ("bot trading konto"), <b>real
 *       money</b>: 1 % of account risk, honours {@link RiskPolicy#pickLiveAccount}
 *       (equity-refuse / Fintokei), skips when open P/L is past
 *       {@code LIVE_HALT_PLN} or the size is below {@code MIN_DEAL_SIZE};
 *       gated by its own {@code HTS_LIVE_EXECUTION_ENABLED}.</li>
 * </ul>
 *
 * <p><b>One position per signal, stop only</b> — no take-profit is sent with the
 * entry. {@link HtsPositionMonitor} owns the exit: it closes half at TP1 (1:2
 * R:R), locks the runner's stop at break-even + 1R, then trails the fast band
 * and flattens the runner on a body close beyond the slow band. SDD opened two
 * tickets; HTS opens one and manages it.
 *
 * <p>Idempotency is both in memory (fast path) and in {@code hts_trades} via
 * {@link HtsTradeService#alreadyExecuted}, so a restart inside the same signal
 * bar cannot re-enter. Every fill is recorded to {@code hts_trades} and the
 * position monitor fills the outcome on close.
 */
@Component
public class HtsExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(HtsExecutionGate.class);

    private final BrokerBooks books;
    private final RiskPolicy risk;
    private final AppProperties properties;
    private final HtsTradeService trades;
    private final com.adam.server.ops.FeatureFlags flags;
    private final com.adam.server.scan.Mailer mailer;
    private final com.adam.server.ops.ErrorLog errorLog;
    private final Set<String> placed = ConcurrentHashMap.newKeySet();

    public HtsExecutionGate(
            BrokerBooks books,
            RiskPolicy risk,
            AppProperties properties,
            HtsTradeService trades,
            com.adam.server.ops.FeatureFlags flags,
            com.adam.server.scan.Mailer mailer,
            com.adam.server.ops.ErrorLog errorLog
    ) {
        this.books = books;
        this.risk = risk;
        this.properties = properties;
        this.trades = trades;
        this.flags = flags;
        this.mailer = mailer;
        this.errorLog = errorLog;
    }

    /** Best-effort: never throws to the scan. */
    public void executeSignal(HtsScan s) {
        if (s == null || s.direction() == null || s.variant() == null) {
            return;
        }
        boolean live = s.variant().live();
        String flag = live ? "hts.live-execution" : "hts.execution";
        if (!flags.enabled(flag)) {
            // A signal that is NOT executed must be loud — this is how one slips by.
            log.warn("HTS [{}] execution SKIPPED for {} {} — feature flag {} is OFF",
                    s.variant().name(), s.symbol(), s.direction(), flag);
            return;
        }
        String book = s.variant().book();
        String key = s.variant().name() + "|" + s.symbol() + "|" + s.direction().name() + "|"
                + (s.timestamp() == null ? 0 : s.timestamp().toEpochMilli());
        if (!placed.add(key)) {
            return; // this bar already placed
        }
        if (trades.alreadyExecuted(s)) {
            return; // persisted across a restart within the same signal bar
        }
        if (trades.hasOpenPosition(s.variant(), s.symbol())) {
            // One position per signal, actively managed — never stack a new entry
            // every bar while the previous one is still open.
            log.info("HTS [{}] execution skipped {} {} — a position for this model/symbol is already open",
                    s.variant().name(), s.symbol(), s.direction());
            placed.remove(key);
            return;
        }
        try {
            BrokerClient broker = books.forBook(book);
            if (broker == null || !broker.configured()) {
                log.info("HTS [{}] execution skipped {} — {} broker not configured",
                        s.variant().name(), s.symbol(), book);
                placed.remove(key);
                return;
            }
            if (!broker.isSessionOpen()) {
                broker.login();
            }
            List<Account> accounts = broker.accounts();

            Account account;
            if (live) {
                RiskPolicy.LivePick pick = risk.pickLiveAccount(accounts);
                if (pick.hideReason() != null) {
                    log.warn("HTS [{}] LIVE execution skipped {} — {}",
                            s.variant().name(), s.symbol(), pick.hideReason());
                    placed.remove(key);
                    return;
                }
                account = pick.account();
                if (account != null) {
                    double dayPnl = trades.realisedPnlSince(book, trades.startOfToday()) + account.profitLoss();
                    if (dayPnl <= properties.getLiveHaltPln()) {
                        log.warn("HTS [{}] LIVE execution skipped {} — day P/L {} past halt {}",
                                s.variant().name(), s.symbol(), dayPnl, properties.getLiveHaltPln());
                        placed.remove(key);
                        return;
                    }
                }
            } else {
                account = risk.pickForBook(book, accounts);
            }
            if (account == null) {
                log.warn("HTS [{}] execution {}: no account on book {}", s.variant().name(), s.symbol(), book);
                placed.remove(key);
                return;
            }
            try {
                broker.selectAccount(account.id());
            } catch (Exception ignored) {
                // already selected
            }

            double stopDist = Math.abs(s.entry() - s.stopLevel());
            double cash = risk.riskAmount(account, live);
            double size = risk.sizeFor(cash, stopDist, 1.0);
            if (size <= 0 || stopDist <= 0) {
                log.warn("HTS [{}] execution {}: size/stop is zero (cash {}, stopDist {})",
                        s.variant().name(), s.symbol(), cash, stopDist);
                placed.remove(key);
                return;
            }
            if (live && size < properties.getMinDealSize()) {
                log.warn("HTS [{}] LIVE execution skipped {} — size {} below min deal {} (1R would risk too little)",
                        s.variant().name(), s.symbol(), size, properties.getMinDealSize());
                placed.remove(key);
                return;
            }

            // Shape the order so the broker actually accepts it: snap the size to
            // the instrument step, round the stop to instrument precision, and
            // widen a too-tight M5 band stop out to the broker minimum. Without
            // this Capital returns a dealReference and then REJECTS the deal on
            // confirm, so no position ever opens.
            MarketRules rules = broker.marketRules(s.epic());
            boolean buy = s.direction() == Direction.BUY;
            double adjEntry = rules.roundPrice(s.entry());
            double adjStop = rules.roundPrice(s.stopLevel());
            if (rules.minStopDistancePoints() > 0
                    && Math.abs(adjEntry - adjStop) < rules.minStopDistancePoints()) {
                adjStop = rules.roundPrice(buy
                        ? adjEntry - rules.minStopDistancePoints()
                        : adjEntry + rules.minStopDistancePoints());
                log.info("HTS [{}] {} stop widened to broker minimum {} pts ({} -> {})",
                        s.variant().name(), s.symbol(), rules.minStopDistancePoints(),
                        s.stopLevel(), adjStop);
            }
            if (Math.abs(adjEntry - adjStop) <= 0) {
                log.warn("HTS [{}] execution {}: stop equals entry after rounding ({})",
                        s.variant().name(), s.symbol(), adjEntry);
                placed.remove(key);
                return;
            }
            double adjDist = Math.abs(adjEntry - adjStop);
            // If the stop was widened to the broker minimum, re-size from the NEW
            // distance so risk stays ~1R — otherwise a 165pt band stop widened to
            // a 500pt broker minimum would risk ~3× the intended amount.
            if (adjDist > stopDist * 1.0001) {
                double resized = risk.sizeFor(cash, adjDist, 1.0);
                log.info("HTS [{}] {} re-sized after stop widen: {} -> {} (dist {} -> {})",
                        s.variant().name(), s.symbol(), size, resized, stopDist, adjDist);
                size = resized;
            }
            double adjSize = rules.roundSize(size);
            if (adjSize <= 0) {
                log.warn("HTS [{}] execution {}: size rounds to zero (raw {}, minDeal {})",
                        s.variant().name(), s.symbol(), size, rules.minDealSize());
                placed.remove(key);
                return;
            }
            if (live && adjSize < properties.getMinDealSize()) {
                log.warn("HTS [{}] LIVE execution skipped {} — size {} below min deal {} after shaping",
                        s.variant().name(), s.symbol(), adjSize, properties.getMinDealSize());
                placed.remove(key);
                return;
            }
            double adjTarget = buy ? adjEntry + HtsEngine.RR * adjDist : adjEntry - HtsEngine.RR * adjDist;
            if (adjEntry != s.entry() || adjStop != s.stopLevel() || adjSize != size) {
                log.info("HTS [{}] {} order shaped for broker: entry {}->{} stop {}->{} size {}->{}",
                        s.variant().name(), s.symbol(), s.entry(), adjEntry,
                        s.stopLevel(), adjStop, size, adjSize);
            }
            size = adjSize;
            s = new HtsScan(s.variant(), s.timestamp(), s.symbol(), s.epic(), s.direction(),
                    adjEntry, adjStop, adjTarget, s.htfUp());

            // Stop only — the position monitor runs the TP1 partial + trailing runner.
            OrderRequest req = new OrderRequest(
                    s.epic(), s.direction(), size, null, "MARKET",
                    s.stopLevel(), null, null, false);
            OrderAck ack = broker.placeMarketOrder(req);
            if (ack == null || ack.dealReference() == null) {
                log.warn("HTS [{}] entry {} returned no deal reference", s.variant().name(), s.symbol());
                placed.remove(key);
                return;
            }

            // Capital's POST /positions only hands back a dealReference; the real
            // dealId (and whether the deal was actually accepted) comes from the
            // confirm. Without this the position monitor can never reconcile the
            // trade — same pattern the SDD gate uses.
            String dealId = resolveDealId(broker, ack.dealReference(), s);
            if (dealId == null) {
                log.warn("HTS [{}] entry {} {} submitted (ref {}) but NOT confirmed open — not recording",
                        s.variant().name(), s.symbol(), s.direction(), ack.dealReference());
                placed.remove(key);
                mailer.sendThrottled("exec-hts-reject", "HTS entry not confirmed open",
                        "An HTS entry for " + s.variant().name() + " " + s.symbol() + " " + s.direction()
                                + " was submitted (ref " + ack.dealReference() + ") but the broker did not "
                                + "confirm an open position. See the confirm reason in the logs.\n\n"
                                + "(further failures within 30 min are suppressed)");
                return;
            }

            OrderAck confirmed = new OrderAck(ack.dealReference(), dealId, "ACCEPTED");
            log.info("HTS [{}] entry placed on {} {} {} size {} @ {} stop {} dealId {} (TP1 target {} managed)",
                    s.variant().name(), book, s.symbol(), s.direction(), size,
                    s.entry(), s.stopLevel(), dealId, s.targetLevel());
            try {
                trades.recordOpen(s, s.variant(), book, account.name(), size, confirmed);
            } catch (Exception e) {
                log.warn("HTS [{}] entry {} placed but not recorded: {}",
                        s.variant().name(), s.symbol(), e.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.warn("HTS [{}] execution failed for {}: {}", s.variant().name(), s.symbol(),
                    e.getClass().getSimpleName());
            placed.remove(key);
            errorLog.record("hts-exec", s.variant().name(), s.symbol(), e);
            mailer.sendThrottled("exec-hts", "HTS execution failed",
                    "Placing an HTS entry failed for " + s.variant().name() + " " + s.symbol()
                            + " " + s.direction() + ":\n\n" + e.getClass().getSimpleName()
                            + "\n\n(further failures within 30 min are suppressed)");
        }
    }

    /**
     * Resolve the broker dealId for a fresh entry from the deal confirmation, with
     * a fallback to matching the newest open position on the same epic + direction
     * (same approach as {@code ExecutionGate.resolveFreshDealIds}). Returns
     * {@code null} when the deal was rejected or cannot be confirmed as open — the
     * caller then does NOT record a phantom OPEN trade.
     */
    private String resolveDealId(BrokerClient broker, String dealReference, HtsScan s) {
        try {
            Confirmation c = broker.confirm(dealReference);
            if (c != null) {
                if (c.dealId() != null && c.accepted()) {
                    return c.dealId();
                }
                log.warn("HTS [{}] {} {} confirm: status={} dealStatus={} reason={}",
                        s.variant().name(), s.symbol(), s.direction(),
                        c.status(), c.dealStatus(), c.reason());
                if ("REJECTED".equalsIgnoreCase(c.dealStatus())) {
                    return null; // definitively rejected — no point matching positions
                }
            }
        } catch (Exception e) {
            log.warn("HTS [{}] {} confirm failed ({}) — trying position match",
                    s.variant().name(), s.symbol(), e.getClass().getSimpleName());
        }
        try {
            for (Position p : broker.openPositions()) {
                if (p.direction() == s.direction()
                        && p.epic() != null && p.epic().equalsIgnoreCase(s.epic())) {
                    return p.dealId();
                }
            }
        } catch (Exception ignored) {
            // no position match available
        }
        return null;
    }
}
