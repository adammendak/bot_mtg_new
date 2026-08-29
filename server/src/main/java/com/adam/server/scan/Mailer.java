package com.adam.server.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared plain-text mailer. Fail-open like {@code TelegramNotifier}: a no-op
 * until SMTP is configured ({@code SPRING_MAIL_HOST} + {@code SPRING_MAIL_USERNAME}
 * / {@code SPRING_MAIL_PASSWORD} — for Gmail an App Password) and a recipient is
 * set ({@code app.mail.to}, default {@code adam.mendak@gmail.com}). Never throws.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    /** At most one throttled mail per key per this window. */
    private static final Duration THROTTLE = Duration.ofMinutes(30);

    private final JavaMailSender sender;
    private final String to;
    private final String from;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    public Mailer(
            ObjectProvider<JavaMailSender> sender,
            @Value("${app.mail.to:adam.mendak@gmail.com}") String to,
            @Value("${app.mail.from:adam.mtg.bot@gmail.com}") String from
    ) {
        this.sender = sender == null ? null : sender.getIfAvailable();
        this.to = to;
        this.from = from;
    }

    /** No-op instance for tests / callers that construct dependencies by hand. */
    public static Mailer disabled() {
        return new Mailer(null, null, null);
    }

    public boolean enabled() {
        return sender != null && to != null && !to.isBlank();
    }

    /** Send now. Best-effort. */
    public void send(String subject, String body) {
        if (!enabled()) {
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            if (from != null && !from.isBlank()) {
                msg.setFrom(from);
            }
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body == null ? "" : body);
            sender.send(msg);
            log.info("Mail sent to {}: {}", to, subject);
        } catch (Exception e) {
            log.warn("Mail send failed ({}): {}", subject, e.getClass().getSimpleName());
        }
    }

    /**
     * Send at most once per {@code key} per {@link #THROTTLE} window — for
     * repeating failures (a scan that keeps failing every 15 min) so the inbox
     * is not flooded.
     */
    public void sendThrottled(String key, String subject, String body) {
        if (!enabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant prev = lastSent.get(key);
        if (prev != null && prev.plus(THROTTLE).isAfter(now)) {
            return;
        }
        lastSent.put(key, now);
        send(subject, body);
    }

    /** Clear a throttle key so the next failure alerts immediately (call on recovery). */
    public void clearThrottle(String key) {
        lastSent.remove(key);
    }
}
