package com.adam.server.hts;

import com.adam.server.broker.Books;
import com.adam.server.broker.okx.OkxBrokerClient;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.persistence.HtsTradeRepository;
import com.adam.server.scan.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The live OKX universe is <b>perpetual swaps</b> ({@code *-USDT-SWAP}), which
 * never expire, so this watcher is <b>dormant</b> in normal operation — it fires
 * only if a legacy dated {@code -YYMMDD} futures position is ever open on the
 * OKX book ({@link OkxBrokerClient#daysToExpiry} returns {@code -1} for a
 * {@code -SWAP} id and the position is skipped).
 *
 * <p>For such a dated position it is a notifier, not an auto-roller: once it is
 * within {@link OkxBrokerClient#ROLL_WARN_DAYS} of expiry it e-mails a reminder
 * (once per contract per 30&nbsp;min) so the position can be rolled or closed on
 * the user's terms rather than force-settled at the mark price on expiry day.
 */
@Component
public class OkxRolloverWatcher {

    private static final Logger log = LoggerFactory.getLogger(OkxRolloverWatcher.class);

    private final HtsTradeRepository trades;
    private final Mailer mailer;

    public OkxRolloverWatcher(HtsTradeRepository trades, Mailer mailer) {
        this.trades = trades;
        this.mailer = mailer;
    }

    /** Every 6 h (contract expiry is days out); first run 5 min after boot. */
    @Scheduled(fixedDelay = 6L * 60 * 60 * 1000, initialDelay = 5L * 60 * 1000)
    public void check() {
        for (HtsTradeEntity t : trades.findByStatusOrderByIdDesc("OPEN")) {
            if (!Books.OKX.equalsIgnoreCase(t.getBook()) || t.getEpic() == null) {
                continue;
            }
            long days = OkxBrokerClient.daysToExpiry(t.getEpic());
            if (days < 0 || days > OkxBrokerClient.ROLL_WARN_DAYS) {
                continue;
            }
            log.warn("OKX rollover: {} {} on {} expires in {} day(s)",
                    t.getVariant(), t.getSymbol(), t.getEpic(), days);
            mailer.sendThrottled("okx-roll-" + t.getEpic(),
                    "OKX contract expiring — roll or close " + t.getSymbol(),
                    "The OKX " + t.getVariant() + " " + t.getSymbol() + " position is on " + t.getEpic()
                            + ", which expires in " + days + " day(s). OKX force-settles it at expiry — "
                            + "roll it to the next quarter or close it first if you want to exit on your terms.\n\n"
                            + "(further reminders for this contract within 30 min are suppressed)");
        }
    }
}
