package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.SddEngine;
import com.adam.server.sdd.SddScan;
import com.adam.server.sdd.SddSymbol;
import com.adam.server.web.dto.BacktestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

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
