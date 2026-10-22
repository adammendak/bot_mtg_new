package com.adam.server.tv;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.hts.HtsCandles;
import com.adam.server.hts.HtsExecutionGate;
import com.adam.server.hts.HtsScan;
import com.adam.server.hts.HtsTradeService;
import com.adam.server.hts.HtsVariant;
import com.adam.server.ops.FeatureFlags;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.persistence.TvSignalEntity;
import com.adam.server.persistence.TvSignalRepository;
import com.adam.server.sdd.SddSymbol;
import com.adam.server.sdd.Wilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns recorded TradingView AlgoPro events into demo-account orders (forward test) and writes
 * what happened back onto the {@code tv_signals} row ({@code exec_status}, linked
 * {@code trade_id}, ATR / stop / target used) so the whole history can be analysed later.
 *
 * <ul>
 *   <li>{@code entry} — market order on the variant's isolated TV book with a broker-side
 *       safety stop of {@code stopAtrMult} x ATR on the alert timeframe; sized by the
 *       standard HTS 1%-risk sizer in {@link HtsExecutionGate}. An opposite open position on
 *       the same symbol is closed first (reversal).</li>
 *   <li>{@code tp} / {@code invalid} — closes the open position of that side at market
 *       ({@code ALGO_TP} / {@code ALGO_INVALID}).</li>
 * </ul>
 *
 * Runs on a single worker thread: TradingView expects an answer within seconds, and
 * serialising keeps a reversal's close strictly before its open. Gated by the
 * {@code tv.execution} runtime flag; the books themselves stay inert until their
 * credentials are set.
 */
