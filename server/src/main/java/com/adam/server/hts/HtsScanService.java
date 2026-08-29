package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.persistence.HtsSignalEntity;
import com.adam.server.persistence.HtsSignalRepository;
import com.adam.server.sdd.SddSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * HTS ("wstęgi") scan — the 3rd strategy's live scan, sibling of
 * {@code ScanService} (SDD-M15) and {@link com.adam.server.swing.SwingScanService}.
 * Primary swing model D1(context) / H1(entry): runs on H1 close, evaluates every
 * {@link SddSymbol} with {@link HtsEngine}, persists each signal to
 * {@code hts_signals}, fans it out to the {@link HtsNotifier}s and hands it to
 * {@link HtsExecutionGate} (off unless {@code HTS_EXECUTION_ENABLED=true}).
 *
 * <p>Candles are account-agnostic, so the shared market-data broker is used.
 * SDD-M15 and SDD-SWING keep running unchanged; this exists so the three
 * strategies can be compared over the same period.
 */
@Service
public class HtsScanService {

    private static final Logger log = LoggerFactory.getLogger(HtsScanService.class);
    /** H1 (entry) lookback — SLOW_LEN(144) bars + margin, with weekend gaps. */
    private static final Duration H1_LOOKBACK = Duration.ofDays(30);
    /** D1 (context) lookback — SLOW_LEN(144) daily bars need ~7 trading months. */
    private static final Duration D1_LOOKBACK = Duration.ofDays(260);

    private final BrokerBooks books;
    private final HtsEngine engine;
    private final HtsSignalRepository signals;
    private final com.adam.server.config.AppProperties properties;
    private final Clock clock;
    private final List<HtsNotifier> notifiers;
    private final HtsExecutionGate execution;
    private final com.adam.server.scan.Mailer mailer;

    private volatile List<HtsScan> lastSignals = List.of();
    private volatile Instant lastScanAt;
    private volatile String lastError;

    public HtsScanService(
            BrokerBooks books,
            HtsEngine engine,
            HtsSignalRepository signals,
            com.adam.server.config.AppProperties properties,
            Clock clock,
            List<HtsNotifier> notifiers,
            HtsExecutionGate execution,
            com.adam.server.scan.Mailer mailer
    ) {
        this.books = books;
        this.engine = engine;
        this.signals = signals;
        this.properties = properties;
        this.clock = clock;
        this.notifiers = notifiers;
        this.execution = execution;
        this.mailer = mailer;
    }

    public List<HtsScan> last() {
        return lastSignals;
    }

    public Instant lastScanAt() {
        return lastScanAt;
    }

    public String lastError() {
        return lastError;
    }

    @Transactional
    public List<HtsScan> scan() {
        Instant now = clock.instant();
        BrokerClient market = books.marketData();
        List<HtsScan> found = new ArrayList<>();
        try {
            if (!market.configured()) {
                throw new BrokerException("no market-data broker configured");
            }
            market.login();
            Instant fromH1 = now.minus(H1_LOOKBACK);
            Instant fromD1 = now.minus(D1_LOOKBACK);
            for (SddSymbol symbol : SddSymbol.universeFor(now, java.time.ZoneOffset.UTC)) {
                String epic = symbol.epic(properties);
                try {
                    List<Candle> h1 = market.candles(epic, Resolution.H1, fromH1, now, 1000);
                    List<Candle> d1 = market.candles(epic, Resolution.D1, fromD1, now, 1000);
                    HtsScan signal = engine.evaluate(symbol.code(), epic, h1, d1, now);
                    if (signal != null) {
                        found.add(signal);
                        persist(signal);
                        notify(signal);
                        execution.executeSignal(signal);
                        log.info("HTS signal {} {} entry {} stop {} target {} (HTF {})",
                                signal.symbol(), signal.direction(), signal.entry(),
                                signal.stopLevel(), signal.targetLevel(), signal.htfUp() ? "up" : "down");
                    }
                } catch (RuntimeException e) {
                    log.warn("HTS scan skipped {} ({}): {}", symbol.code(), epic, e.getClass().getSimpleName());
                }
            }
            lastError = null;
            mailer.clearThrottle("scan-hts");
        } catch (BrokerException e) {
            lastError = e.getMessage();
            log.warn("HTS scan aborted: {}", e.getMessage());
            mailer.sendThrottled("scan-hts", "HTS scan failed",
                    "The hourly HTS scan failed:\n\n" + e.getMessage()
                            + "\n\n(further failures within 30 min are suppressed)");
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            log.warn("HTS scan failed: {}", e.getClass().getSimpleName());
            mailer.sendThrottled("scan-hts", "HTS scan failed",
                    "The hourly HTS scan failed: " + e.getClass().getSimpleName()
                            + "\n\n(further failures within 30 min are suppressed)");
        }
        lastSignals = List.copyOf(found);
        lastScanAt = now;
        return lastSignals;
    }

    private void persist(HtsScan s) {
        try {
            HtsSignalEntity row = new HtsSignalEntity();
            row.setScannedAt(s.timestamp() == null ? Instant.now() : s.timestamp());
            row.setSymbol(s.symbol());
            row.setEpic(s.epic());
            row.setDirection(s.direction() == null ? null : s.direction().name());
            row.setEntry(s.entry());
            row.setStopLevel(s.stopLevel());
            row.setTargetLevel(s.targetLevel());
            row.setHtfUp(s.htfUp());
            signals.save(row);
        } catch (Exception e) {
            log.warn("HTS signal persist failed for {}: {}", s.symbol(), e.getClass().getSimpleName());
        }
    }

    private void notify(HtsScan s) {
        for (HtsNotifier n : notifiers) {
            try {
                n.onHtsSignal(s);
            } catch (Exception e) {
                log.warn("HTS notifier {} failed: {}", n.getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        }
    }
}
