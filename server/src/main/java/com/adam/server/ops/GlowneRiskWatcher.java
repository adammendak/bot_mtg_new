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

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregate open-risk report for the <b>Główne</b> ({@code "main"}) account —
 * the view-only account the bot never trades. Every cycle it reads the open
 * positions on XAU / XAG / US100 / US500 / US30 / GER40 / J225 / EURUSD /
 * USDJPY and sums the stop-distance risk ({@code |entry − stop| × size ×
 * point-value}, in the account currency) as a percentage of balance.
 *
 * <p>Mails on two triggers, not a fixed alert-only threshold:
 * <ol>
 *   <li><b>Scheduled check-in</b>, 3×/day at {@link #SCHEDULED_TIMES} (08:30,
 *       12:30, 18:00 by default, {@code app.scan.zone}) — a status snapshot
 *       regardless of the level, so the account gets a regular pulse even on
 *       a quiet day;</li>
 *   <li><b>Big move</b> — the moment risk has drifted {@link #CHANGE_TRIGGER_PCT}
 *       percentage points (default 2) away from whatever was last actually
 *       mailed, so a sudden spike between check-ins is not missed.</li>
 * </ol>
 * The comparison baseline for (2) only updates when a mail actually goes out
 * — not every cycle — otherwise a slow drift would never accumulate past the
 * threshold (each individual 10-min step staying under 2 points forever).
 * {@code app.glowne.risk-alert-pct} (default 3 %) no longer gates sending; it
 * only marks the mail ⚠ vs ℹ and appears in the body for context.
 *
 * <p>Positions with no stop-loss set are left out of the sum but listed in the
 * mail as "risk undefined". No-ops when the Główne book is not configured.
 * The very first run after a restart only establishes the baseline — it never
 * mails on its own, so a redeploy does not itself trigger a "risk changed" mail.
 *
 * <p>Cron: {@code app.glowne.risk-alert-cron} (default every 10 min — needs to
 * land on :00/:10/…/:50 past the hour for the schedule check above to line up
 * exactly with 08:30/12:30/18:00). Toggle: {@code app.glowne.risk-alert-enabled}
 * (default true — fail toward ON).
 */
@Component
public class GlowneRiskWatcher {

    private static final Logger log = LoggerFactory.getLogger(GlowneRiskWatcher.class);
    /** Daily check-in times — always mails a snapshot, regardless of level. */
    private static final LocalTime[] SCHEDULED_TIMES = {
            LocalTime.of(8, 30), LocalTime.of(12, 30), LocalTime.of(18, 0)
    };
    /** Mail immediately once risk has moved this many percentage points since the last mail. */
    private static final double CHANGE_TRIGGER_PCT = 2.0;

    private final BrokerBooks books;
    private final AppProperties properties;
    private final Mailer mailer;
    private final ErrorLog errorLog;
    private final Clock clock;
    private final boolean enabled;
    private final double alertPct;

    /** {@code null} = never mailed yet this run (fresh boot) — next run only baselines. */
    private volatile Double lastMailedPct;
    /** The scheduled slot (date + time) last mailed for, so each slot fires at most once/day. */
    private volatile String lastScheduledSlot;
    /** Whether the last mail (or the baseline run) saw a stopless position — a stopless
     *  position only re-triggers a mail on the transition into that state, not every
     *  cycle it persists (it's still listed in any mail sent for another reason). */
    private volatile Boolean lastMailedHadStopless;

    public GlowneRiskWatcher(BrokerBooks books, AppProperties properties, Mailer mailer, ErrorLog errorLog,
                             Clock clock,
                             @Value("${app.glowne.risk-alert-enabled:true}") boolean enabled,
                             @Value("${app.glowne.risk-alert-pct:3.0}") double alertPct) {
        this.books = books;
        this.properties = properties;
        this.mailer = mailer;
        this.errorLog = errorLog;
        this.clock = clock;
        this.enabled = enabled;
        this.alertPct = alertPct;
    }

    /** epic (upper-case) -> short name, for the nine watched instruments. */
    private Map<String, String> watched() {
        AppProperties.Epics e = properties.getSdd().getEpics();
        Map<String, String> m = new LinkedHashMap<>();
        m.put(e.getXau().toUpperCase(Locale.ROOT), "XAU");
        m.put(e.getXag().toUpperCase(Locale.ROOT), "XAG");
        m.put(e.getUs100().toUpperCase(Locale.ROOT), "US100");
        m.put(e.getUs500().toUpperCase(Locale.ROOT), "US500");
        m.put(e.getUs30().toUpperCase(Locale.ROOT), "US30");
        m.put(e.getGer40().toUpperCase(Locale.ROOT), "GER40");
        m.put(e.getJ225().toUpperCase(Locale.ROOT), "J225");
        m.put(e.getEurusd().toUpperCase(Locale.ROOT), "EURUSD");
        m.put(e.getUsdjpy().toUpperCase(Locale.ROOT), "USDJPY");
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
                    continue; // not one of the nine watched instruments
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
            String scheduledSlot = matchingScheduledSlot();
            boolean hasStopless = !stopless.isEmpty();
            Double previousPct = lastMailedPct;

            if (previousPct == null) {
                // fresh boot — establish the baseline silently, never mail on its own
                // (a redeploy would otherwise itself look like a "risk changed" event).
                lastMailedPct = pct;
                lastMailedHadStopless = hasStopless;
                if (scheduledSlot != null) {
                    lastScheduledSlot = scheduledSlot;
                }
                log.debug("Główne risk watch: baseline {}% of {} {} (no mail — first run since boot)",
                        String.format(Locale.ROOT, "%.2f", pct), trim(acct.balance()), acct.currency());
                return;
            }

            boolean isScheduled = scheduledSlot != null && !scheduledSlot.equals(lastScheduledSlot);
            boolean changedEnough = Math.abs(pct - previousPct) >= CHANGE_TRIGGER_PCT;
            boolean stoplessJustAppeared = hasStopless && !Boolean.TRUE.equals(lastMailedHadStopless);
            if (!isScheduled && !changedEnough && !stoplessJustAppeared) {
                log.debug("Główne risk watch: {}% of {} {} — not due (last mailed {}%)",
                        String.format(Locale.ROOT, "%.2f", pct), trim(acct.balance()), acct.currency(),
                        String.format(Locale.ROOT, "%.2f", previousPct));
                return;
            }

            String reason = isScheduled ? "raport okresowy"
                    : changedEnough ? String.format(Locale.ROOT, "zmiana %.2f%%→%.2f%%", previousPct, pct)
                    : "pozycja bez stopu";

            StringBuilder body = new StringBuilder();
            body.append("Konto Główne — łączne ryzyko otwartych pozycji.\n\n");
            body.append("Powód mejla: ").append(reason).append(".\n\n");
            body.append(String.format(Locale.ROOT, "Saldo:        %s %s%n", trim(acct.balance()), acct.currency()));
            body.append(String.format(Locale.ROOT, "Ryzyko razem: %s %s  (%.2f%% salda; limit %s%%)%n%n",
                    trim(totalRisk), acct.currency(), pct, trim(alertPct)));
            if (!lines.isEmpty()) {
                body.append("Pozycje ze stopem (XAU/XAG/US100/US500/US30/GER40/J225/EURUSD/USDJPY):\n");
                lines.forEach(l -> body.append(l).append('\n'));
            }
            if (hasStopless) {
                body.append("\nPozycje BEZ stop-lossa — nie wliczone do sumy, sprawdź ręcznie:\n");
                stopless.forEach(l -> body.append(l).append('\n'));
            }
            body.append(String.format(Locale.ROOT,
                    "%n(mejle: 3x dziennie o 8:30/12:30/18:00, plus natychmiast przy zmianie o %s%% lub więcej)%n",
                    trim(CHANGE_TRIGGER_PCT)));

            String icon = pct > alertPct ? "⚠" : "ℹ";
            String subject = String.format(Locale.ROOT,
                    "%s Główne — ryzyko %.2f%% (limit %s%%), %s %s [%s]",
                    icon, pct, trim(alertPct), trim(totalRisk), acct.currency(), reason);
            mailer.send(subject, body.toString());
            lastMailedPct = pct;
            lastMailedHadStopless = hasStopless;
            if (isScheduled) {
                lastScheduledSlot = scheduledSlot;
            }
            log.info("Główne risk watch: mailed {}% (risk {} {}, balance {}, reason {})",
                    String.format(Locale.ROOT, "%.2f", pct), trim(totalRisk), acct.currency(), trim(acct.balance()),
                    reason);
        } catch (Exception e) {
            log.warn("Główne risk watch failed: {}", e.getClass().getSimpleName());
            errorLog.record("glowne-risk", Books.GLOWNE, null, e);
        }
    }

    /**
     * {@code "yyyy-MM-dd'T'HH:mm"} for the {@link #SCHEDULED_TIMES} entry matching the
     * current local time exactly (minute precision — the cron always lands on :00/:10/
     * …/:50 past the hour, so an exact match is reliable), or {@code null} outside one.
     */
    private String matchingScheduledSlot() {
        ZoneId zone = ZoneId.of(properties.getScan().getZone());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        LocalTime nowTime = now.toLocalTime().withSecond(0).withNano(0);
        for (LocalTime t : SCHEDULED_TIMES) {
            if (nowTime.equals(t)) {
                LocalDate date = now.toLocalDate();
                return date + "T" + t;
            }
        }
        return null;
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
