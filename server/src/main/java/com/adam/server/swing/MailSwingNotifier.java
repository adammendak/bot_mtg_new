package com.adam.server.swing;

import com.adam.server.scan.Mailer;
import org.springframework.stereotype.Component;

/**
 * E-mails every SDD-SWING (H1) entry signal via the shared {@link Mailer}
 * (a no-op until SMTP + a recipient are configured). The body reads like an
 * analyst note: the state of both timeframes at the moment of the signal, plus
 * a one-paragraph rationale — not just entry / stop / target.
 */
@Component
public class MailSwingNotifier implements SwingNotifier {

    private final Mailer mailer;

    public MailSwingNotifier(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void onSwingSignal(SwingScan s, SwingSignalContext ctx) {
        String subject = "SDD-SWING " + s.direction() + " " + s.symbol()
                + " @ " + trim(s.entry()) + "  (H4 " + s.h4Trend() + ")";
        mailer.send(subject, ctx == null ? brief(s) : full(s, ctx));
    }

    private static String brief(SwingScan s) {
        return "H1 entry signal\n\n"
                + "symbol    " + s.symbol() + " (" + s.epic() + ")\n"
                + "direction " + s.direction() + "\n"
                + "entry     " + s.entry() + "\n"
                + "stop      " + s.stopLevel() + "\n"
                + "target    " + s.targetLevel() + "\n"
                + "H4 trend  " + s.h4Trend() + "\n"
                + "time      " + s.timestamp() + "\n";
    }

    private static String full(SwingScan s, SwingSignalContext c) {
        boolean buy = "BUY".equals(String.valueOf(s.direction()));
        StringBuilder b = new StringBuilder();
        b.append("SDD-SWING (H1) entry signal — ").append(s.timestamp()).append('\n');
        b.append(s.direction()).append(' ').append(s.symbol()).append(" (").append(s.epic()).append(")\n");

        b.append("\n── H4 (context) ──\n");
        b.append("  trend        ").append(c.h4Trend()).append('\n');
        b.append("  WaveTrend    ").append(trim(c.h4Wt1())).append(" / ").append(trim(c.h4Wt2()))
                .append("   (").append(c.h4WtZone()).append(")\n");
        b.append("  ATR(14)      ").append(trim(c.h4Atr())).append('\n');
        b.append("  vs Pivot     ").append(c.h4VsPivot()).append('\n');

        b.append("\n── H1 (execution) ──\n");
        b.append("  Supertrend   ").append(trendWord(c.h1SupertrendTrend()))
                .append(", line ").append(trim(c.h1SupertrendLine())).append('\n');
        b.append("  RMA33/133    ").append(trim(c.h1Rma33())).append(" / ").append(trim(c.h1Rma133()))
                .append("   (").append(c.h1RmaStack()).append(")\n");
        b.append("  WaveTrend    ").append(trim(c.h1Wt1())).append('\n');
        b.append("  entry        ").append(trim(s.entry())).append('\n');
        b.append("  stop         ").append(trim(s.stopLevel()))
                .append("   (").append(trim(c.stopDistanceAtrH4())).append("x ATR H4 = ")
                .append(trim(c.stopDistancePrice())).append(", ").append(trim(c.stopDistancePct())).append("% of price)\n");
        b.append("  target       ").append(trim(s.targetLevel()))
                .append("   (RR ").append(trim(c.riskReward())).append(":1)\n");

        b.append("\n── Why ──\n").append(why(s, c, buy)).append('\n');
        return b.toString();
    }

    private static String why(SwingScan s, SwingSignalContext c, boolean buy) {
        String dir = buy ? "up" : "down";
        StringBuilder w = new StringBuilder();
        w.append("H4 is trending ").append(dir).append(" and the H4 context agrees with the trade");
        if (c.h4Trend().contains("against")) {
            w.setLength(0);
            w.append("H4 context is ").append(c.h4Trend());
        }
        w.append(". On H1 price is ").append(c.h1RmaStack())
                .append(" and Supertrend is ").append(trendWord(c.h1SupertrendTrend()))
                .append(" (line ").append(buy ? "below" : "above").append(" price), a fresh HA flip triggered the entry, and price sits ")
                .append(c.h4VsPivot()).append(". ");
        switch (c.h4WtZone()) {
            case "oversold extreme", "oversold" -> w.append("H4 WaveTrend is ").append(c.h4WtZone())
                    .append(" — this is a pullback inside the ").append(dir).append("trend, a favourable spot to join.");
            case "overbought extreme", "overbought" -> w.append("Caveat: H4 WaveTrend is ").append(c.h4WtZone())
                    .append(" — the move is stretched, expect a shallower run before the target.");
            default -> w.append("H4 WaveTrend is neutral — no momentum extreme either way.");
        }
        return w.toString();
    }

    private static String trendWord(int t) {
        return t > 0 ? "UP" : t < 0 ? "DOWN" : "n/a";
    }

    private static String trim(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        double r = Math.round(v * 100.0) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
