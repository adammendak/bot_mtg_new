package com.adam.server.swing;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.web.dto.BacktestResult;
import com.adam.server.web.dto.SwingTradeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SDD-SWING (H1) backtest — replays H1 candles through {@link SddSwingEngine}
 * and scores each signal with the swing risk model (asymmetric): target =
 * entry ± 1× H4 ATR, stop = entry ∓ 2.5× H4 ATR, so R per signal is
 * <ul>
 *   <li>+1.0 — target reached first within the look-ahead window</li>
 *   <li>−2.5 — stop reached first</li>
 *   <li>0    — neither within {@link #LOOK_AHEAD_BARS} H1 bars</li>
 * </ul>
 * The H4 context is filtered to bars closed at or before each entry bar, so the
 * decisive directional filter carries no look-ahead bias. Aggregated per symbol.
 */
@Service
public class SwingBacktestService {

    private static final Logger log = LoggerFactory.getLogger(SwingBacktestService.class);
    /** ~4 trading days on H1 — swing trades run longer than M15. */
    private static final int LOOK_AHEAD_BARS = 96;
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 180;
    /** RMA_SLOW(133) H1 bars of warm-up ≈ 6 days, plus margin. */
    private static final int H1_WARMUP_DAYS = 12;
    private static final int H4_WARMUP_DAYS = 20;

    private final BrokerBooks books;
    private final SddSwingEngine engine;

    public SwingBacktestService(BrokerBooks books, SddSwingEngine engine) {
        this.books = books;
        this.engine = engine;
    }

    public List<BacktestResult> run(int days) {
        BrokerClient market = books.marketData();
        if (!market.configured()) {
            return List.of();
        }
        int window = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        try {
            market.login();
        } catch (Exception e) {
            log.warn("SWING backtest: market-data login failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
        List<BacktestResult> out = new ArrayList<>();
        for (SwingSymbol symbol : SwingSymbol.universe()) {
            try {
                out.add(backtestOne(market, symbol, symbol.epic(), window));
            } catch (Exception e) {
                log.warn("SWING backtest failed for {}: {}", symbol.code(), e.getClass().getSimpleName());
            }
        }
        out.sort(Comparator.comparingDouble(BacktestResult::profitFactor).reversed());
        return out;
    }

    /**
     * Per-trade replay for the portfolio equity simulator (tools/equity_simulator.py).
     * Same signal machinery as {@link #run} (H1 flip + RMA stack + PP + H4 filter), but
     * the exit model is parametrised so the 2.5R / 1.5R / 1:1 stop variants can be swept:
     * <pre>
     *   stop   = entry ∓ stopMult  × H4 ATR
     *   target = entry ± targetAtr × H4 ATR
     * </pre>
     * {@code rMultiple} is in units of the stop distance: a stop-out is {@code -1.0},
     * a target hit is {@code targetAtr/stopMult}, an unresolved trade at the end of the
     * {@code lookAhead} window is marked to market. Stop is checked before target on the
     * same bar (conservative).
     */
    public List<SwingTradeRow> runTrades(int days, double stopMult, double targetAtr, int lookAhead) {
        BrokerClient market = books.marketData();
        if (!market.configured()) {
            return List.of();
        }
        int window = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        double sm = stopMult <= 0 ? SddSwingEngine.STOP_ATR_MULT : stopMult;
        double ta = targetAtr <= 0 ? 1.0 : targetAtr;
        int la = lookAhead <= 0 ? LOOK_AHEAD_BARS : lookAhead;
        try {
            market.login();
        } catch (Exception e) {
            log.warn("SWING trade-backtest: market-data login failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
        List<SwingTradeRow> out = new ArrayList<>();
        for (SwingSymbol symbol : SwingSymbol.universe()) {
            try {
                collectTrades(out, market, symbol, symbol.epic(), window, sm, ta, la);
            } catch (Exception e) {
                log.warn("SWING trade-backtest failed for {}: {}", symbol.code(), e.getClass().getSimpleName());
            }
        }
        out.sort(Comparator.comparing(SwingTradeRow::entryTime));
        return out;
    }

    private void collectTrades(List<SwingTradeRow> out, BrokerClient market, SwingSymbol symbol, String epic,
                               int days, double stopMult, double targetAtr, int lookAhead) {
        Instant to = Instant.now().minusSeconds(3600L);
        Instant fromEval = to.minusSeconds(days * 86400L);
        Instant fromH1 = fromEval.minusSeconds(H1_WARMUP_DAYS * 86400L);
        Instant fromH4 = fromEval.minusSeconds((days + H4_WARMUP_DAYS) * 86400L);

        List<Candle> h1 = candlesChunked(market, epic, Resolution.H1, fromH1, to, 1000, 20);
        List<Candle> h4 = candlesChunked(market, epic, Resolution.H4, fromH4, to, 500, 60);

        for (int i = SddSwingEngine.RMA_SLOW + 2; i < h1.size(); i++) {
            Candle bar = h1.get(i);
            if (bar.time().isBefore(fromEval)) {
                continue;
            }
            List<Candle> h4Upto = h4ClosedBy(h4, bar.time());
            SwingScan scan = engine.evaluate(symbol, epic, h1.subList(0, i + 1), h4Upto, bar.time());
            if (scan == null) {
                continue;
            }
            // Back out the H4 ATR the engine used (stopLevel = entry ∓ 2.5×ATR).
            double atrH4 = Math.abs(scan.entry() - scan.stopLevel()) / SddSwingEngine.STOP_ATR_MULT;
            if (atrH4 <= 0 || Double.isNaN(atrH4)) {
                continue;
            }
            out.add(replayTrade(scan, h1, i, atrH4, stopMult, targetAtr, lookAhead));
        }
    }

    private static SwingTradeRow replayTrade(SwingScan scan, List<Candle> h1, int entryIdx,
                                             double atrH4, double stopMult, double targetAtr, int lookAhead) {
        boolean buy = scan.direction() == Direction.BUY;
        double entry = scan.entry();
        double stopDist = stopMult * atrH4;
        double targetDist = targetAtr * atrH4;
        double stop = buy ? entry - stopDist : entry + stopDist;
        double target = buy ? entry + targetDist : entry - targetDist;
        double winR = targetDist / stopDist; // = targetAtr / stopMult

        int end = Math.min(entryIdx + lookAhead, h1.size());
        for (int i = entryIdx + 1; i < end; i++) {
            Candle c = h1.get(i);
            boolean stopHit = buy ? c.low() <= stop : c.high() >= stop;
            boolean targetHit = buy ? c.high() >= target : c.low() <= target;
            if (stopHit) {
                return row(scan, c.time(), "LOSS", -1.0);
            }
            if (targetHit) {
                return row(scan, c.time(), "WIN", winR);
            }
        }
        // Unresolved after the look-ahead window: mark to market at its last bar.
        Candle lastBar = h1.get(Math.max(entryIdx + 1, end - 1));
        double mtm = (buy ? lastBar.close() - entry : entry - lastBar.close()) / stopDist;
        return row(scan, lastBar.time(), "OPEN", mtm);
    }

    private static SwingTradeRow row(SwingScan scan, Instant exit, String result, double r) {
        return new SwingTradeRow(
                iso(scan.timestamp()),
                iso(exit),
                scan.symbol(),
                scan.direction() == Direction.BUY ? "LONG" : "SHORT",
                result,
                Math.round(r * 10000.0) / 10000.0);
    }

    private static final java.time.format.DateTimeFormatter ISO_UTC =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static String iso(Instant t) {
        return t == null ? "" : java.time.LocalDateTime.ofInstant(t, java.time.ZoneOffset.UTC).format(ISO_UTC);
    }

    private BacktestResult backtestOne(BrokerClient market, SwingSymbol symbol, String epic, int days) {
        // Capital rejects ranges ending exactly at "now"; end one H1 bar back.
        Instant to = Instant.now().minusSeconds(3600L);
        Instant fromEval = to.minusSeconds(days * 86400L);
        Instant fromH1 = fromEval.minusSeconds(H1_WARMUP_DAYS * 86400L);
        Instant fromH4 = fromEval.minusSeconds((days + H4_WARMUP_DAYS) * 86400L);

        List<Candle> h1 = candlesChunked(market, epic, Resolution.H1, fromH1, to, 1000, 20);
        List<Candle> h4 = candlesChunked(market, epic, Resolution.H4, fromH4, to, 500, 60);

        int signals = 0;
        int wins = 0;
        int losses = 0;
        double sumR = 0;
        // Only evaluate bars inside the requested window (warm-up bars just feed the RMAs).
        for (int i = SddSwingEngine.RMA_SLOW + 2; i < h1.size(); i++) {
            Candle bar = h1.get(i);
            if (bar.time().isBefore(fromEval)) {
                continue;
            }
            List<Candle> h4Upto = h4ClosedBy(h4, bar.time());
            SwingScan scan = engine.evaluate(symbol, epic, h1.subList(0, i + 1), h4Upto, bar.time());
            if (scan == null) {
                continue;
            }
            signals++;
            double r = outcome(scan, h1, i);
            sumR += r;
            if (r > 0) {
                wins++;
            } else if (r < 0) {
                losses++;
            }
        }
        double winRate = signals == 0 ? 0 : (double) wins / signals;
        double avgR = signals == 0 ? 0 : sumR / signals;
        // Profit factor = gross win R / gross loss R (each win is +1.0, each loss −2.5).
        double grossWin = wins * 1.0;
        double grossLoss = losses * 2.5;
        double profitFactor = grossLoss == 0 ? (grossWin > 0 ? 999.0 : 0.0) : grossWin / grossLoss;
        return new BacktestResult(symbol.code(), epic, signals, wins, losses, winRate, avgR, avgR, profitFactor);
    }

    /** H4 bars whose close (open + 4h) is at or before {@code at}. */
    private static List<Candle> h4ClosedBy(List<Candle> h4, Instant at) {
        List<Candle> out = new ArrayList<>();
        for (Candle c : h4) {
            if (!c.time().plusSeconds(4 * 3600L).isAfter(at)) {
                out.add(c);
            }
        }
        return out;
    }

    /** +1.0 if the 1R target is hit before the 2.5R stop within the window, −2.5 if the stop, else 0. */
    private static double outcome(SwingScan scan, List<Candle> h1, int entryIdx) {
        double entry = scan.entry();
        boolean buy = scan.direction() == Direction.BUY;
        double target = scan.targetLevel();
        double stop = scan.stopLevel();
        int end = Math.min(entryIdx + LOOK_AHEAD_BARS, h1.size());
        for (int i = entryIdx + 1; i < end; i++) {
            Candle c = h1.get(i);
            if (buy) {
                if (c.low() <= stop) {
                    return -2.5;
                }
                if (c.high() >= target) {
                    return 1.0;
                }
            } else {
                if (c.high() >= stop) {
                    return -2.5;
                }
                if (c.low() <= target) {
                    return 1.0;
                }
            }
        }
        return 0.0;
    }

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
        all.sort(Comparator.comparing(Candle::time));
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
}
