package com.adam.server.swing;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.sdd.RiskPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places SDD-SWING (H1) entries on the {@code swing} book — a dedicated
 * Capital.com <b>DEMO</b> account, so this runs in parallel with SDD-M15 on
 * demo/live for comparison and never touches real money.
 *
 * <p>One market ticket per signal: stop = 2.5× H4 ATR, hard 1R take-profit
 * PUT together with the stop (Capital quirk: a profit level set alone wipes the
 * stop). Off by default — set {@code SWING_EXECUTION_ENABLED=true}.
 *
 * <p>v1 idempotency is in memory (keyed {@code symbol|direction|barTime}); a
 * restart inside the same H1 bar could re-enter. Persisting it is a follow-up.
 */
@Component
public class SwingExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(SwingExecutionGate.class);

    private final BrokerBooks books;
    private final RiskPolicy risk;
    private final boolean enabled;
    private final Set<String> placed = ConcurrentHashMap.newKeySet();

    public SwingExecutionGate(
            BrokerBooks books,
            RiskPolicy risk,
            @Value("${app.swing.execution-enabled:false}") boolean enabled
    ) {
        this.books = books;
        this.risk = risk;
        this.enabled = enabled;
    }

    /** Best-effort: never throws to the scan. */
    public void executeSignal(SwingScan s) {
        if (!enabled || s == null || s.direction() == null) {
            return;
        }
        String key = s.symbol() + "|" + s.direction().name() + "|"
                + (s.timestamp() == null ? 0 : s.timestamp().toEpochMilli());
        if (!placed.add(key)) {
            return; // this H1 bar already placed
        }
        try {
            BrokerClient swing = books.swing();
            if (swing == null || !swing.configured()) {
                log.info("SWING execution skipped {} — swing broker not configured", s.symbol());
                placed.remove(key);
                return;
            }
            if (!swing.isSessionOpen()) {
                swing.login();
            }
            Account account = risk.pickDemoAccount(swing.accounts());
            if (account == null) {
                log.warn("SWING execution {}: no swing demo account", s.symbol());
                placed.remove(key);
                return;
            }
            try {
                swing.selectAccount(account.id());
            } catch (Exception ignored) {
                // already selected
            }

            double oneR = Math.abs(s.targetLevel() - s.entry());
            double cash = risk.riskAmount(account, false);
            double size = risk.sizeFor(cash, oneR, SddSwingEngine.STOP_ATR_MULT);
            if (size <= 0 || oneR <= 0) {
                log.warn("SWING execution {}: size/1R is zero (cash {}, 1R {})", s.symbol(), cash, oneR);
                placed.remove(key);
                return;
            }

            OrderRequest req = new OrderRequest(
                    s.epic(), s.direction(), size, null, "MARKET",
                    s.stopLevel(), null, s.targetLevel(), false);
            OrderAck ack = swing.placeMarketOrder(req);
            if (ack != null && (ack.dealReference() != null || ack.dealId() != null)) {
                log.info("SWING entry placed swing {} {} size {} @ {} stop {} target {}",
                        s.symbol(), s.direction(), size, s.entry(), s.stopLevel(), s.targetLevel());
            } else {
                log.warn("SWING entry {} returned no deal reference", s.symbol());
                placed.remove(key);
            }
        } catch (Exception e) {
            log.warn("SWING execution failed for {}: {}", s.symbol(), e.getClass().getSimpleName());
            placed.remove(key);
        }
    }
}
