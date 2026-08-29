package com.adam.server.swing;

import com.adam.server.scan.Mailer;
import org.springframework.stereotype.Component;

/**
 * E-mails every SDD-SWING (H1) entry signal via the shared {@link Mailer}
 * (a no-op until SMTP + a recipient are configured).
 */
@Component
public class MailSwingNotifier implements SwingNotifier {

    private final Mailer mailer;

    public MailSwingNotifier(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void onSwingSignal(SwingScan s) {
        mailer.send(
                "SDD-SWING " + s.direction() + " " + s.symbol(),
                "H1 entry signal\n\n"
                        + "symbol    " + s.symbol() + " (" + s.epic() + ")\n"
                        + "direction " + s.direction() + "\n"
                        + "entry     " + s.entry() + "\n"
                        + "stop      " + s.stopLevel() + "\n"
                        + "target    " + s.targetLevel() + "\n"
                        + "H4 trend  " + s.h4Trend() + "\n"
                        + "time      " + s.timestamp() + "\n");
    }
}
