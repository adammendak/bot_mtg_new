package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
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
 * Places HTS ("wstęgi") entries on the {@code hts} book — a dedicated
 * Capital.com <b>DEMO</b> account ("Account m5"), so this runs in parallel with
 * SDD-M15 and SDD-SWING for comparison and never touches real money.
 *
 * <p>One market ticket per signal: structural stop + fixed R:R take-profit PUT
 * together (Capital quirk: a profit level set alone wipes the stop). Off by
 * default — set {@code HTS_EXECUTION_ENABLED=true}.
 *
 * <p>v1 idempotency is in memory (keyed {@code symbol|direction|barTime}); a
 * restart inside the same bar could re-enter. Persisting it is a follow-up,
 * exactly as for {@link com.adam.server.swing.SwingExecutionGate}.
 */
@Component
public class HtsExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(HtsExecutionGate.class);

    private final BrokerBooks books;
    private final RiskPolicy risk;
    private final boolean enabled;
    private final com.adam.server.scan.Mailer mailer;
    private final Set<String> placed = ConcurrentHashMap.newKeySet();

    public HtsExecutionGate(
            BrokerBooks books,
            RiskPolicy risk,
            com.adam.server.scan.Mailer mailer,
            @Value("${app.hts.execution-enabled:false}") boolean enabled
    ) {
        this.books = books;
        this.risk = risk;
        this.mailer = mailer;
        this.enabled = enabled;
    }

    /** Best-effort: never throws to the scan. */
    public void executeSignal(HtsScan s) {
        if (!enabled || s == null || s.direction() == null) {
            return;
        }
        String key = s.symbol() + "|" + s.direction().name() + "|"
                + (s.timestamp() == null ? 0 : s.timestamp().toEpochMilli());
        if (!placed.add(key)) {
            return; // this bar already placed
        }
        try {
            BrokerClient hts = books.hts();
            if (hts == null || !hts.configured()) {
                log.info("HTS execution skipped {} — hts broker not configured", s.symbol());
                placed.remove(key);
                return;
            }
            if (!hts.isSessionOpen()) {
                hts.login();
            }
            List<Account> accounts = hts.accounts();
            Account account = risk.pickHtsAccount(accounts);
            if (account == null) {
                log.warn("HTS execution {}: no hts demo account", s.symbol());
                placed.remove(key);
                return;
            }
            try {
                hts.selectAccount(account.id());
            } catch (Exception ignored) {
                // already selected
            }

            double stopDist = Math.abs(s.entry() - s.stopLevel());
            double cash = risk.riskAmount(account, false);
            double size = risk.sizeFor(cash, stopDist, 1.0);
            if (size <= 0 || stopDist <= 0) {
                log.warn("HTS execution {}: size/stop is zero (cash {}, stopDist {})", s.symbol(), cash, stopDist);
                placed.remove(key);
                return;
            }

            OrderRequest req = new OrderRequest(
                    s.epic(), s.direction(), size, null, "MARKET",
                    s.stopLevel(), null, s.targetLevel(), false);
            OrderAck ack = hts.placeMarketOrder(req);
            if (ack != null && (ack.dealReference() != null || ack.dealId() != null)) {
                log.info("HTS entry placed hts {} {} size {} @ {} stop {} target {}",
                        s.symbol(), s.direction(), size, s.entry(), s.stopLevel(), s.targetLevel());
            } else {
                log.warn("HTS entry {} returned no deal reference", s.symbol());
                placed.remove(key);
            }
        } catch (Exception e) {
            log.warn("HTS execution failed for {}: {}", s.symbol(), e.getClass().getSimpleName());
            placed.remove(key);
            mailer.sendThrottled("exec-hts", "HTS execution failed",
                    "Placing an HTS entry failed for " + s.symbol() + " " + s.direction()
                            + ":\n\n" + e.getClass().getSimpleName()
                            + "\n\n(further failures within 30 min are suppressed)");
        }
    }
}
