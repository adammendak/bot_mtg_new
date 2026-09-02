package com.adam.server.ops;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Books;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.scan.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregate open-risk watchdog for the <b>Główne</b> ({@code "main"}) account —
 * the view-only account the bot never trades. Every cycle it reads the open
 * positions on US100 / US500 / GER40 / XAU, sums the stop-distance risk
 * ({@code |entry − stop| × size × point-value}, in the account currency), and
 * e-mails once (30-min throttle) when the total exceeds
 * {@code app.glowne.risk-alert-pct} (default 3 %) of the account balance.
 *
 * <p>Positions with no stop-loss set are left out of the sum but listed in the
 * mail as "risk undefined". No-ops when the Główne book is not configured.
 *
 * <p>Cron: {@code app.glowne.risk-alert-cron} (default every 10 min).
 * Toggle: {@code app.glowne.risk-alert-enabled} (default true — fail toward ON).
 */
@Component
public class GlowneRiskWatcher {

    private static final Logger log = LoggerFactory.getLogger(GlowneRiskWatcher.class);
    private static final String MAIL_KEY = "glowne-risk";

    private final BrokerBooks books;
    private final AppProperties properties;
    private final Mailer mailer;
    private final ErrorLog errorLog;
    private final boolean enabled;
    private final double alertPct;

    public GlowneRiskWatcher(BrokerBooks books, AppProperties properties, Mailer mailer, ErrorLog errorLog,
                             @Value("${app.glowne.risk-alert-enabled:true}") boolean enabled,
                             @Value("${app.glowne.risk-alert-pct:3.0}") double alertPct) {
        this.books = books;
        this.properties = properties;
        this.mailer = mailer;
        this.errorLog = errorLog;
        this.enabled = enabled;
        this.alertPct = alertPct;
    }

    /** epic (upper-case) -> short name, for the four watched instruments. */
    private Map<String, String> watched() {
        AppProperties.Epics e = properties.getSdd().getEpics();
        Map<String, String> m = new LinkedHashMap<>();
        m.put(e.getUs100().toUpperCase(Locale.ROOT), "US100");
        m.put(e.getUs500().toUpperCase(Locale.ROOT), "US500");
        m.put(e.getGer40().toUpperCase(Locale.ROOT), "GER40");
        m.put(e.getXau().toUpperCase(Locale.ROOT), "XAU");
        return m;
    }

    @Scheduled(cron = "${app.glowne.risk-alert-cron:20 */10 * * * *}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            BrokerClient g = books.forBook(Books.GLOWNE);
            if (g == null || !g.configured()) {
                log.debug("Główne risk watch: book not configured — skipping");
                return;
            }
            if (!g.isSessionOpen()) {
                g.login();
            }
            Account acct = mainAccount(g);
            if (acct == null || acct.balance() <= 0) {
                log.warn("Główne risk watch: no account / non-positive balance — skipping");
                return;
            }
            Map<String, String> watched = watched();
            double totalRisk = 0;
            List<String> lines = new ArrayList<>();
            List<String> stopless = new ArrayList<>();

            for (Position p : g.openPositions()) {
                if (p.epic() == null) {
                    continue;
                }
                String name = watched.get(p.epic().toUpperCase(Locale.ROOT));
                if (name == null) {
                    continue; // not one of the four
                }
                if (p.stopLevel() == null) {
                    stopless.add(String.format(Locale.ROOT, "  %-6s %s size %s @ %s — NO STOP (risk undefined)",
                            name, p.direction(), trim(p.size()), trim(p.level())));
                    continue;
                }
                double stopDist = Math.abs(p.level() - p.stopLevel());
                double pointValue = pointValueInAccountCcy(g, p.epic(), acct.currency());
                double risk = stopDist * p.size() * pointValue;
                totalRisk += risk;
                lines.add(String.format(Locale.ROOT, "  %-6s %s size %s @ %s, stop %s → risk %s %s (%.2f%%)",
                        name, p.direction(), trim(p.size()), trim(p.level()), trim(p.stopLevel()),
                        trim(risk), acct.currency(), risk / acct.balance() * 100.0));
            }

            double pct = totalRisk / acct.balance() * 100.0;
            if (pct <= alertPct && stopless.isEmpty()) {
                mailer.clearThrottle(MAIL_KEY); // back under the limit — re-arm the alert
                log.debug("Główne risk watch: {}% of {} {} — under the {}% limit",
                        String.format(Locale.ROOT, "%.2f", pct), trim(acct.balance()), acct.currency(), alertPct);
                return;
            }

            StringBuilder body = new StringBuilder();
            body.append("Konto Główne — łączne ryzyko otwartych pozycji przekroczyło ")
                    .append(trim(alertPct)).append("%.\n\n");
            body.append(String.format(Locale.ROOT, "Saldo:        %s %s%n", trim(acct.balance()), acct.currency()));
            body.append(String.format(Locale.ROOT, "Ryzyko razem: %s %s  (%.2f%% salda; limit %s%%)%n%n",
                    trim(totalRisk), acct.currency(), pct, trim(alertPct)));
            if (!lines.isEmpty()) {
                body.append("Pozycje ze stopem (US100 / US500 / GER40 / XAU):\n");
                lines.forEach(l -> body.append(l).append('\n'));
            }
            if (!stopless.isEmpty()) {
                body.append("\nPozycje BEZ stop-lossa — nie wliczone do sumy, sprawdź ręcznie:\n");
                stopless.forEach(l -> body.append(l).append('\n'));
            }
            body.append("\n(kolejny alert dopiero po ~30 min lub gdy ryzyko spadnie pod limit)\n");

            String subject = String.format(Locale.ROOT,
                    "⚠ Główne — ryzyko %.2f%% (limit %s%%), %s %s",
                    pct, trim(alertPct), trim(totalRisk), acct.currency());
            mailer.sendThrottled(MAIL_KEY, subject, body.toString());
            log.info("Główne risk watch: ALERT {}% (risk {} {}, balance {})",
                    String.format(Locale.ROOT, "%.2f", pct), trim(totalRisk), acct.currency(), trim(acct.balance()));
        } catch (Exception e) {
            log.warn("Główne risk watch failed: {}", e.getClass().getSimpleName());
            errorLog.record("glowne-risk", Books.GLOWNE, null, e);
        }
    }

    /** The account carrying the balance — largest by balance on the Główne login. */
    private static Account mainAccount(BrokerClient g) {
        Account best = null;
        for (Account a : g.accounts()) {
            if (best == null || a.balance() > best.balance()) {
                best = a;
            }
        }
        return best;
    }

    private static double pointValueInAccountCcy(BrokerClient g, String epic, String acctCcy) {
        try {
            MarketRules r = g.marketRules(epic);
            double pv = r.pointValue() > 0 ? r.pointValue() : 1.0;
            double fx = g.fxRate(r.currency(), acctCcy);
            return pv * (fx > 0 ? fx : 1.0);
        } catch (Exception e) {
            return 1.0; // best effort — a raw price-point ≈ 1 unit of P/L per contract
        }
    }

    private static String trim(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        double r = Math.round(v * 100.0) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
