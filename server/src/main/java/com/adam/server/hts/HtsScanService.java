package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Books;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.okx.OkxSymbol;
import com.adam.server.persistence.HtsSignalEntity;
import com.adam.server.persistence.HtsSignalRepository;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.sdd.SddSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final HtsTradeService trades;
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
            HtsTradeService trades,
            com.adam.server.scan.Mailer mailer
    ) {
        this.books = books;
        this.engine = engine;
        this.signals = signals;
        this.properties = properties;
        this.clock = clock;
        this.notifiers = notifiers;
        this.execution = execution;
        this.trades = trades;
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
        ZoneId zone = ZoneId.of(properties.getTimezone());
        List<HtsScan> found = new ArrayList<>();
        try {
            int minute = now.atZone(zone).getMinute();
            for (HtsVariant variant : HtsVariant.values()) {
                if (!variant.dueAtMinute(minute)) {
                    continue;
                }
                // OKX variants scan crypto on the OKX book; Capital variants scan
                // the shared market-data broker with the SDD universe.
                BrokerClient market = variant.book().equals(Books.OKX)
                        ? books.forBook(Books.OKX)
                        : books.marketData();
                if (market == null || !market.configured()) {
                    log.warn("HTS [{}] scan skipped — no configured broker for book {}",
                            variant.name(), variant.book());
                    continue;
                }
                try {
                    // Only (re)authenticate when the session is actually stale.
                    // This loop runs per due variant, and several variants share
                    // one broker — an unconditional login() here would hit
                    // Capital's rate-limited POST /session up to 4× per
                    // top-of-hour scan.
                    if (!market.isSessionOpen()) {
                        market.login();
                    }
                    if (variant.book().equals(Books.OKX)) {
                        BrokerClient okx = market;
                        scanVariant(variant, OkxSymbol.universe().stream()
                                        .map(s -> new HtsInstrument(s.code(), okx.resolveEpic(s.instId()))).toList(),
                                market, now, found);
                    } else {
                        scanVariant(variant, SddSymbol.htsUniverseFor(now, zone).stream()
                                        .map(s -> new HtsInstrument(s.code(), s.epic(properties))).toList(),
                                market, now, found);
                    }
                } catch (BrokerException e) {
                    // One broken book (e.g. OKX creds wrong) must not abort the
                    // whole HTS scan. Alert once per book, then move on.
                    log.warn("HTS [{}] scan skipped — {} book: {}",
                            variant.name(), variant.book(), e.getMessage());
                    mailer.sendThrottled("scan-hts-" + variant.book(),
                            "HTS scan skipped — " + variant.book() + " book",
                            "The HTS scan could not run the " + variant.book() + " book:\n\n"
                                    + e.getMessage() + "\n\n(further failures within 30 min are suppressed)");
                }
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

    /** Instrument (short code + broker epic) fed to the scan loop. */
    private record HtsInstrument(String code, String epic) {
    }

    /**
     * Fire ONE synthetic HTS entry straight through the real execution gate — an
     * end-to-end live check of place → confirm → {@code recordOpen} against the
     * broker (demo book). The entry is the current market mid, the stop is
     * {@code 0.4%} away and the target 2R; the bar timestamp is "now" so it never
     * collides with a real signal's idempotency key. Honours the
     * {@code hts.execution} feature flag. Returns a small status map for the API.
     */
    @Transactional
    public Map<String, Object> testEntry(HtsVariant variant, String symbolCode, Direction direction) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean okxVariant = variant != null && Books.OKX.equalsIgnoreCase(variant.book());
        String code;
        String epic;
        if (okxVariant) {
            OkxSymbol symbol = null;
            for (OkxSymbol s : OkxSymbol.universe()) {
                if (s.code().equalsIgnoreCase(symbolCode)) {
                    symbol = s;
                    break;
                }
            }
            if (symbol == null) {
                out.put("ok", false);
                out.put("error", "unknown OKX symbol '" + symbolCode + "' (try one of BTC/ETH/SOL/XRP/DOGE/LTC)");
                return out;
            }
            code = symbol.code();
            epic = books.forBook(Books.OKX).resolveEpic(symbol.instId());
        } else {
            SddSymbol symbol = null;
            for (SddSymbol s : SddSymbol.values()) {
                if (s.code().equalsIgnoreCase(symbolCode)) {
                    symbol = s;
                    break;
                }
            }
            if (symbol == null) {
                out.put("ok", false);
                out.put("error", "unknown symbol '" + symbolCode + "' (try one of GER40/XAU/US100/EURUSD/BTC)");
                return out;
            }
            code = symbol.code();
            epic = symbol.epic(properties);
        }
        if (trades.hasOpenPosition(variant, code)) {
            out.put("ok", false);
            out.put("error", variant.name() + " already has an OPEN position for " + code
                    + " — close it first, the gate will not stack.");
            return out;
        }
        BrokerClient market = okxVariant ? books.forBook(Books.OKX) : books.marketData();
        double mid;
        try {
            market.login();
            mid = market.marketPrice(epic).mid();
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "market price unavailable for " + epic + ": " + e.getClass().getSimpleName());
            return out;
        }
        if (mid <= 0) {
            out.put("ok", false);
            out.put("error", "market price for " + epic + " is zero");
            return out;
        }
        boolean buy = direction == Direction.BUY;
        double stopDist = mid * 0.004;
        double stop = buy ? mid - stopDist : mid + stopDist;
        double target = buy ? mid + 2 * stopDist : mid - 2 * stopDist;
        Instant now = clock.instant();
        HtsScan signal = new HtsScan(variant, now, code, epic, direction, mid, stop, target, buy);

        log.info("HTS TEST entry: {} {} {} @ {} stop {} target {}", variant.name(), code,
                direction, mid, stop, target);
        execution.executeSignal(signal);

        out.put("signal", Map.of(
                "variant", variant.name(), "symbol", code, "epic", epic,
                "direction", direction.name(), "entry", mid, "stop", stop, "target", target));

        HtsTradeEntity saved = null;
        for (HtsTradeEntity t : trades.recent(null, 20)) {
            if (variant.name().equals(t.getVariant())
                    && code.equalsIgnoreCase(t.getSymbol())
                    && t.getOpenedAt() != null
                    && t.getOpenedAt().isAfter(now.minusSeconds(180))) {
                saved = t;
                break;
            }
        }
        if (saved != null) {
            out.put("ok", true);
            out.put("saved", true);
            out.put("tradeId", saved.getId());
            out.put("dealId", saved.getDealId());
            out.put("dealReference", saved.getDealReference());
            out.put("status", saved.getStatus());
        } else {
            out.put("ok", false);
            out.put("saved", false);
            out.put("hint", "no hts_trades row was written — check the server logs for the "
                    + "confirm reason (dealStatus/reason), the 'execution SKIPPED' flag line, "
                    + "or 'not confirmed open'.");
        }
        return out;
    }

    private void scanVariant(HtsVariant v, List<HtsInstrument> universe, BrokerClient market,
                             Instant now, List<HtsScan> found) {
        Instant fromHtf = now.minus(v.htfLookback());
        Instant fromLtf = now.minus(v.ltfLookback());
        for (HtsInstrument symbol : universe) {
            String epic = symbol.epic();
            try {
                List<Candle> ltf = HtsCandles.fetch(market, epic, v.ltf(), fromLtf, now);
                List<Candle> htf = HtsCandles.fetch(market, epic, v.htf(), fromHtf, now);
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