@Service
public class TvExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TvExecutionService.class);

    private final HtsExecutionGate gate;
    private final HtsTradeService trades;
    private final BrokerBooks books;
    private final AppProperties properties;
    private final FeatureFlags flags;
    private final TvSignalRepository signals;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tv-exec");
        t.setDaemon(true);
        return t;
    });

    public TvExecutionService(HtsExecutionGate gate, HtsTradeService trades, BrokerBooks books,
                              AppProperties properties, FeatureFlags flags, TvSignalRepository signals) {
        this.gate = gate;
        this.trades = trades;
        this.books = books;
        this.properties = properties;
        this.flags = flags;
        this.signals = signals;
    }

    /** Hand the event to the worker; never blocks or throws into the webhook thread. */
    public void submit(TvSignalEntity e) {
        try {
            worker.execute(() -> {
                try {
                    handle(e);
                } catch (RuntimeException ex) {
                    log.warn("TV exec {} {} {} failed: {}", e.getStrat(), e.getEvent(), e.getSymbol(),
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    mark(e, "ERROR", ex.getClass().getSimpleName() + ": " + ex.getMessage(), null);
                }
            });
        } catch (RuntimeException ex) {
            log.warn("TV exec could not be queued: {}", ex.getClass().getSimpleName());
        }
    }

    static HtsVariant variantFor(String strat) {
        if (strat == null) {
            return null;
        }
        return switch (strat.toLowerCase(java.util.Locale.ROOT)) {
            case "algopro-h1" -> HtsVariant.TV_ALGO_H1;
            case "algopro-h4" -> HtsVariant.TV_ALGO_H4;
            default -> null;
        };
    }

    void handle(TvSignalEntity e) {
        HtsVariant v = variantFor(e.getStrat());
        if (v == null) {
            mark(e, "RECORDED_ONLY", "strat has no execution book", null);
            return;
        }
        if (!flags.enabled("tv.execution")) {
            log.info("TV exec [{}] {} {} {} — tv.execution flag is OFF, recorded only", v.name(), e.getEvent(),
                    e.getSide(), e.getSymbol());
            mark(e, "FLAG_OFF", null, null);
            return;
        }
        if (!v.tradesSymbol(e.getSymbol())) {
            log.info("TV exec [{}] {} {} — symbol not in the TV universe, recorded only", v.name(), e.getSide(),
                    e.getSymbol());
            mark(e, "NOT_IN_UNIVERSE", null, null);
            return;
        }
        Direction dir = "BUY".equals(e.getSide()) ? Direction.BUY : Direction.SELL;
        HtsTradeEntity open = trades.openPositionOf(v, e.getSymbol());
        switch (e.getEvent()) {
            case "entry" -> entry(v, e, dir, open);
            case "tp", "invalid" -> {
                if (open == null || !dir.name().equals(open.getDirection())) {
                    log.info("TV exec [{}] {} {} {} — no matching open position, nothing to close", v.name(),
                            e.getEvent(), e.getSide(), e.getSymbol());
                    mark(e, "NO_POSITION", null, null);
                    return;
                }
                if ("tp".equals(e.getEvent())) {
                    // several TP alerts can arrive for one position: each takes a slice, the rest stays open
                    HtsTradeService.AlertOutcome o = trades.partialTakeProfit(open,
                            properties.getTv().getTpCloseFraction());
                    mark(e, o.status(), o.detail(), open.getId());
                } else {
                    boolean ok = trades.closeForAlert(open, "ALGO_INVALID");
                    mark(e, ok ? "CLOSED" : "CLOSE_FAILED",
                            ok && open.getRMultiple() != null ? "R=" + open.getRMultiple() : null, open.getId());
                }
            }
            default -> mark(e, "RECORDED_ONLY", "unhandled event", null);
        }
    }

    private void entry(HtsVariant v, TvSignalEntity e, Direction dir, HtsTradeEntity open) {
        String note = null;
        if (open != null) {
            if (dir.name().equals(open.getDirection())) {
                log.info("TV exec [{}] {} {} — already open, skipping duplicate entry", v.name(), e.getSide(),
                        e.getSymbol());
                mark(e, "DUPLICATE_OPEN", "already open", open.getId());
                return;
            }
            if (!trades.closeForAlert(open, "ALGO_REVERSE")) {
                log.warn("TV exec [{}] {} — could not close the opposite position, entry skipped", v.name(),
                        e.getSymbol());
                mark(e, "REVERSAL_CLOSE_FAILED", "opposite position id " + open.getId(), open.getId());
                return;
            }
            note = "reversed trade " + open.getId() + (open.getRMultiple() != null ? " (R=" + open.getRMultiple() + ")" : "");
        }
        HtsScan scan = buildScan(v, e, dir);
        if (scan == null) {
            mark(e, "NO_LEVELS", "candles / ATR unavailable", null);
            return;
        }
        e.setStopUsed(scan.stopLevel());
        e.setTargetUsed(scan.targetLevel());
        double mult = properties.getTv().getStopAtrMult();
        e.setAtr(mult > 0 ? Math.abs(scan.entry() - scan.stopLevel()) / mult : null);
        log.info("TV exec [{}] ENTRY {} {} @ {} stop {} (ATR-based safety stop)", v.name(), e.getSide(),
                e.getSymbol(), scan.entry(), scan.stopLevel());
        gate.executeSignal(scan);
        HtsTradeEntity t = trades.openPositionOf(v, e.getSymbol());
        if (t != null && dir.name().equals(t.getDirection())) {
            mark(e, "OPENED", note, t.getId());
        } else {
            mark(e, "NOT_OPENED", (note == null ? "" : note + "; ") + "gate skipped or broker rejected — see logs", null);
        }
    }

    HtsScan buildScan(HtsVariant v, TvSignalEntity e, Direction dir) {
        SddSymbol sym;
        try {
            sym = SddSymbol.valueOf(e.getSymbol());
        } catch (RuntimeException ex) {
            log.warn("TV exec [{}] unknown symbol {}", v.name(), e.getSymbol());
            return null;
        }
        String epic = sym.epic(properties);
        BrokerClient market = books.marketData();
        Instant now = Instant.now();
        List<Candle> candles;
        try {
            candles = HtsCandles.fetch(market, epic, v.ltf(), now.minus(v.ltfLookback()), now);
        } catch (RuntimeException ex) {
            log.warn("TV exec [{}] {} candles unavailable ({}) — entry skipped", v.name(), e.getSymbol(),
                    ex.getClass().getSimpleName());
            return null;
        }
        // The alert's close comes from TradingView's feed, which differs from Capital's (BTC up to ~0.6%,
        // XAU ~0.2%). Size and stop must be relative to the price the order will actually fill at.
        double ref = e.getPrice();
        try {
            com.adam.server.broker.model.MarketPrice mp = market.marketPrice(epic);
            if (mp != null && mp.mid() > 0) {
                ref = mp.mid();
            }
        } catch (RuntimeException ex) {
            log.warn("TV exec [{}] {} Capital price unavailable ({}) — using the alert price", v.name(),
                    e.getSymbol(), ex.getClass().getSimpleName());
        }
        return scanFrom(v, e, dir, epic, candles, ref);
    }

    /** Pure: price levels from the alert price and the ATR of {@code candles}. */
    HtsScan scanFrom(HtsVariant v, TvSignalEntity e, Direction dir, String epic, List<Candle> candles,
                     double entryRef) {
        int period = Math.max(2, properties.getTv().getAtrPeriod());
        if (candles == null || candles.size() < period + 2) {
            log.warn("TV exec [{}] {} not enough candles for ATR{} ({}) — entry skipped", v.name(), e.getSymbol(),
                    period, candles == null ? 0 : candles.size());
            return null;
        }
        double atr = Wilder.last(Wilder.atr(candles, period));
        if (!(atr > 0) || Double.isNaN(atr)) {
            log.warn("TV exec [{}] {} ATR invalid ({}) — entry skipped", v.name(), e.getSymbol(), atr);
            return null;
        }
        double entry = entryRef;
        boolean buy = dir == Direction.BUY;
        double stop = buy ? entry - properties.getTv().getStopAtrMult() * atr
                : entry + properties.getTv().getStopAtrMult() * atr;
        double target = buy ? entry + properties.getTv().getTpAtrMult() * atr
                : entry - properties.getTv().getTpAtrMult() * atr;
        if (stop <= 0) {
            return null;
        }
        Instant ts;
        try {
            ts = e.getBarTime() == null ? Instant.now() : Instant.parse(e.getBarTime());
        } catch (RuntimeException ex) {
            ts = Instant.now();
        }
        return new HtsScan(v, ts, e.getSymbol(), epic, dir, entry, stop, target, buy);
    }

    /** Record the outcome on the signal row; never throws. */
    private void mark(TvSignalEntity e, String status, String detail, Long tradeId) {
        try {
            e.setExecStatus(status);
            e.setExecDetail(detail == null ? null : detail.substring(0, Math.min(255, detail.length())));
            e.setTradeId(tradeId);
            e.setExecutedAt(Instant.now());
            signals.save(e);
        } catch (RuntimeException ex) {
            log.warn("TV exec: could not record outcome {} for signal {}: {}", status, e.getId(),
                    ex.getClass().getSimpleName());
        }
    }
}
