package com.adam.server.sdd;

import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DEMO ~1% / 10 PLN. Halt −30 / hard −50 vs Warsaw day-open P/L.
 * LIVE only account {@code bot trading konto} at 1%; refuse equity ≥ 5000 (~10k preferred later).
 * Never flatten TQQQ / CRCL / SPOT / SHOP. No Fintokei. No QQQ restore.
 */
@Component
public class RiskPolicy {

    public static final Set<String> NEVER_FLATTEN = Set.of("TQQQ", "CRCL", "SPOT", "SHOP");

    private final AppProperties properties;

    public RiskPolicy(AppProperties properties) {
        this.properties = properties;
    }

    public boolean neverFlatten(String epicOrSymbol) {
        if (epicOrSymbol == null) {
            return false;
        }
        return NEVER_FLATTEN.contains(epicOrSymbol.toUpperCase(Locale.ROOT));
    }

    public String liveGate(Account account, boolean live) {
        if (!live) {
            return null;
        }
        if (account == null) {
            return "LIVE requires account '" + properties.getLiveAccountName() + "'";
        }
        if (!properties.getLiveAccountName().equals(account.name())) {
            return "LIVE refuses account '" + account.name() + "'; only '" + properties.getLiveAccountName() + "'";
        }
        if (account.balance() >= properties.getLiveEquityRefuse()) {
            return "LIVE refuses equity >= " + properties.getLiveEquityRefuse() + " (preferred later ~10k)";
        }
        return null;
    }

    public String dayHalt(double dayPnl) {
        if (dayPnl <= properties.getHardHaltPln()) {
            return "hard halt day P/L " + dayPnl;
        }
        if (dayPnl <= properties.getHaltPln()) {
            return "halt day P/L " + dayPnl;
        }
        return null;
    }

    public boolean pyramidBlocked(String epic, List<Position> open) {
        for (Position p : open) {
            if (epic.equalsIgnoreCase(p.epic())) {
                return true;
            }
        }
        return false;
    }

    public double riskAmount(Account account, boolean live) {
        double equity = account == null ? 0 : account.balance();
        double onePct = equity * 0.01;
        if (live) {
            return onePct;
        }
        if (onePct <= 0) {
            return properties.getDemoRiskPln();
        }
        return onePct;
    }

    /**
     * Size so that 2.5 ATR stop risks {@link #riskAmount}. If ATR is the 1R unit,
     * cash risk for the 2.5 ATR stop is 2.5 * oneR * size. We size from 1R = 1 ATR
     * and accept the stop sitting 2.5R away.
     */
    public double sizeFor(double riskCash, double atr, double stopAtrMult) {
        if (atr <= 0 || riskCash <= 0) {
            return 0;
        }
        double stopDistance = stopAtrMult * atr;
        return riskCash / stopDistance;
    }
}
