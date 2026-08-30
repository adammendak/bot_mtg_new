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
import com.adam.server.web.dto.HtsJournal;
import com.adam.server.web.dto.HtsScorecardRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        Map<String, List<Candle>> candleCache = new HashMap<>(); // epic|LTF within this cycle
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
            if (t.getTp1At() != null && t.getTp1Pnl() == null
                    && fillTp1Pnl(t, txByBook.computeIfAbsent(t.getBook(), this::recentTx))) {
                touched++;
            }
            if (manageRunner(t, market, candleCache)) {
                touched++;
            }
        }
        return touched;
    }

    /** Backfill the realised cash of the TP1 half from the transaction feed. */
    private boolean fillTp1Pnl(HtsTradeEntity t, List<BrokerTransaction> tx) {
        Double cash = matchTp1Pnl(t, tx);
        if (cash == null) {
            return false;
        }
        t.setTp1Pnl(cash);
        trades.save(t);
        log.info("HTS runner [{}] {} TP1 realised {} {}", t.getVariant(), t.getSymbol(), round(cash), t.getPnlCcy());
        return true;
    }

    // ---- runner management on the still-open position ----

    private boolean manageRunner(HtsTradeEntity t, BrokerClient market, Map<String, List<Candle>> candleCache) {
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

        // One candle fetch per epic+LTF per cycle — several open trades share it.
        String cacheKey = t.getEpic() + "|" + v.ltf().name();
        List<Candle> ltf = candleCache.get(cacheKey);
        if (ltf == null) {
            try {
                Instant now = Instant.now();
                ltf = HtsCandles.fetch(market, t.getEpic(), v.ltf(), now.minus(v.ltfLookback()), now);
            } catch (Exception e) {
                log.warn("HTS runner [{}] {}: candle fetch failed ({})", v, t.getSymbol(), e.getClass().getSimpleName());
                return false;
            }
            candleCache.put(cacheKey, ltf);
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
        if (t.getTp1At() != null && t.getTp1Pnl() == null) {
            t.setTp1Pnl(matchTp1Pnl(t, tx)); // last chance to attribute the partial
        }
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

    // ---- read side (E-3 / E-4) ----

    /** Persisted HTS trades, newest first; {@code status} null = all. */
    public List<HtsTradeEntity> recent(String status, int limit) {
        int capped = Math.min(Math.max(limit, 1), 500);
        if (status != null && !status.isBlank()) {
            return trades.findByStatusOrderByIdDesc(status.trim().toUpperCase()).stream().limit(capped).toList();
        }
        return trades.findAllByOrderByIdDesc(PageRequest.of(0, capped));
    }

    /**
     * Trade journal (E-8) — closed trades filtered by variant / symbol / exit-date
     * range, sliced into a per-day series, an R histogram and per-reason /
     * per-symbol groups.
     */
    public HtsJournal journal(String variant, String symbol, Instant from, Instant to) {
        List<HtsTradeEntity> rows = new ArrayList<>();
        for (HtsTradeEntity t : trades.findAllByOrderByIdAsc()) {
            if (!"CLOSED".equalsIgnoreCase(t.getStatus()) || t.getRMultiple() == null) {
                continue;
            }
            if (variant != null && !variant.isBlank() && !variant.equalsIgnoreCase(t.getVariant())) {
                continue;
            }
            if (symbol != null && !symbol.isBlank() && !symbol.equalsIgnoreCase(t.getSymbol())) {
                continue;
            }
            Instant when = t.getExitAt() != null ? t.getExitAt() : t.getOpenedAt();
            if (from != null && (when == null || when.isBefore(from))) {
                continue;
            }
            if (to != null && (when == null || when.isAfter(to))) {
                continue;
            }
            rows.add(t);
        }

        int wins = 0;
        double sumR = 0;
        Map<String, double[]> perDay = new LinkedHashMap<>();   // date -> [r, pnl, trades, pnlKnown]
        int[] hist = new int[6]; // <=-1, -1..0, 0..1, 1..2, 2..3, >3
        Map<String, long[]> perReason = new LinkedHashMap<>();  // key -> [trades, wins, sumR*1e6]
        Map<String, long[]> perSymbol = new LinkedHashMap<>();
        java.time.ZoneId zone = java.time.ZoneId.of(properties.getTimezone());

        for (HtsTradeEntity t : rows) {
            double r = t.getRMultiple();
            sumR += r;
            if (r > 0) {
                wins++;
            }
            hist[bucket(r)]++;
            Instant when = t.getExitAt() != null ? t.getExitAt() : t.getOpenedAt();
            String day = when.atZone(zone).toLocalDate().toString();
            double[] d = perDay.computeIfAbsent(day, k -> new double[4]);
            d[0] += r;
            if (t.getPnl() != null) {
                d[1] += t.getPnl();
                d[3] = 1;
            }
            d[2] += 1;
            accumulate(perReason, t.getCloseReason() == null ? "UNKNOWN" : t.getCloseReason(), r);
            accumulate(perSymbol, t.getSymbol(), r);
        }

        List<HtsJournal.Day> days = new ArrayList<>();
        for (Map.Entry<String, double[]> e : perDay.entrySet()) {
            double[] d = e.getValue();
            days.add(new HtsJournal.Day(e.getKey(), round(d[0]), d[3] == 1 ? round(d[1]) : null, (int) d[2]));
        }
        String[] labels = {"≤ −1R", "−1…0R", "0…1R", "1…2R", "2…3R", "> 3R"};
        List<HtsJournal.Bucket> histogram = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            histogram.add(new HtsJournal.Bucket(labels[i], hist[i]));
        }
        int n = rows.size();
        return new HtsJournal(n, wins, n == 0 ? 0 : round((double) wins / n), n == 0 ? 0 : round(sumR / n),
                round(sumR), days, histogram, groups(perReason), groups(perSymbol));
    }

    private static int bucket(double r) {
        if (r <= -1) {
            return 0;
        }
        if (r < 0) {
            return 1;
        }
        if (r < 1) {
            return 2;
        }
        if (r < 2) {
            return 3;
        }
        if (r < 3) {
            return 4;
        }
        return 5;
    }

    private static void accumulate(Map<String, long[]> m, String key, double r) {
        long[] v = m.computeIfAbsent(key, k -> new long[3]);
        v[0]++;
        if (r > 0) {
            v[1]++;
        }
        v[2] += Math.round(r * 1_000_000L);
    }

    private static List<HtsJournal.Group> groups(Map<String, long[]> m) {
        List<HtsJournal.Group> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : m.entrySet()) {
            long[] v = e.getValue();
            double sumR = v[2] / 1_000_000.0;
            out.add(new HtsJournal.Group(e.getKey(), (int) v[0], (int) v[1],
                    v[0] == 0 ? 0 : round((double) v[1] / v[0]),
                    v[0] == 0 ? 0 : round(sumR / v[0]), round(sumR)));
        }
        out.sort((a, b) -> Integer.compare(b.trades(), a.trades()));
        return out;
    }

    /**
     * Forward-test scorecard (E-4): one row per {@link HtsVariant}, aggregated
     * from {@code hts_trades}. Every variant is emitted even with no trades, so
     * the September board is complete.
     */
    public List<HtsScorecardRow> scorecard() {
        Map<String, List<HtsTradeEntity>> byVariant = new LinkedHashMap<>();
        for (HtsVariant v : HtsVariant.values()) {
            byVariant.put(v.name(), new ArrayList<>());
        }
        for (HtsTradeEntity t : trades.findAllByOrderByIdAsc()) {
            byVariant.computeIfAbsent(t.getVariant() == null ? "?" : t.getVariant(), k -> new ArrayList<>()).add(t);
        }
        List<HtsScorecardRow> out = new ArrayList<>();
        for (Map.Entry<String, List<HtsTradeEntity>> e : byVariant.entrySet()) {
            out.add(aggregate(e.getKey(), e.getValue()));
        }
        return out;
    }

    private HtsScorecardRow aggregate(String variant, List<HtsTradeEntity> rows) {
        String htf = null;
        String ltf = null;
        String book = null;
        try {
            HtsVariant v = HtsVariant.valueOf(variant);
            htf = v.htf().name();
            ltf = v.ltf().name();
            book = v.book();
        } catch (RuntimeException ignored) {
            // free-text variant — leave the timeframe columns null
        }
        int open = 0;
        int wins = 0;
        int losses = 0;
        double sumR = 0;
        Double realised = null;
        String ccy = null;
        Instant last = null;

        List<HtsTradeEntity> closed = new ArrayList<>();
        for (HtsTradeEntity t : rows) {
            if (t.getOpenedAt() != null && (last == null || t.getOpenedAt().isAfter(last))) {
                last = t.getOpenedAt();
            }
            if ("OPEN".equalsIgnoreCase(t.getStatus())) {
                open++;
            } else if ("CLOSED".equalsIgnoreCase(t.getStatus()) && t.getRMultiple() != null) {
                closed.add(t);
                double r = t.getRMultiple();
                sumR += r;
                if (r > 0) {
                    wins++;
                } else {
                    losses++;
                }
                if (t.getPnl() != null) {
                    realised = (realised == null ? 0.0 : realised) + t.getPnl();
                    if (ccy == null) {
                        ccy = t.getPnlCcy();
                    }
                }
            }
        }
        int n = closed.size();
        double avgR = n == 0 ? 0.0 : sumR / n;
        double winRate = n == 0 ? 0.0 : (double) wins / n;

        // Max drawdown of the cumulative-R curve, closed trades in exit order.
        closed.sort((a, b) -> {
            Instant ta = a.getExitAt() != null ? a.getExitAt() : a.getOpenedAt();
            Instant tb = b.getExitAt() != null ? b.getExitAt() : b.getOpenedAt();
            if (ta == null || tb == null) {
                return 0;
            }
            return ta.compareTo(tb);
        });
        double cum = 0;
        double peak = 0;
        double maxDd = 0;
        for (HtsTradeEntity t : closed) {
            cum += t.getRMultiple();
            peak = Math.max(peak, cum);
            maxDd = Math.max(maxDd, peak - cum);
        }

        return new HtsScorecardRow(variant, htf, ltf, book, open, n, wins, losses,
                round(winRate), round(avgR), round(sumR), round(avgR),
                round(maxDd), realised == null ? null : round(realised), ccy, last);
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

    private static boolean refHit(HtsTradeEntity t, BrokerTransaction b) {
        return "TRADE".equalsIgnoreCase(b.type()) && b.reference() != null
                && (b.reference().equals(t.getDealId()) || b.reference().equals(t.getDealReference()));
    }

    /**
     * The <b>latest</b> TRADE settlement for this deal — the final close. When the
     * runner was split at TP1 there are two settlements on the same reference; the
     * partial is the earlier one ({@link #matchTp1Pnl}), so the last one is the
     * runner's exit.
     */
    private static Double matchPnl(HtsTradeEntity t, List<BrokerTransaction> tx) {
        if (tx == null) {
            return null;
        }
        BrokerTransaction latest = null;
        for (BrokerTransaction b : tx) {
            if (!refHit(t, b)) {
                continue;
            }
            boolean afterOpen = t.getOpenedAt() == null || b.time() == null || !b.time().isBefore(t.getOpenedAt());
            if (!afterOpen) {
                continue;
            }
            if (latest == null || latest.time() == null
                    || (b.time() != null && b.time().isAfter(latest.time()))) {
                latest = b;
            }
        }
        return latest == null ? null : latest.amount();
    }

    /** The <b>earliest</b> TRADE settlement at/after TP1 — the partial-close cash. */
    private static Double matchTp1Pnl(HtsTradeEntity t, List<BrokerTransaction> tx) {
        if (tx == null || t.getTp1At() == null) {
            return null;
        }
        Instant floor = t.getTp1At().minusSeconds(120);
        BrokerTransaction earliest = null;
        for (BrokerTransaction b : tx) {
            if (!refHit(t, b) || b.time() == null || b.time().isBefore(floor)) {
                continue;
            }
            if (earliest == null || earliest.time() == null || b.time().isBefore(earliest.time())) {
                earliest = b;
            }
        }
        return earliest == null ? null : earliest.amount();
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
