package com.adam.server.swing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * E-mails every SDD-SWING (H1) entry signal. Fail-open, like {@code TelegramNotifier}:
 * a no-op until SMTP is configured (set {@code SPRING_MAIL_HOST} +
 * {@code SPRING_MAIL_USERNAME} / {@code SPRING_MAIL_PASSWORD} on the host — for
 * Gmail that password is an App Password) and a recipient is set
 * ({@code app.mail.to}, default {@code adam.mendak@gmail.com}).
 */
@Component
public class MailSwingNotifier implements SwingNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailSwingNotifier.class);

    private final JavaMailSender mail;
    private final String to;
    private final String from;

    public MailSwingNotifier(
            ObjectProvider<JavaMailSender> mail,
            @Value("${app.mail.to:adam.mendak@gmail.com}") String to,
            @Value("${app.mail.from:adam.mtg.bot@gmail.com}") String from
    ) {
        this.mail = mail.getIfAvailable();
        this.to = to;
        this.from = from;
    }

    @Override
    public void onSwingSignal(SwingScan s) {
        if (mail == null || to == null || to.isBlank()) {
            return; // SMTP or recipient not configured
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            if (from != null && !from.isBlank()) {
                msg.setFrom(from);
            }
            msg.setTo(to);
            msg.setSubject("SDD-SWING " + s.direction() + " " + s.symbol());
            msg.setText(
                    "H1 entry signal\n\n"
                            + "symbol    " + s.symbol() + " (" + s.epic() + ")\n"
                            + "direction " + s.direction() + "\n"
                            + "entry     " + s.entry() + "\n"
                            + "stop      " + s.stopLevel() + "\n"
                            + "target    " + s.targetLevel() + "\n"
                            + "H4 trend  " + s.h4Trend() + "\n"
                            + "time      " + s.timestamp() + "\n");
            mail.send(msg);
            log.info("SWING signal e-mailed to {} ({} {})", to, s.symbol(), s.direction());
        } catch (Exception e) {
            log.warn("SWING signal e-mail failed: {}", e.getClass().getSimpleName());
        }
    }
}
