package com.adam.server.hts;

import com.adam.server.scan.Mailer;
import org.springframework.stereotype.Component;

/**
 * E-mails every HTS ("wstęgi") entry signal via the shared {@link Mailer}
 * (a no-op until SMTP + a recipient are configured). The body reads like an
 * analyst note: the band / ADX picture on both timeframes at the moment of the
 * signal, the trade geometry, and a one-paragraph rationale — not just
 * entry / stop / target.
 */
@Component
public class MailHtsNotifier implements HtsNotifier {

    private final Mailer mailer;

    public MailHtsNotifier(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void onHtsSignal(HtsScan s, HtsSignalContext ctx) {
        String subject = "HTS " + s.direction() + " " + s.symbol()
                + " @ " + trim(s.entry()) + "  (HTF " + (s.htfUp() ? "up" : "down") + ")";
        mailer.send(subject, ctx == null ? brief(s) : full(s, ctx));
    }

    private static String brief(HtsScan s) {
        return "HTS (wstęgi) entry signal\n\n"
                + "symbol    " + s.symbol() + " (" + s.epic() + ")\n"
                + "direction " + s.direction() + "\n"
                + "entry     " + s.entry() + "\n"
                + "stop      " + s.stopLevel() + "\n"
                + "target    " + s.targetLevel() + "  (TP1, 1:2)\n"
                + "HTF band  " + (s.htfUp() ? "up" : "down") + "\n"
                + "time      " + s.timestamp() + "\n";
    }

    private static String full(HtsScan s, HtsSignalContext c) {
        boolean buy = "BUY".equals(String.valueOf(s.direction()));
        StringBuilder b = new StringBuilder();
        b.append("HTS (wstęgi) entry signal — ").append(s.timestamp()).append('\n');
        b.append(s.direction()).append(' ').append(s.symbol()).append(" (").append(s.epic()).append(")\n");

        b.append("\n── D1 (context) ──\n");
        b.append("  band state   ").append(c.htfState()).append('\n');
        b.append("  slow slope   ").append(c.htfSlowSlope()).append('\n');

        b.append("\n── H1 (execution) ──\n");
        b.append("  fast vs slow ").append(trim(c.ltfBandGapAtr())).append("x slow-band width apart\n");
        b.append("  slow slope   ").append(c.ltfSlowSlope()).append('\n');
        b.append("  pulled back  ").append(c.pulledBackBars() < 0
                ? "not in the last " + HtsEngine.PULLBACK_BARS + " bars"
                : c.pulledBackBars() + " bar(s) ago").append('\n');
        b.append("  ADX(14)      ").append(trim(c.adx()))
                .append("  (+DI ").append(trim(c.plusDi())).append(" / -DI ").append(trim(c.minusDi()))
                .append(", ").append(c.adxZone()).append(")\n");
        b.append("  ATR(14)      ").append(trim(c.atr())).append('\n');
        b.append("  vs Pivot     ").append(c.vsPivot()).append('\n');

        b.append("\n── Trade ──\n");
        b.append("  entry        ").append(trim(s.entry())).append('\n');
        b.append("  stop         ").append(trim(s.stopLevel()))
                .append("   (behind the fast-band edge ").append(trim(c.stopBandEdge()))
                .append(" + ").append((int) Math.round(HtsEngine.STOP_BUFFER_FRAC * 100)).append("% buffer; ")
                .append(trim(c.stopDistancePrice())).append(" = ").append(trim(c.stopDistancePct())).append("% of price)\n");
        b.append("  target (TP1) ").append(trim(s.targetLevel()))
                .append("   (RR ").append(trim(c.riskReward())).append(":1, half off here)\n");
        b.append("  runner       held to a body close beyond the slow band; stop trails the fast band after TP1\n");

        b.append("\n── Why ──\n").append(why(s, c, buy)).append('\n');
        return b.toString();
    }

    private static String why(HtsScan s, HtsSignalContext c, boolean buy) {
        String dir = buy ? "up" : "down";
        StringBuilder w = new StringBuilder();
        w.append("Both timeframes are trending ").append(dir)
                .append(": the D1 band is ").append(c.htfState())
                .append(" and the H1 fast band sits clear of the slow band (")
                .append(trim(c.ltfBandGapAtr())).append("x its width), slope ").append(c.ltfSlowSlope())
                .append(". Price pulled back into the fast band ")
                .append(c.pulledBackBars() < 0 ? "recently" : c.pulledBackBars() + " bar(s) ago")
                .append(" and this candle closed its body back beyond the edge — the entry trigger. ");
        w.append("Structural stop is behind the far fast-band edge; 1R is ")
                .append(trim(c.stopDistancePct())).append("% of price, TP1 at 1:2, the runner rides to the slow band. ");
        switch (c.adxZone()) {
            case "trend" -> w.append("ADX is ").append(trim(c.adx()))
                    .append(" (trending) with ").append(buy ? "+DI" : "-DI").append(" leading — momentum backs the trade.");
            case "no-trend (blue)" -> w.append("Caveat: ADX is only ").append(trim(c.adx()))
                    .append(" (blue / no-trend) — the bands lead here, expect a smaller follow-through.");
            default -> w.append("ADX is n/a (warm-up).");
        }
        if ("above PP".equals(c.vsPivot()) || "below PP".equals(c.vsPivot()) || c.vsPivot().contains("PP by")) {
            w.append(" Price is ").append(c.vsPivot()).append('.');
        }
        return w.toString();
    }

    private static String trim(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        double r = Math.round(v * 100.0) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
