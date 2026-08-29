package com.adam.server.hts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Baseline notifier: logs the signal. A rich mail notifier is T10. */
@Component
public class LogHtsNotifier implements HtsNotifier {

    private static final Logger log = LoggerFactory.getLogger(LogHtsNotifier.class);

    @Override
    public void onHtsSignal(HtsScan s) {
        log.info("HTS signal {} {} entry {} stop {} target {} (HTF {})",
                s.symbol(), s.direction(), s.entry(), s.stopLevel(), s.targetLevel(),
                s.htfUp() ? "up" : "down");
    }
}
