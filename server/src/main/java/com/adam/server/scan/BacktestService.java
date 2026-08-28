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
        SddEngine engine = new SddEngine(ZoneId.of(properties.getTimezone()));
        List<BacktestResult> out = new ArrayList<>();
        for (SddSymbol symbol : SddSymbol.universe()) {
            try {
                out.add(backtestOne(market, engine, symbol, symbol.epic(properties), days));
            } catch (Exception e) {
                log.warn("Backtest failed for {}: {}", symbol.code(), e.getClass().getSimpleName());
            }
        }
        out.sort((a, b) -> Double.compare(b.profitFactor(), a.profitFactor()));
        return out;
    }

    private BacktestResult backtestOne(BrokerClient market, SddEngine engine,
                                       SddSymbol symbol, String epic, int days) {
        Instant now = Instant.now();
        Instant from = now.minusSeconds((days > 0 ? days : 90) * 86400L);
        List<Candle> m15 = market.candles(epic, Resolution.M15, from, now, 1000);
        List<Candle> h1 = market.candles(epic, Resolution.H1, from.minusSeconds(30 * 86400L), now, 500);
        List<Candle> h4 = market.candles(epic, Resolution.H4, from.minusSeconds(60 * 86400L), now, 300);

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
