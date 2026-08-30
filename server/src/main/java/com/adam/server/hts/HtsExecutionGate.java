package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.config.AppProperties;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
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
        if (live ? !flags.enabled("hts.live-execution") : !flags.enabled("hts.execution")) {
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

            // Stop only — the position monitor runs the TP1 partial + trailing runner.
            OrderRequest req = new OrderRequest(
                    s.epic(), s.direction(), size, null, "MARKET",
                    s.stopLevel(), null, null, false);
            OrderAck ack = broker.placeMarketOrder(req);
            if (ack != null && (ack.dealReference() != null || ack.dealId() != null)) {
                log.info("HTS [{}] entry placed on {} {} {} size {} @ {} stop {} (TP1 target {} managed)",
                        s.variant().name(), book, s.symbol(), s.direction(), size,
                        s.entry(), s.stopLevel(), s.targetLevel());
                try {
                    trades.recordOpen(s, s.variant(), book, account.name(), size, ack);
                } catch (Exception e) {
                    log.warn("HTS [{}] entry {} placed but not recorded: {}",
                            s.variant().name(), s.symbol(), e.getClass().getSimpleName());
                }
            } else {
                log.warn("HTS [{}] entry {} returned no deal reference", s.variant().name(), s.symbol());
                placed.remove(key);
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
}
