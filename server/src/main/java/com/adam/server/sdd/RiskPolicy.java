package com.adam.server.sdd;

import com.adam.server.broker.model.Account;
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

    public boolean isFintokei(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("fintokei");
    }

    /**
     * LIVE dashboard pick: only {@code bot trading konto}, hide equity ≥ 5000, never Fintokei.
     */
    public LivePick pickLiveAccount(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return LivePick.hidden("LIVE requires account '" + properties.getLiveAccountName() + "'");
        }
        Account named = null;
        for (Account a : accounts) {
            if (isFintokei(a.name())) {
                continue;
            }
            if (properties.getLiveAccountName().equals(a.name())) {
                named = a;
                break;
            }
        }
        if (named == null) {
            return LivePick.hidden("LIVE requires account '" + properties.getLiveAccountName() + "'");
        }
        if (named.balance() >= properties.getLiveEquityRefuse()) {
            return LivePick.hidden("LIVE account hidden (equity >= " + (int) properties.getLiveEquityRefuse() + ")");
        }
        return LivePick.visible(named);
    }

    public Account pickDemoAccount(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        Account preferred = null;
        for (Account a : accounts) {
            if (isFintokei(a.name())) {
                continue;
            }
            if (a.preferred()) {
                preferred = a;
                break;
            }
        }
        if (preferred != null) {
            return preferred;
        }
        for (Account a : accounts) {
            if (!isFintokei(a.name())) {
                return a;
            }
        }
        return null;
    }

    /**
     * "Główne" (main) book pick. Unlike {@link #pickDemoAccount}, this never
     * selects the live trading account (which lives on the same Capital.com
     * profile and is flagged preferred there), so the main dashboard cannot
     * show live data. When {@code GLOWNE_ACCOUNT_NAME} is set, only an account
     * with that exact name is accepted; otherwise the preferred / first
     * non-Fintokei account (excluding the live trading account) is used.
     */
    public Account pickGlowneAccount(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        String glowneName = properties.getGlowneAccountName();
        String liveName = properties.getLiveAccountName();
        Account preferred = null;
        Account first = null;
        for (Account a : accounts) {
            if (isFintokei(a.name())) {
                continue;
            }
            if (glowneName != null && !glowneName.isBlank() && glowneName.equals(a.name())) {
                return a;
            }
            if (liveName != null && liveName.equals(a.name())) {
                continue;
            }
            if (preferred == null && a.preferred()) {
                preferred = a;
            }
            if (first == null) {
                first = a;
            }
        }
        if (preferred != null) {
            return preferred;
        }
        return first;
    }

    /**
     * "Swing" book pick — a separate Capital.com DEMO sub-account for SDD-SWING.
     * Accepts only the account named {@code SWING_ACCOUNT_NAME}
     * ({@code app.swing-account-name}, default {@code "Account H1"}); falls back
     * to the preferred / first non-Fintokei demo account if that name is absent.
     */
    public Account pickSwingAccount(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        String name = properties.getSwingAccountName();
        if (name != null && !name.isBlank()) {
            for (Account a : accounts) {
                if (!isFintokei(a.name()) && name.equals(a.name())) {
                    return a;
                }
            }
        }
        return pickDemoAccount(accounts);
    }

    public record LivePick(Account account, String hideReason) {
        static LivePick visible(Account account) {
            return new LivePick(account, null);
        }

        static LivePick hidden(String reason) {
            return new LivePick(null, reason);
        }

        public boolean visible() {
            return account != null;
        }
    }

    public String dayHalt(double dayPnl) {
        return dayHalt(dayPnl, properties.getHaltPln(), properties.getHardHaltPln());
    }

    /**
     * Per-book day P/L halt gate: returns a non-null reason when new SDD entries
     * must be halted on that book. Demo uses {@code HALT_PLN} (−30), live uses
     * {@code LIVE_HALT_PLN} (−18).
     */
    public String dayHalt(double dayPnl, boolean live) {
        if (live) {
            return dayHalt(dayPnl, properties.getLiveHaltPln(), properties.getHardHaltPln());
        }
        return dayHalt(dayPnl);
    }

    private static String dayHalt(double dayPnl, double halt, double hardHalt) {
        if (dayPnl <= hardHalt) {
            return "hard halt day P/L " + dayPnl;
        }
        if (dayPnl <= halt) {
            return "halt day P/L " + dayPnl;
        }
        return null;
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
