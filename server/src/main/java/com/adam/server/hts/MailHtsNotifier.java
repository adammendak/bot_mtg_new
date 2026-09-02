package com.adam.server.hts;

import com.adam.server.scan.Mailer;
import org.springframework.stereotype.Component;

/**
 * E-mails HTS entry signals via the shared {@link Mailer} (a no-op until SMTP +
 * a recipient are configured).
 *
 * <p>Only the <b>HA-hunt</b> strategies ({@link HtsVariant#HA4},
 * {@link HtsVariant#HA12}) mail — see {@link HtsVariant#mailsSignals()}. They are
 * sparse (~230/yr and ~75/yr) and each entry is a one-bar Heikin-Ashi flip, so
 * every signal is mailed with no cooldown. FAST (M5) and the OKX crypto variants
 * signal far too often to mail; CORE_LIVE fills are visible on the dashboard.
 *
 * <p>A short on a long-only variant is mailed too, marked
 * <b>OBSERVE ONLY</b> — the scan does not execute it.
 */
@Component
public class MailHtsNotifier implements HtsNotifier {

    private final Mailer mailer;

    public MailHtsNotifier(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void onHtsSignal(HtsScan s, HtsSignalContext ctx) {
        if (s.variant() == null || !s.variant().mailsSignals()) {
            return;
        }
        boolean observeOnly = isObserveOnly(s);
        String subject = "HTS [" + s.variant().label() + "] " + s.direction() + " " + s.symbol()
                + " @ " + trim(s.entry()) + "  (hunt " + (s.htfUp() ? "bull" : "bear") + ")"
                + (observeOnly ? "  — OBSERVE ONLY" : "");
        mailer.send(subject, body(s, observeOnly));
    }

    /** Short on a long-only variant — mailed for visibility, not traded. */
    private static boolean isObserveOnly(HtsScan s) {
        return s.variant() != null && s.variant().longOnly()
                && !"BUY".equals(String.valueOf(s.direction()));
    }

    private static String body(HtsScan s, boolean observeOnly) {
        double dist = Math.abs(s.entry() - s.stopLevel());
        double pct = s.entry() != 0 ? dist / s.entry() * 100.0 : Double.NaN;
        boolean buy = "BUY".equals(String.valueOf(s.direction()));
        return "HA-hunt cloud entry — " + s.variant().label() + " — " + s.timestamp() + "\n"
                + (observeOnly ? "*** OBSERVE ONLY — short on a long-only variant, NOT executed ***\n" : "")
                + s.direction() + ' ' + s.symbol() + " (" + s.epic() + ")\n\n"
                + "  entry        " + trim(s.entry()) + '\n'
                + "  stop         " + trim(s.stopLevel()) + "   (2.5xATR = 1R = " + trim(dist)
                + " = " + trim(pct) + "% of price)\n"
                + "  target       " + trim(s.targetLevel()) + "   (2R; informational — exit is cloud-hold)\n"
                + "  hunt regime  " + (s.htfUp() ? "bull" : "bear") + " (last-closed HTF Heikin-Ashi)\n\n"
                + "Entry-TF Heikin-Ashi flipped " + (buy ? "bull" : "bear")
                + " on the just-closed bar, aligned with the " + (s.htfUp() ? "bull" : "bear")
                + " hunt regime, RMA stacked, mid-TF confirming, daily pivot aligned.\n"
                + "Exit: fixed stop, OR the last-closed hunt Heikin-Ashi colour flips against the position.\n"
                + "No TP1, no trailing. At most 2 fills per hunt regime per instrument.\n";
    }

    private static String trim(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        double r = Math.round(v * 100.0) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
