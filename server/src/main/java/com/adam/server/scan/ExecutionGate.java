package com.adam.server.scan;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.sdd.SddEngine;
import com.adam.server.sdd.SddScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places orders only when {@code EXECUTION_ENABLED=true}. Default is scan + dashboard + webhooks.
 * Scale 50% at 2R, then BE + H1 trail. No auto-BE before that. No pyramid while a name is open.
 */
@Component
public class ExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(ExecutionGate.class);

    private final AppProperties properties;
    private final BrokerClient broker;
    private final RiskPolicy risk;
    private final Set<String> scaled = ConcurrentHashMap.newKeySet();

    public ExecutionGate(AppProperties properties, BrokerClient broker, RiskPolicy risk) {
        this.properties = properties;
        this.broker = broker;
        this.risk = risk;
    }

    public String maybeEnter(SddScan scan, List<Position> open, Account account, boolean newsBlackout, String halt) {
        if (!properties.isExecutionEnabled()) {
            return "execution disabled";
        }
        if (!scan.actionable() || !scan.fullStack()) {
            return "not full stack";
        }
        if (newsBlackout) {
            return "news blackout";
        }
        if (halt != null) {
            return halt;
        }
        if (risk.pyramidBlocked(scan.epic(), open)) {
            return "no pyramid while name is open";
        }
        String liveGate = risk.liveGate(account, properties.getCapital().isLive());
        if (liveGate != null) {
            return liveGate;
        }
        double cash = risk.riskAmount(account, properties.getCapital().isLive());
        double size = risk.sizeFor(cash, scan.oneR(), SddEngine.STOP_ATR_MULT);
        if (size <= 0) {
            return "size is zero";
        }
        try {
            broker.placeMarketOrder(OrderRequest.market(scan.epic(), scan.direction(), size, scan.stop()));
            log.info("Entry submitted {} {} size {}", scan.symbol(), scan.direction(), size);
            return "submitted";
        } catch (Exception e) {
            log.warn("Entry failed for {}", scan.symbol());
            return "entry failed";
        }
    }

    public void manageOpen(List<Position> open) {
        if (!properties.isExecutionEnabled()) {
            return;
        }
        for (Position p : open) {
            if (risk.neverFlatten(p.epic())) {
                continue;
            }
            if (p.stopLevel() == null) {
                continue;
            }
            double stopDistance = Math.abs(p.level() - p.stopLevel());
            if (stopDistance <= 0) {
                continue;
            }
            double atr = stopDistance / SddEngine.STOP_ATR_MULT;
            double twoR = 2.0 * atr;
            MarketPrice px;
            try {
                px = broker.marketPrice(p.epic());
            } catch (Exception e) {
                continue;
            }
            double mid = px.mid();
            boolean hit2R = p.direction() == Direction.BUY
                    ? mid >= p.level() + twoR
                    : mid <= p.level() - twoR;
            if (hit2R && scaled.add(p.dealId())) {
                double half = p.size() / 2.0;
                try {
                    broker.closePosition(p.dealId(), half);
                    broker.amendPosition(p.dealId(), p.level(), true);
                    log.info("Scaled 50% at 2R then BE+H1 trail {}", p.epic());
                } catch (Exception e) {
                    scaled.remove(p.dealId());
                    log.warn("Scale-out failed for {}", p.epic());
                }
            }
        }
    }
}
