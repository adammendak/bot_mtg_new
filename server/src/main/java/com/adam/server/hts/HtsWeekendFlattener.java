package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Books;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.FeatureFlags;
import com.adam.server.scan.Mailer;
import com.adam.server.sdd.SddSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Weekend gap guard for the FAST (H1/M5) model only. FAST is the scalp variant —
 * a scalp trade held through a 65-hour weekend on GER40 / XAU / US100 / EURUSD
 * eats the Monday-open gap. CORE (H4/M15) and SWING (D1/H1) are built to hold
 * multi-day, so they are left alone; BTC trades 24/7, so it is left alone.
 *
 * <p>Friday evening (before the index/FX close) it flattens every open position
 * on the {@code hts} book whose epic is not BTC. The position monitor then
 * reconciles those {@code hts_trades} rows to CLOSED with reason WEEKEND.
 *
 * <p>Cron: {@code app.hts.weekend-flatten-cron} (default Fri 21:45 Europe/Warsaw).
 * Toggle: the {@code hts.weekend-flatten} feature flag.
 */
@Component
public class HtsWeekendFlattener {

    private static final Logger log = LoggerFactory.getLogger(HtsWeekendFlattener.class);
    private static final String FLAG = "hts.weekend-flatten";

    private final BrokerBooks books;
    private final FeatureFlags flags;
    private final AppProperties properties;
    private final HtsTradeService trades;
    private final Mailer mailer;
    private final ErrorLog errorLog;

    public HtsWeekendFlattener(BrokerBooks books, FeatureFlags flags, AppProperties properties,
                               HtsTradeService trades, Mailer mailer, ErrorLog errorLog) {
        this.books = books;
        this.flags = flags;
        this.properties = properties;
        this.trades = trades;
        this.mailer = mailer;
        this.errorLog = errorLog;
    }

    @Scheduled(cron = "${app.hts.weekend-flatten-cron:0 45 21 * * FRI}", zone = "${app.scan.zone:Europe/Warsaw}")
    public void run() {
        if (!flags.enabled(FLAG)) {
            return;
        }
        String btcEpic = SddSymbol.BTC.epic(properties);
        int closed = 0;
        StringBuilder detail = new StringBuilder();
        try {
            BrokerClient hts = books.forBook(Books.HTS);
            if (hts == null || !hts.configured()) {
                log.info("HTS weekend flatten: hts book not configured — nothing to do");
                return;
            }
            if (!hts.isSessionOpen()) {
                hts.login();
            }
            List<Position> open = hts.openPositions();
            for (Position p : open) {
                if (p.dealId() == null || p.epic() == null || p.epic().equalsIgnoreCase(btcEpic)) {
                    continue; // keep BTC (24/7), skip malformed rows
                }
                try {
                    hts.closePosition(p.dealId(), p.size());
                    closed++;
                    detail.append(p.epic()).append(' ').append(p.direction()).append(' ').append(p.size()).append('\n');
                    log.info("HTS weekend flatten: closed {} {} size {} (deal {})",
                            p.epic(), p.direction(), p.size(), p.dealId());
                } catch (Exception e) {
                    log.warn("HTS weekend flatten: close failed for {} ({})", p.epic(), e.getClass().getSimpleName());
                    errorLog.record("hts-weekend", Books.HTS, p.epic(), e);
                }
            }
            if (closed > 0) {
                trades.tagWeekend(Books.HTS, btcEpic);
                trades.manage(); // reconcile the just-closed deals now, don't wait a cycle
                mailer.send("HTS weekend flatten — " + closed + " pozycji zamkniętych (konto m5)",
                        "FAST (H1/M5) pozycje zamknięte przed weekendem (BTC zostaje):\n\n" + detail);
            }
            log.info("HTS weekend flatten done — {} closed, {} left open", closed, open.size() - closed);
        } catch (Exception e) {
            log.warn("HTS weekend flatten failed: {}", e.getClass().getSimpleName());
            errorLog.record("hts-weekend", Books.HTS, null, e);
        }
    }
}
