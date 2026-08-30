package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.model.Candle;
import com.adam.server.persistence.HtsSignalEntity;
import com.adam.server.persistence.HtsSignalRepository;
import com.adam.server.sdd.SddSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * HTS ("wstęgi") scan — runs the <b>three timeframe models</b> ({@link HtsVariant})
 * side by side for the September forward test, one per demo account:
 * CORE H4/M15 → "Account m15", SWING D1/H1 → "Account H1", FAST H1/M5 → "Account m5".
 *
 * <p>Each pass, for every variant: fetch its HTF + LTF candles, evaluate every
 * {@link SddSymbol} in the HTS universe (weekdays = index/metal/FX, weekend = BTC)
 * with {@link HtsEngine}, persist each signal to {@code hts_signals} tagged with
 * its variant, fan it out to the {@link HtsNotifier}s and hand it to
 * {@link HtsExecutionGate} (which places on that variant's book when
 * {@code HTS_EXECUTION_ENABLED=true}).
 *
 * <p>Candles are account-agnostic, so the shared market-data broker is used.
 * SDD-M15 / SDD-SWING are archived (scans + execution gated off) — HTS is the focus.
 */
@Service
public class HtsScanService {

    private static final Logger log = LoggerFactory.getLogger(HtsScanService.class);

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
            ZoneId zone = ZoneId.of(properties.getTimezone());
            List<SddSymbol> universe = SddSymbol.htsUniverseFor(now, zone);
            for (HtsVariant variant : HtsVariant.values()) {
                scanVariant(variant, universe, market, now, found);
            }
            lastError = null;
            mailer.clearThrottle("scan-hts");
        } catch (BrokerException e) {
            lastError = e.getMessage();
            log.warn("HTS scan aborted: {}", e.getMessage());
            mailer.sendThrottled("scan-hts", "HTS scan failed",
                    "The HTS scan failed:\n\n" + e.getMessage()
                            + "\n\n(further failures within 30 min are suppressed)");
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            log.warn("HTS scan failed: {}", e.getClass().getSimpleName());
            mailer.sendThrottled("scan-hts", "HTS scan failed",
                    "The HTS scan failed: " + e.getClass().getSimpleName()
                            + "\n\n(further failures within 30 min are suppressed)");
        }
        lastSignals = List.copyOf(found);
        lastScanAt = now;
        return lastSignals;
    }

    private void scanVariant(HtsVariant v, List<SddSymbol> universe, BrokerClient market,
                             Instant now, List<HtsScan> found) {
        Instant fromHtf = now.minus(v.htfLookback());
        Instant fromLtf = now.minus(v.ltfLookback());
        for (SddSymbol symbol : universe) {
            String epic = symbol.epic(properties);
            try {
                List<Candle> ltf = market.candles(epic, v.ltf(), fromLtf, now, 1000);
                List<Candle> htf = market.candles(epic, v.htf(), fromHtf, now, 1000);
                HtsScan signal = engine.evaluate(v, symbol.code(), epic, ltf, htf, now);
                if (signal != null) {
                    found.add(signal);
                    persist(signal);
                    notify(signal, context(ltf, htf, signal));
                    execution.executeSignal(signal);
                    log.info("HTS [{}] signal {} {} entry {} stop {} target {} (HTF {})",
                            v.label(), signal.symbol(), signal.direction(), signal.entry(),
                            signal.stopLevel(), signal.targetLevel(), signal.htfUp() ? "up" : "down");
                }
            } catch (RuntimeException e) {
                log.warn("HTS [{}] scan skipped {} ({}): {}", v.name(), symbol.code(), epic,
                        e.getClass().getSimpleName());
            }
        }
    }

    private void persist(HtsScan s) {
        try {
            HtsSignalEntity row = new HtsSignalEntity();
            row.setScannedAt(s.timestamp() == null ? Instant.now() : s.timestamp());
            row.setVariant(s.variant() == null ? null : s.variant().name());
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

    private HtsSignalContext context(List<Candle> ltf, List<Candle> htf, HtsScan s) {
        try {
            return HtsSignalContext.from(ltf, htf, s, ZoneId.of(properties.getTimezone()));
        } catch (RuntimeException e) {
            log.warn("HTS signal context build failed for {}: {}", s.symbol(), e.getClass().getSimpleName());
            return null;
        }
    }

    private void notify(HtsScan s, HtsSignalContext ctx) {
        for (HtsNotifier n : notifiers) {
            try {
                n.onHtsSignal(s, ctx);
            } catch (Exception e) {
                log.warn("HTS notifier {} failed: {}", n.getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        }
    }
}
