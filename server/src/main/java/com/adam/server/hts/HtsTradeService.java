package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.model.BrokerTransaction;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.persistence.HtsTradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTS trade lifecycle (E-1). One position per signal, actively managed:
 * <ol>
 *   <li>{@link #recordOpen} persists the fill to {@code hts_trades} (status OPEN).</li>
 *   <li>{@link #manage} runs each cycle: at TP1 it closes half the position and
 *       locks the runner's stop at +{@value HtsEngine#RUNNER_LOCK_R}R; after that
 *       it trails the remaining stop under the fast band and flattens the runner
 *       on a body close beyond the slow band.</li>
 *   <li>When the broker no longer reports the deal open the trade is marked
 *       CLOSED and the outcome (exit, r_multiple, pnl, reason) is filled from the
 *       transaction feed.</li>
 * </ol>
 * Every open and close fans out to the {@link HtsTradeSink}s (log now, Notion later).
 */
@Service
public class HtsTradeService {

    private static final Logger log = LoggerFactory.getLogger(HtsTradeService.class);
    private static final double REASON_TOLERANCE = 0.15;   // fraction of the leg for STOP / TARGET labelling
    private static final double TRAIL_MIN_STEP = 0.10;     // only amend the stop if it moved this fraction of the leg

    private final HtsTradeRepository trades;
    private final BrokerBooks books;
    private final HtsEngine engine;
    private final AppProperties properties;
    private final List<HtsTradeSink> sinks;

    public HtsTradeService(HtsTradeRepository trades, BrokerBooks books, HtsEngine engine,
                           AppProperties properties, List<HtsTradeSink> sinks) {
        this.trades = trades;
        this.books = books;
        this.engine = engine;
        this.properties = properties;
        this.sinks = sinks;
    }

    /** True when this exact signal bar was already executed for this variant. */
    public boolean alreadyExecuted(HtsScan s) {
        return s.variant() != null && s.timestamp() != null
                && trades.existsByVariantAndSymbolAndDirectionAndBarTime(
                s.variant().name(), s.symbol(), s.direction().name(), s.timestamp());
    }

    @Transactional
    public HtsTradeEntity recordOpen(HtsScan s, HtsVariant v, String book, String accountName,
                                     double size, OrderAck ack) {
        HtsTradeEntity t = new HtsTradeEntity();
        t.setVariant(v.name());
        t.setHtf(v.htf().name());
        t.setLtf(v.ltf().name());
        t.setBook(book);
        t.setAccountName(accountName);
        t.setSymbol(s.symbol());
        t.setEpic(s.epic());
        t.setDirection(s.direction() == null ? null : s.direction().name());
        t.setEntry(s.entry());
        t.setStopLevel(s.stopLevel());
        t.setTargetLevel(s.targetLevel());
        t.setSize(size);
        t.setRunnerStop(s.stopLevel());
        t.setDealId(ack == null ? null : ack.dealId());
        t.setDealReference(ack == null ? null : ack.dealReference());
        t.setBarTime(s.timestamp());
        t.setOpenedAt(Instant.now());
        t.setStatus("OPEN");
        HtsTradeEntity saved = trades.save(t);
        fan(sink -> sink.onOpen(saved));
        return saved;
    }

    /** Reconcile + runner-manage every OPEN trade. Returns how many were touched. */
    @Transactional
    public int manage() {
        List<HtsTradeEntity> open = trades.findByStatusOrderByIdDesc("OPEN");
        if (open.isEmpty()) {
            return 0;
        }
        BrokerClient market = books.marketData();
        Map<String, Set<String>> liveDealsByBook = new HashMap<>();
        Map<String, List<BrokerTransaction>> txByBook = new HashMap<>();
        int touched = 0;
        for (HtsTradeEntity t : open) {
            Set<String> liveDeals = liveDealsByBook.computeIfAbsent(t.getBook(), this::openDealIds);
            if (liveDeals == null) {
                continue; // broker unreachable — retry next cycle
            }
            if (t.getDealId() == null || !liveDeals.contains(t.getDealId())) {
                applyClose(t, txByBook.computeIfAbsent(t.getBook(), this::recentTx));
                trades.save(t);
                fan(sink -> sink.onClose(t));
                touched++;
                continue;
            }
            if (manageRunner(t, market)) {
                touched++;
            }
        }
        return touched;
    }

    // ---- runner management on the still-open position ----

    private boolean manageRunner(HtsTradeEntity t, BrokerClient market) {
        HtsVariant v;
        try {
            v = HtsVariant.valueOf(t.getVariant());
        } catch (RuntimeException e) {
            return false;
        }
        if (t.getEntry() == null || t.getStopLevel() == null || t.getSize() == null) {
            return false;
        }
        boolean buy = "BUY".equalsIgnoreCase(t.getDirection());
        double entry = t.getEntry();
        double leg = Math.abs(entry - t.getStopLevel());
        if (leg <= 0) {
            return false;
        }

        List<Candle> ltf;
        try {
            Instant now = Instant.now();
            ltf = HtsCandles.fetch(market, t.getEpic(), v.ltf(), now.minus(v.ltfLookback()), now);
        } catch (Exception e) {
            log.warn("HTS runner [{}] {}: candle fetch failed ({})", v, t.getSymbol(), e.getClass().getSimpleName());
            return false;
        }
        HtsEngine.RunnerRead r = engine.runnerRead(ltf, buy);
        if (r == null) {
            return false;
        }
        BrokerClient broker = books.forBook(t.getBook());

        // ---- before TP1: close half + lock the runner's stop ----
        if (t.getTp1At() == null) {
            double tp1 = buy ? entry + HtsEngine.RR * leg : entry - HtsEngine.RR * leg;
            boolean hit = buy ? r.lastClose() >= tp1 : r.lastClose() <= tp1;
            if (!hit) {
                return false;
            }
            double lock = buy ? entry + HtsEngine.RUNNER_LOCK_R * leg : entry - HtsEngine.RUNNER_LOCK_R * leg;
            double half = Math.round(t.getSize() / 2.0 * 100.0) / 100.0;
            double min = properties.getMinDealSize();
            boolean splittable = half >= min && (t.getSize() - half) >= min;
            try {
                if (splittable) {
                    broker.closePosition(t.getDealId(), half);
                    t.setRemainingSize(t.getSize() - half);
                    log.info("HTS runner [{}] {} TP1 — closed half {} @ ~{}, stop → {}",
                            v, t.getSymbol(), half, r.lastClose(), round(lock));
                } else {
                    t.setRemainingSize(t.getSize());
                    log.info("HTS runner [{}] {} TP1 — size {} too small to split, whole position runs; stop → {}",
                            v, t.getSymbol(), t.getSize(), round(lock));
                }
                broker.amendPosition(t.getDealId(), lock, false);
                t.setTp1At(Instant.now());
                t.setRunnerStop(lock);
                trades.save(t);
                return true;
            } catch (Exception e) {
                log.warn("HTS runner [{}] {}: TP1 close/amend failed ({})", v, t.getSymbol(), e.getClass().getSimpleName());
                return false;
            }
        }

        // ---- after TP1: slow-band exit, else trail the fast band ----
        double remaining = t.getRemainingSize() != null ? t.getRemainingSize() : t.getSize();
        if (r.bodyBeyondSlow()) {
            try {
                broker.closePosition(t.getDealId(), remaining);
                log.info("HTS runner [{}] {} body closed beyond slow band — flattening runner", v, t.getSymbol());
                return true; // reconcile marks CLOSED next cycle
            } catch (Exception e) {
                log.warn("HTS runner [{}] {}: slow-band close failed ({})", v, t.getSymbol(), e.getClass().getSimpleName());
                return false;
            }
        }
        double cur = t.getRunnerStop() != null ? t.getRunnerStop() : t.getStopLevel();
        double next = buy ? Math.max(cur, r.fastFarEdge()) : Math.min(cur, r.fastFarEdge());
        boolean moved = (buy ? next > cur : next < cur) && Math.abs(next - cur) > TRAIL_MIN_STEP * leg;
        if (!moved) {
            return false;
        }
        try {
            broker.amendPosition(t.getDealId(), next, false);
            t.setRunnerStop(next);
            trades.save(t);
            log.info("HTS runner [{}] {} trail — stop → {}", v, t.getSymbol(), round(next));
            return true;
        } catch (Exception e) {
            log.warn("HTS runner [{}] {}: trail amend failed ({})", v, t.getSymbol(), e.getClass().getSimpleName());
            return false;
        }
    }

    // ---- close-out bookkeeping ----

    private void applyClose(HtsTradeEntity t, List<BrokerTransaction> tx) {
        t.setStatus("CLOSED");
        t.setExitAt(Instant.now());
        Double settle = matchPnl(t, tx);
        double leg = t.getEntry() != null && t.getStopLevel() != null
                ? Math.abs(t.getEntry() - t.getStopLevel()) : 0.0;
        double tp1 = t.getTp1Pnl() != null ? t.getTp1Pnl() : 0.0;
        Double pnl = settle == null ? (t.getTp1Pnl() != null ? tp1 : null) : settle + tp1;
        t.setPnl(pnl);
        if (settle != null && t.getSize() != null && t.getSize() > 0 && leg > 0) {
            boolean buy = "BUY".equalsIgnoreCase(t.getDirection());
            double closedSize = t.getRemainingSize() != null ? t.getRemainingSize() : t.getSize();
            double move = closedSize > 0 ? settle / closedSize : 0.0;
            t.setExitPrice(buy ? t.getEntry() + move : t.getEntry() - move);
            double rr = move / leg;
            t.setRMultiple(round(rr));
            double targetR = t.getTargetLevel() != null
                    ? Math.abs(t.getTargetLevel() - t.getEntry()) / leg : Double.NaN;
            if (Math.abs(rr + 1.0) <= REASON_TOLERANCE) {
                t.setCloseReason(t.getTp1At() != null ? "TRAIL" : "STOP");
            } else if (!Double.isNaN(targetR) && Math.abs(rr - targetR) <= REASON_TOLERANCE) {
                t.setCloseReason("TARGET");
            } else if (t.getTp1At() != null) {
                t.setCloseReason("RUNNER");
            } else {
                t.setCloseReason("MANUAL");
            }
        } else {
            t.setCloseReason("UNKNOWN");
        }
    }

    /** Realised P/L for a book since {@code since} — for the live day-halt. */
    public double realisedPnlSince(String book, Instant since) {
        double sum = 0;
        for (HtsTradeEntity t : trades.findByBookAndStatusAndExitAtAfter(book, "CLOSED", since)) {
            if (t.getPnl() != null) {
                sum += t.getPnl();
            }
        }
        return sum;
    }

    public Instant startOfToday() {
        return Instant.now().atZone(ZoneId.of(properties.getTimezone())).toLocalDate()
                .atStartOfDay(ZoneId.of(properties.getTimezone())).toInstant();
    }

    // ---- broker helpers (null = "unknown this pass, retry") ----

    private Set<String> openDealIds(String book) {
        try {
            BrokerClient c = books.forBook(book);
            if (c == null || !c.configured()) {
                return null;
            }
            if (!c.isSessionOpen()) {
                c.login();
            }
            Set<String> ids = new HashSet<>();
            for (Position p : c.openPositions()) {
                if (p.dealId() != null) {
                    ids.add(p.dealId());
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("HTS reconcile: open positions unavailable for {} ({})", book, e.getClass().getSimpleName());
            return null;
        }
    }

    private List<BrokerTransaction> recentTx(String book) {
        try {
            BrokerClient c = books.forBook(book);
            if (c == null || !c.configured()) {
                return List.of();
            }
            Instant now = Instant.now();
            return c.transactionHistory(now.minus(Duration.ofDays(3)), now, Duration.ofSeconds(12));
        } catch (Exception e) {
            log.warn("HTS reconcile: tx history unavailable for {} ({})", book, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static Double matchPnl(HtsTradeEntity t, List<BrokerTransaction> tx) {
        if (tx == null) {
            return null;
        }
        for (BrokerTransaction b : tx) {
            if (!"TRADE".equalsIgnoreCase(b.type())) {
                continue;
            }
            boolean refHit = b.reference() != null
                    && (b.reference().equals(t.getDealId()) || b.reference().equals(t.getDealReference()));
            boolean afterOpen = t.getOpenedAt() == null || b.time() == null || !b.time().isBefore(t.getOpenedAt());
            if (refHit && afterOpen) {
                return b.amount();
            }
        }
        return null;
    }

    private void fan(java.util.function.Consumer<HtsTradeSink> call) {
        for (HtsTradeSink s : sinks) {
            try {
                call.accept(s);
            } catch (Exception e) {
                log.warn("HTS trade sink {} failed: {}", s.getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
