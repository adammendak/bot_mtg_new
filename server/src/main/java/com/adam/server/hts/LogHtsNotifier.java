package com.adam.server.hts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Baseline notifier: logs the signal. The rich e-mail is {@link MailHtsNotifier}. */
@Component
public class LogHtsNotifier implements HtsNotifier {

    private static final Logger log = LoggerFactory.getLogger(LogHtsNotifier.class);

    @Override
    public void onHtsSignal(HtsScan s, HtsSignalContext ctx) {
        log.info("HTS signal {} {} entry {} stop {} target {} (HTF {})",
                s.symbol(), s.direction(), s.entry(), s.stopLevel(), s.targetLevel(),
                s.htfUp() ? "up" : "down");
    }
}
