package com.adam.server.swing;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.persistence.SwingSignalEntity;
import com.adam.server.persistence.SwingSignalRepository;
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
 * SDD-SWING (H1) scan — the higher-timeframe sibling of {@code ScanService}.
 * Runs on H1 close, evaluates every {@link SwingSymbol} with {@link SddSwingEngine},
 * persists each signal to {@code swing_signals} and fans it out to the
 * {@link SwingNotifier}s (e-mail, …).
 *
 * <p>It never places orders. Demo/Live keep running SDD-M15 unchanged; this
 * exists so the two strategies can be compared over the same period. Candles are
 * account-agnostic, so the shared market-data broker is used.
 */
@Service
public class SwingScanService {

    private static final Logger log = LoggerFactory.getLogger(SwingScanService.class);
    /** H1 lookback: RMA_SLOW(133) + margin, in hours. */
    private static final Duration H1_LOOKBACK = Duration.ofDays(20);
    /** H4 context lookback. */
    private static final Duration H4_LOOKBACK = Duration.ofDays(60);

    private final BrokerBooks books;
    private final SddSwingEngine engine;
    private final SwingSignalRepository signals;
    private final AppProperties properties;
    private final Clock clock;
    private final List<SwingNotifier> notifiers;
    private final SwingExecutionGate execution;
    private final com.adam.server.scan.Mailer mailer;

    private volatile List<SwingScan> lastSignals = List.of();
    private volatile Instant lastScanAt;
    private volatile String lastError;

    public SwingScanService(
            BrokerBooks books,
            SddSwingEngine engine,
            SwingSignalRepository signals,
            AppProperties properties,
            Clock clock,
            List<SwingNotifier> notifiers,
            SwingExecutionGate execution,
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

    public List<SwingScan> last() {
        return lastSignals;
    }

    public Instant lastScanAt() {
        return lastScanAt;
    }

    public String lastError() {
        return lastError;
    }

    @Transactional
    public List<SwingScan> scan() {
        Instant now = clock.instant();
        BrokerClient market = books.marketData();
        List<SwingScan> found = new ArrayList<>();
        try {
            if (!market.configured()) {
                throw new BrokerException("no market-data broker configured");
            }
            market.login();
            Instant fromH1 = now.minus(H1_LOOKBACK);
            Instant fromH4 = now.minus(H4_LOOKBACK);
            for (SwingSymbol symbol : SwingSymbol.universe()) {
                String epic = symbol.epic();
                try {
                    List<Candle> h1 = market.candles(epic, Resolution.H1, fromH1, now, 500);
                    List<Candle> h4 = market.candles(epic, Resolution.H4, fromH4, now, 300);
                    SwingScan signal = engine.evaluate(symbol, epic, h1, h4, now);
                    if (signal != null) {
                        found.add(signal);
                        persist(signal);
                        notify(signal, context(h1, h4, signal));
                        execution.executeSignal(signal);
                        log.info("SWING signal {} {} entry {} stop {} target {} (H4 {})",
                                signal.symbol(), signal.direction(), signal.entry(),
                                signal.stopLevel(), signal.targetLevel(), signal.h4Trend());
                    }
                } catch (RuntimeException e) {
                    log.warn("SWING scan skipped {} ({}): {}", symbol.code(), epic, e.getClass().getSimpleName());
                }
            }
            lastError = null;
            mailer.clearThrottle("scan-swing");
        } catch (BrokerException e) {
            lastError = e.getMessage();
            log.warn("SWING scan aborted: {}", e.getMessage());
            mailer.sendThrottled("scan-swing", "SDD-SWING scan failed",
                    "The hourly SDD-SWING scan failed:\n\n" + e.getMessage()
                            + "\n\n(further failures within 30 min are suppressed)");
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            log.warn("SWING scan failed: {}", e.getClass().getSimpleName());
            mailer.sendThrottled("scan-swing", "SDD-SWING scan failed",
                    "The hourly SDD-SWING scan failed: " + e.getClass().getSimpleName()
                            + "\n\n(further failures within 30 min are suppressed)");
        }
        lastSignals = List.copyOf(found);
        lastScanAt = now;
        return lastSignals;
    }

    private void persist(SwingScan s) {
        try {
            SwingSignalEntity row = new SwingSignalEntity();
            row.setScannedAt(s.timestamp() == null ? Instant.now() : s.timestamp());
            row.setSymbol(s.symbol());
            row.setEpic(s.epic());
            row.setDirection(s.direction() == null ? null : s.direction().name());
            row.setEntry(s.entry());
            row.setStopLevel(s.stopLevel());
            row.setTargetLevel(s.targetLevel());
            row.setH4Trend(s.h4Trend() == null ? null : s.h4Trend().name());
            signals.save(row);
        } catch (Exception e) {
            log.warn("SWING signal persist failed for {}: {}", s.symbol(), e.getClass().getSimpleName());
        }
    }

    private SwingSignalContext context(List<Candle> h1, List<Candle> h4, SwingScan s) {
        try {
            return SwingSignalContext.from(h1, h4, s, java.time.ZoneId.of(properties.getTimezone()));
        } catch (RuntimeException e) {
            log.warn("SWING signal context build failed for {}: {}", s.symbol(), e.getClass().getSimpleName());
            return null;
        }
    }

    private void notify(SwingScan s, SwingSignalContext ctx) {
        for (SwingNotifier n : notifiers) {
            try {
                n.onSwingSignal(s, ctx);
            } catch (Exception e) {
                log.warn("SWING notifier {} failed: {}", n.getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        }
    }
}
