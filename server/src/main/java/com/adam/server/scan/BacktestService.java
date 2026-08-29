package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.HeikenAshi;
import com.adam.server.sdd.SddEngine;
import com.adam.server.sdd.SddScan;
import com.adam.server.sdd.SddSymbol;
import com.adam.server.sdd.Supertrend;
import com.adam.server.sdd.WaveTrend;
import com.adam.server.sdd.Wilder;
import com.adam.server.web.dto.BacktestResult;
import com.adam.server.web.dto.SwingTradeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backtest: replays historical candles through {@link SddEngine} and scores
 * every signal as if a trade were opened at the bar close with a stop at 1R
 * and a target at 1R (Computron's hard-1R method). Outcome per signal:
 * <ul>
 *   <li>win  → the price reached entry + 1R before entry − 1R (BUY), R = +1</li>
 *   <li>loss → entry − 1R first, R = −1</li>
 *   <li>partial → neither hit within the look-ahead window, R = 0</li>
 * </ul>
 * Win rate / expectancy / profit factor are aggregated per symbol.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);
    private static final int LOOK_AHEAD_BARS = 32; // ~8h on M15
    private static final int DEFAULT_DAYS = 90;
    /**
     * Upper bound on the requested window. {@code candlesChunked} pages the range
     * in ~10-day slices per symbol, so an unbounded {@code days} (e.g. a caller
     * passing {@code ?days=3650}) would fan out into hundreds of sequential
     * broker HTTP calls on the request thread. 180 days keeps a full run under
     * ~90 broker calls and matches the longest window the dashboard offers.
     */
    private static final int MAX_DAYS = 180;

    private final BrokerBooks books;
    private final AppProperties properties;

    public BacktestService(BrokerBooks books, AppProperties properties) {
        this.books = books;
        this.properties = properties;
    }

    public List<BacktestResult> run(String book, int days) {
        BrokerClient market = books.marketData();
        if (!market.configured()) {
            return List.of();
        }
        int window = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        SddEngine engine = new SddEngine(ZoneId.of(properties.getTimezone()));
        List<BacktestResult> out = new ArrayList<>();
        for (SddSymbol symbol : SddSymbol.universe()) {
            try {
                out.add(backtestOne(market, engine, symbol, symbol.epic(properties), window));
            } catch (Exception e) {
                log.warn("Backtest failed for {}: {}", symbol.code(), e.getClass().getSimpleName());
            }
        }
        out.sort((a, b) -> Double.compare(b.profitFactor(), a.profitFactor()));
        return out;
    }

    // ------------------------------------------------------------------
    // Per-trade replay (for tools/equity_simulator.py) + HTF/LTF filter
    // ------------------------------------------------------------------

    private static final int RMA_FAST = SddEngine.RMA_FAST;
    /** M15 setup window for the HTF (H1) WaveTrend extreme — ~24h. */
    private static final int HTF_WINDOW_H1_BARS = 6;
    /** M15 Supertrend flip must be this recent (≈ the 4–6h setup window on M15). */
    private static final int LTF_WINDOW_M15_BARS = 20;
    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * SDD-M15 per-trade replay. {@code htfFilter=false}: the live full-stack
     * ({@link SddEngine}). {@code htfFilter=true}: the HTF mean-reversion model —
     * M15 HA flip + price reclaiming M15 RMA33 (pullback, not the trend stack) +
     * H1 WaveTrend leaving an extreme in the trade direction + a fresh M15
     * Supertrend flip. Exit: stop = {@code stopMult}× H1 ATR, target =
     * {@code targetAtr}× H1 ATR; {@code r_multiple} in stop-distance units
     * (stop-out = −1.0). {@code maxNames}>0 applies the live 4-name / no-pyramid
     * gate. {@code offsetDays} ends the window early (train/test split).
     */
    public List<SwingTradeRow> runTrades(int days, double stopMult, double targetAtr, int lookAhead,
                                         int maxNames, boolean htfFilter, int offsetDays) {
        BrokerClient market = books.marketData();
        if (!market.configured()) {
            return List.of();
        }
        int window = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        double sm = stopMult <= 0 ? SddEngine.STOP_ATR_MULT : stopMult;
        double ta = targetAtr <= 0 ? 1.0 : targetAtr;
        int la = lookAhead <= 0 ? LOOK_AHEAD_BARS : lookAhead;
        long offsetSecs = Math.max(0, offsetDays) * 86400L;
        SddEngine engine = new SddEngine(ZoneId.of(properties.getTimezone()));

        List<Cand> cands = new ArrayList<>();
        for (SddSymbol symbol : SddSymbol.universe()) {
            try {
                collect(cands, market, engine, symbol, symbol.epic(properties), window, offsetSecs, htfFilter);
            } catch (Exception e) {
                log.warn("M15 trade-backtest failed for {}: {}", symbol.code(), e.getClass().getSimpleName());
            }
        }
        cands.sort(Comparator.comparing(c -> c.time));

        List<SwingTradeRow> out = new ArrayList<>();
        Map<String, Instant> openUntil = new HashMap<>();
        for (Cand c : cands) {
            if (maxNames > 0) {
                openUntil.values().removeIf(exit -> !exit.isAfter(c.time));
                if (openUntil.containsKey(c.symbol) || openUntil.size() >= maxNames) {
                    continue;
                }
            }
            double stopDist = sm * c.oneR;
            double targetDist = ta * c.oneR;
            double winR = targetDist / stopDist;
            boolean buy = c.dir == Direction.BUY;
            double stop = buy ? c.entry - stopDist : c.entry + stopDist;
            double target = buy ? c.entry + targetDist : c.entry - targetDist;

            int endIdx = Math.min(c.idx + la, c.m15.size());
            String result = "OPEN";
            double r = 0;
            Instant exit = c.m15.get(Math.max(c.idx + 1, endIdx - 1)).time();
            for (int i = c.idx + 1; i < endIdx; i++) {
                Candle bar = c.m15.get(i);
                boolean stopHit = buy ? bar.low() <= stop : bar.high() >= stop;
                boolean targetHit = buy ? bar.high() >= target : bar.low() <= target;
                if (stopHit) {
                    result = "LOSS";
                    r = -1.0;
                    exit = bar.time();
                    break;
                }
                if (targetHit) {
                    result = "WIN";
                    r = winR;
                    exit = bar.time();
                    break;
                }
            }
            if ("OPEN".equals(result)) {
                Candle lastBar = c.m15.get(Math.max(c.idx + 1, endIdx - 1));
                r = (buy ? lastBar.close() - c.entry : c.entry - lastBar.close()) / stopDist;
            }
            out.add(new SwingTradeRow(iso(c.time), iso(exit), c.symbol,
                    buy ? "LONG" : "SHORT", result, Math.round(r * 10000.0) / 10000.0));
            if (maxNames > 0) {
                openUntil.put(c.symbol, exit);
            }
        }
        return out;
    }

    private record Cand(Instant time, String symbol, String epic, Direction dir,
                        double entry, double oneR, List<Candle> m15, int idx) {
    }

    private void collect(List<Cand> out, BrokerClient market, SddEngine engine, SddSymbol symbol, String epic,
                         int days, long offsetSecs, boolean htfFilter) {
        Instant to = Instant.now().minusSeconds(15 * 60L + offsetSecs);
        Instant from = to.minusSeconds(days * 86400L);
        List<Candle> m15 = candlesChunked(market, epic, Resolution.M15, from, to, 1000, 10);
        List<Candle> h1 = candlesChunked(market, epic, Resolution.H1, from.minusSeconds(30 * 86400L), to, 500, 30);
        List<Candle> h4 = candlesChunked(market, epic, Resolution.H4, from.minusSeconds(60 * 86400L), to, 300, 60);

        for (int i = 60; i < m15.size(); i++) {
            Candle bar = m15.get(i);
            List<Candle> m15Upto = m15.subList(0, i + 1);
            List<Candle> h1Upto = closedByTime(h1, bar.time(), 3600L);

            if (htfFilter) {
                Cand c = mrCandidate(symbol, epic, m15, m15Upto, h1Upto, i, bar.time());
                if (c != null) {
                    out.add(c);
                }
                continue;
            }
            SddScan scan = engine.evaluate(symbol, epic, m15Upto, h1, h4, bar.time());
            if (!scan.fullStack()) {
                continue;
            }
            double oneR = scan.oneR() > 0 ? scan.oneR() : Math.abs(scan.entry() - scan.stop()) / SddEngine.STOP_ATR_MULT;
            if (oneR <= 0 || Double.isNaN(oneR)) {
                continue;
            }
            out.add(new Cand(bar.time(), symbol.code(), epic, scan.direction(), scan.entry(), oneR, m15, i));
        }
    }

    /** HTF mean-reversion entry on M15 (HTF = H1). */
    private Cand mrCandidate(SddSymbol symbol, String epic, List<Candle> m15, List<Candle> m15Upto,
                             List<Candle> h1Upto, int i, Instant now) {
        List<HeikenAshi.Bar> ha = HeikenAshi.from(m15Upto);
        int m = ha.size();
        if (m < 3) {
            return null;
        }
        if (ha.get(m - 1).bullish() == ha.get(m - 2).bullish()) {
            return null; // no HA flip
        }
        boolean buy = ha.get(m - 1).bullish();

        double[] closes = Wilder.closes(m15Upto);
        double rma33 = Wilder.last(Wilder.rma(closes, RMA_FAST));
        if (Double.isNaN(rma33)) {
            return null;
        }
        double close = m15.get(i).close();
        if (buy ? close <= rma33 : close >= rma33) {
            return null; // not reclaimed
        }
        boolean pulledThrough = false;
        for (int k = Math.max(0, i - 5); k < i; k++) {
            double c = m15.get(k).close();
            if (buy ? c < rma33 : c > rma33) {
                pulledThrough = true;
                break;
            }
        }
        if (!pulledThrough) {
            return null;
        }
        if (!htfExtremeResuming(h1Upto, buy) || !ltfSupertrendTrigger(m15Upto, buy)) {
            return null;
        }
        double oneR = Wilder.last(Wilder.atr(h1Upto, SddEngine.ATR_PERIOD));
        if (Double.isNaN(oneR) || oneR <= 0) {
            return null;
        }
        return new Cand(now, symbol.code(), epic, buy ? Direction.BUY : Direction.SELL, close, oneR, m15, i);
    }

    private static boolean htfExtremeResuming(List<Candle> h1Upto, boolean buy) {
        List<WaveTrend.Point> wt = WaveTrend.compute(h1Upto);
        int n = wt.size();
        if (n < WaveTrend.N2 + HTF_WINDOW_H1_BARS + 1) {
            return false;
        }
        double now = wt.get(n - 1).wt1();
        if (Double.isNaN(now)) {
            return false;
        }
        for (int k = n - 1 - HTF_WINDOW_H1_BARS; k < n - 1; k++) {
            double v = wt.get(k).wt1();
            if (Double.isNaN(v)) {
                continue;
            }
            if (buy && v <= WaveTrend.OVERSOLD_EXTREME && now > v) {
                return true;
            }
            if (!buy && v >= WaveTrend.OVERBOUGHT_EXTREME && now < v) {
                return true;
            }
        }
        return false;
    }

    private static boolean ltfSupertrendTrigger(List<Candle> m15Upto, boolean buy) {
        List<Supertrend.Point> st = Supertrend.compute(m15Upto);
        int n = st.size();
        if (n < Supertrend.ATR_PERIOD + LTF_WINDOW_M15_BARS + 1) {
            return false;
        }
        if (st.get(n - 1).trend() != (buy ? 1 : -1)) {
            return false;
        }
        for (int k = n - 1; k >= n - LTF_WINDOW_M15_BARS; k--) {
            Supertrend.Point p = st.get(k);
            if (buy && p.flipDown()) {
                return false;
            }
            if (!buy && p.flipUp()) {
                return false;
            }
            if (buy && p.flipUp()) {
                return true;
            }
            if (!buy && p.flipDown()) {
                return true;
            }
        }
        return false;
    }

    private static List<Candle> closedByTime(List<Candle> candles, Instant at, long barSecs) {
        List<Candle> out = new ArrayList<>();
        for (Candle c : candles) {
            if (!c.time().plusSeconds(barSecs).isAfter(at)) {
                out.add(c);
            }
        }
        return out;
    }

    private static String iso(Instant t) {
        return t == null ? "" : LocalDateTime.ofInstant(t, ZoneOffset.UTC).format(ISO_UTC);
    }

    private BacktestResult backtestOne(BrokerClient market, SddEngine engine,
                                       SddSymbol symbol, String epic, int days) {
        // End at the last closed M15 candle (Capital rejects ranges ending at "now").
        Instant to = Instant.now().minusSeconds(15 * 60L);
        Instant from = to.minusSeconds((days > 0 ? days : 90) * 86400L);
        // Capital caps the daterange per resolution (M15 ~10d, H1 ~30-60d, H4 wider),
        // so page long windows in chunks and merge the candles.
        List<Candle> m15 = candlesChunked(market, epic, Resolution.M15, from, to, 1000, 10);
        List<Candle> h1 = candlesChunked(market, epic, Resolution.H1, from.minusSeconds(30 * 86400L), to, 500, 30);
        List<Candle> h4 = candlesChunked(market, epic, Resolution.H4, from.minusSeconds(60 * 86400L), to, 300, 60);

        int signals = 0;
        int wins = 0;
        int losses = 0;
        double sumR = 0;
        // Replay: step through m15, at each closed bar evaluate with a shifted "now".
        for (int i = 60; i < m15.size(); i++) {
            Instant barTime = m15.get(i).time();
            SddScan scan = engine.evaluate(symbol, epic,
                    m15.subList(0, i + 1), h1, h4, barTime);
            if (!scan.fullStack() && !scan.flip()) {
                continue;
            }
            signals++;
            double r = outcome(scan, m15, i);
            sumR += r;
            if (r > 0) {
                wins++;
            } else if (r < 0) {
                losses++;
            }
        }
        double winRate = signals == 0 ? 0 : (double) wins / signals;
        double avgR = signals == 0 ? 0 : sumR / signals;
        double profitFactor = losses == 0 ? (wins > 0 ? 999.0 : 0.0) : (double) wins / losses;
        double expectancy = avgR; // per-trade R expectation
        return new BacktestResult(symbol.code(), epic, signals, wins, losses, winRate, avgR, expectancy, profitFactor);
    }

    /** Fetches candles over a long window, paging in ≤{@code maxDaysPerChunk} slices (Capital daterange cap). */
    private static List<Candle> candlesChunked(BrokerClient market, String epic, Resolution res,
                                               Instant from, Instant to, int max, int maxDaysPerChunk) {
        List<Candle> all = new ArrayList<>();
        long chunkSecs = maxDaysPerChunk * 86400L;
        Instant chunkTo = to;
        while (chunkTo.isAfter(from)) {
            Instant chunkFrom = chunkTo.minusSeconds(chunkSecs);
            if (chunkFrom.isBefore(from)) {
                chunkFrom = from;
            }
            all.addAll(market.candles(epic, res, chunkFrom, chunkTo, max));
            chunkTo = chunkFrom;
        }
        all.sort(java.util.Comparator.comparing(Candle::time));
        List<Candle> dedup = new ArrayList<>();
        Instant prev = null;
        for (Candle c : all) {
            if (prev != null && c.time().equals(prev)) {
                continue;
            }
            dedup.add(c);
            prev = c.time();
        }
        return dedup;
    }

    /** +1 if price reaches entry+1R before entry−1R within look-ahead, −1 otherwise, 0 on no fill. */
    private static double outcome(SddScan scan, List<Candle> m15, int entryIdx) {
        double entry = scan.entry();
        double oneR = scan.oneR() <= 0 ? 1 : scan.oneR();
        boolean buy = scan.direction() == Direction.BUY;
        double target = buy ? entry + oneR : entry - oneR;
        double stop = buy ? entry - oneR : entry + oneR;
        int end = Math.min(entryIdx + LOOK_AHEAD_BARS, m15.size());
        for (int i = entryIdx + 1; i < end; i++) {
            Candle c = m15.get(i);
            if (buy) {
                if (c.high() >= target) {
                    return 1.0;
                }
                if (c.low() <= stop) {
                    return -1.0;
                }
            } else {
                if (c.low() <= target) {
                    return 1.0;
                }
                if (c.high() >= stop) {
                    return -1.0;
                }
            }
        }
        return 0.0;
    }
}
