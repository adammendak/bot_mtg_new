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
import org.springframework.beans.factory.annotation.Value;
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
 * <p>One market ticket per signal: structural stop + fixed R:R take-profit PUT
 * together (Capital quirk: a profit level set alone wipes the stop).
 *
 * <p>v1 idempotency is in memory (keyed {@code variant|symbol|direction|barTime});
 * a restart inside the same bar could re-enter. Persisting it is a follow-up.
 */
@Component
public class HtsExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(HtsExecutionGate.class);

    private final BrokerBooks books;
    private final RiskPolicy risk;
    private final AppProperties properties;
    private final boolean enabled;
    private final boolean liveEnabled;
    private final com.adam.server.scan.Mailer mailer;
    private final Set<String> placed = ConcurrentHashMap.newKeySet();

    public HtsExecutionGate(
            BrokerBooks books,
            RiskPolicy risk,
            AppProperties properties,
            com.adam.server.scan.Mailer mailer,
            @Value("${app.hts.execution-enabled:false}") boolean enabled,
            @Value("${app.hts.live-execution-enabled:false}") boolean liveEnabled
    ) {
        this.books = books;
        this.risk = risk;
        this.properties = properties;
        this.mailer = mailer;
        this.enabled = enabled;
        this.liveEnabled = liveEnabled;
    }

    /** Best-effort: never throws to the scan. */
    public void executeSignal(HtsScan s) {
        if (s == null || s.direction() == null || s.variant() == null) {
            return;
        }
        boolean live = s.variant().live();
        if (live ? !liveEnabled : !enabled) {
            return;
        }
        String book = s.variant().book();
        String key = s.variant().name() + "|" + s.symbol() + "|" + s.direction().name() + "|"
                + (s.timestamp() == null ? 0 : s.timestamp().toEpochMilli());
        if (!placed.add(key)) {
            return; // this bar already placed
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
                if (account != null && account.profitLoss() <= properties.getLiveHaltPln()) {
                    log.warn("HTS [{}] LIVE execution skipped {} — open P/L {} past halt {}",
                            s.variant().name(), s.symbol(), account.profitLoss(), properties.getLiveHaltPln());
                    placed.remove(key);
                    return;
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

            OrderRequest req = new OrderRequest(
                    s.epic(), s.direction(), size, null, "MARKET",
                    s.stopLevel(), null, s.targetLevel(), false);
            OrderAck ack = broker.placeMarketOrder(req);
            if (ack != null && (ack.dealReference() != null || ack.dealId() != null)) {
                log.info("HTS [{}] entry placed on {} {} {} size {} @ {} stop {} target {}",
                        s.variant().name(), book, s.symbol(), s.direction(), size,
                        s.entry(), s.stopLevel(), s.targetLevel());
            } else {
                log.warn("HTS [{}] entry {} returned no deal reference", s.variant().name(), s.symbol());
                placed.remove(key);
            }
        } catch (Exception e) {
            log.warn("HTS [{}] execution failed for {}: {}", s.variant().name(), s.symbol(),
                    e.getClass().getSimpleName());
            placed.remove(key);
            mailer.sendThrottled("exec-hts", "HTS execution failed",
                    "Placing an HTS entry failed for " + s.variant().name() + " " + s.symbol()
                            + " " + s.direction() + ":\n\n" + e.getClass().getSimpleName()
                            + "\n\n(further failures within 30 min are suppressed)");
        }
    }
}
