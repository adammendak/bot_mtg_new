package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.Adx;
import com.adam.server.sdd.Band;
import com.adam.server.sdd.PivotPoints;
import com.adam.server.sdd.SddSymbol;
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
 * HTS ("wstęgi") strategy — per-trade backtest, timeframe-generic.
 * Models: H4/M15 (core), D1/H1, H1/M5.
 *
 * <p><b>Entry</b> (long; short mirrors): the fast RMA band (high/low, 33) is
 * fully clear above the slow band (144) on both the execution timeframe and the
 * HTF; price pulled back into the fast band within the last {@link #PULLBACK_BARS}
 * bars; the current candle <b>body</b> closes back above the fast band's upper
 * edge. Optional gates: skip when the bands are consolidating (T2), require ADX
 * to be trending (T3).
 *
 * <p><b>Stop</b>: structural — the far edge of the fast band at entry.
 * <b>Target</b>: {@code rr × stopDistance}, or (T4) the daily pivots R1/R2/R3 as
 * TP1/TP2/TP3 with the stop moved to break-even after TP1.
 * <b>Runner</b> ({@code runner=true}): half takes the fixed target, the other
 * half is held until a candle body closes beyond the slow band.
 *
 * <p>{@code r_multiple} is in stop-distance units (stop-out = −1.0).
 */
@Service
public class HtsBacktestService {

    private static final Logger log = LoggerFactory.getLogger(HtsBacktestService.class);
    private static final int FAST_LEN = 33;
    private static final int SLOW_LEN = 144;
    private static final int PULLBACK_BARS = 10;
    private static final int SLOPE_BARS = 20;              // slow band must be sloping over this many bars
    private static final double CONSOLIDATION_SEP = 0.25;  // required band separation as a fraction of slow-band width
    private static final int LOOK_AHEAD_BARS = 600;
    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final BrokerBooks books;
    private final AppProperties properties;

    public HtsBacktestService(BrokerBooks books, AppProperties properties) {
        this.books = books;
        this.properties = properties;
    }

    public record Params(
            Resolution htf,
            Resolution ltf,
            int days,
            int offsetDays,
            double rr,
            boolean runner,
            boolean adxFilter,
            double adxThreshold,
            boolean skipConsolidation,
            boolean pivotTargets,
            int maxNames
    ) {
        public static Params core(int days, int offsetDays, double rr) {
            return new Params(Resolution.H4, Resolution.M15, days, offsetDays, rr,
                    /*runner*/ false, /*adxFilter*/ false, /*adxThreshold*/ Adx.TREND_THRESHOLD,
                    /*skipConsolidation*/ false, /*pivotTargets*/ false, /*maxNames*/ 4);
        }
    }

    public List<SwingTradeRow> run(Params p) {
        BrokerClient market = books.marketData();
        if (!market.configured()) {
            return List.of();
        }
        try {
            market.login();
        } catch (Exception e) {
            log.warn("HTS backtest: market-data login failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
        long ltfSec = seconds(p.ltf());
        long htfSec = seconds(p.htf());
        long offsetSec = Math.max(0, p.offsetDays()) * 86400L;

        List<Cand> cands = new ArrayList<>();
        for (SddSymbol symbol : SddSymbol.universe()) {
            try {
                collect(cands, market, symbol.code(), symbol.epic(properties), p, htfSec, ltfSec, offsetSec);
            } catch (Exception e) {
                log.warn("HTS backtest failed for {}: {}", symbol.code(), e.getClass().getSimpleName());
            }
        }
        cands.sort(Comparator.comparing(c -> c.time));

        List<SwingTradeRow> out = new ArrayList<>();
        Map<String, Instant> openUntil = new HashMap<>();
        for (Cand c : cands) {
            if (p.maxNames() > 0) {
                openUntil.values().removeIf(x -> !x.isAfter(c.time));
                if (openUntil.containsKey(c.symbol) || openUntil.size() >= p.maxNames()) {
                    continue;
                }
            }
            Replayed r = p.pivotTargets() ? replayPivots(c) : replayRr(c, p.rr(), p.runner());
            out.add(new SwingTradeRow(iso(c.time), iso(r.exit), c.symbol, c.buy ? "LONG" : "SHORT",
                    r.result, Math.round(r.r * 10000.0) / 10000.0));
            if (p.maxNames() > 0) {
                openUntil.put(c.symbol, r.exit);
            }
        }
        return out;
    }

    private record Cand(Instant time, String symbol, boolean buy, double entry, double stop,
                        List<Candle> ltf, Band.Series slow, int idx, PivotPoints.Levels pivots) {
    }

    private record Replayed(Instant exit, String result, double r) {
    }

    private void collect(List<Cand> out, BrokerClient market, String code, String epic, Params p,
                         long htfSec, long ltfSec, long offsetSec) {
        Instant to = Instant.now().minusSeconds(ltfSec + offsetSec);
        Instant fromEval = to.minusSeconds((long) p.days() * 86400L);
        Instant fromLtf = fromEval.minusSeconds((long) (SLOW_LEN + PULLBACK_BARS + SLOPE_BARS + 5) * ltfSec);
        Instant fromHtf = to.minusSeconds((long) (SLOW_LEN + 5) * htfSec * 3L);

        List<Candle> ltf = chunked(market, epic, p.ltf(), fromLtf, to);
        List<Candle> h = chunked(market, epic, p.htf(), fromHtf, to);
        if (ltf.size() < SLOW_LEN + PULLBACK_BARS + SLOPE_BARS + 2 || h.size() < SLOW_LEN + 2) {
            return;
        }
        Band.Series lFast = Band.rma(ltf, FAST_LEN);
        Band.Series lSlow = Band.rma(ltf, SLOW_LEN);
        Band.Series hFast = Band.rma(h, FAST_LEN);
        Band.Series hSlow = Band.rma(h, SLOW_LEN);
        List<Adx.Point> adx = p.adxFilter() ? Adx.compute(ltf) : null;

        int hIdx = 0;
        int start = SLOW_LEN + PULLBACK_BARS + SLOPE_BARS;
        for (int i = start; i < ltf.size() - 1; i++) {
            Candle bar = ltf.get(i);
            if (bar.time().isBefore(fromEval)) {
                continue;
            }
            while (hIdx + 1 < h.size() && !h.get(hIdx + 1).time().plusSeconds(htfSec).isAfter(bar.time())) {
                hIdx++;
            }
            if (!lFast.ready(i) || !lSlow.ready(i) || !hFast.ready(hIdx) || !hSlow.ready(hIdx)) {
                continue;
            }
            boolean ltfUp = lFast.lower()[i] > lSlow.upper()[i];
            boolean ltfDn = lFast.upper()[i] < lSlow.lower()[i];
            boolean htfUp = hFast.lower()[hIdx] > hSlow.upper()[hIdx];
            boolean htfDn = hFast.upper()[hIdx] < hSlow.lower()[hIdx];

            Integer dir = null;
            if (ltfUp && htfUp && !consolidating(lFast, lSlow, i, true, p.skipConsolidation())
                    && pulledBack(ltf, lFast, i, true) && bar.close() > lFast.upper()[i]
                    && adxOk(adx, i, true, p)) {
                dir = 1;
            } else if (ltfDn && htfDn && !consolidating(lFast, lSlow, i, false, p.skipConsolidation())
                    && pulledBack(ltf, lFast, i, false) && bar.close() < lFast.lower()[i]
                    && adxOk(adx, i, false, p)) {
                dir = -1;
            }
            if (dir == null) {
                continue;
            }
            boolean buy = dir > 0;
            double entry = bar.close();
            double stop = buy ? lFast.lower()[i] : lFast.upper()[i];
            if (buy ? stop >= entry : stop <= entry) {
                continue;
            }
            PivotPoints.Levels piv = p.pivotTargets()
                    ? PivotPoints.previousCompleted(ltf.subList(0, i + 1), bar.time(), ZONE) : null;
            if (p.pivotTargets() && piv == null) {
                continue;
            }
            out.add(new Cand(bar.time(), code, buy, entry, stop, ltf, lSlow, i, piv));
        }
    }

    /** Bands too close together / slow band not sloping = consolidation → no entry. */
    private static boolean consolidating(Band.Series fast, Band.Series slow, int i, boolean buy, boolean enabled) {
        if (!enabled) {
            return false;
        }
        double slowWidth = slow.upper()[i] - slow.lower()[i];
        double sep = buy ? fast.lower()[i] - slow.upper()[i] : slow.lower()[i] - fast.upper()[i];
        if (slowWidth <= 0 || sep < CONSOLIDATION_SEP * slowWidth) {
            return true;
        }
        double mid = (slow.upper()[i] + slow.lower()[i]) / 2.0;
        double midPrev = (slow.upper()[i - SLOPE_BARS] + slow.lower()[i - SLOPE_BARS]) / 2.0;
        return buy ? mid <= midPrev : mid >= midPrev; // slow band must slope with the trade
    }

    private static boolean pulledBack(List<Candle> ltf, Band.Series fast, int i, boolean buy) {
        for (int k = Math.max(0, i - PULLBACK_BARS); k < i; k++) {
            if (!fast.ready(k)) {
                continue;
            }
            if (buy ? ltf.get(k).low() <= fast.upper()[k] : ltf.get(k).high() >= fast.lower()[k]) {
                return true;
            }
        }
        return false;
    }

    private static boolean adxOk(List<Adx.Point> adx, int i, boolean buy, Params p) {
        if (!p.adxFilter() || adx == null || i >= adx.size()) {
            return true;
        }
        Adx.Point a = adx.get(i);
        if (!a.trending(p.adxThreshold())) {
            return false;
        }
        return buy ? a.plusDi() >= a.minusDi() : a.minusDi() >= a.plusDi();
    }

    // ---- exit models ----

    private static Replayed replayRr(Cand c, double rr, boolean runner) {
        boolean buy = c.buy;
        double stopDist = Math.abs(c.entry - c.stop);
        double target = buy ? c.entry + rr * stopDist : c.entry - rr * stopDist;
        int end = Math.min(c.idx + LOOK_AHEAD_BARS, c.ltf.size());

        if (!runner) {
            for (int i = c.idx + 1; i < end; i++) {
                Candle b = c.ltf.get(i);
                if (buy ? b.low() <= c.stop : b.high() >= c.stop) {
                    return new Replayed(b.time(), "LOSS", -1.0);
                }
                if (buy ? b.high() >= target : b.low() <= target) {
                    return new Replayed(b.time(), "WIN", rr);
                }
                if (bodyBeyondSlow(c, b, i, buy)) {
                    return mtm(c, b, i, buy, stopDist);
                }
            }
            return mtm(c, c.ltf.get(end - 1), end - 1, buy, stopDist);
        }

        Double rA = null;
        Double rB = null;
        Instant exA = null;
        Instant exB = null;
        for (int i = c.idx + 1; i < end && (rA == null || rB == null); i++) {
            Candle b = c.ltf.get(i);
            if (buy ? b.low() <= c.stop : b.high() >= c.stop) {
                if (rA == null) { rA = -1.0; exA = b.time(); }
                if (rB == null) { rB = -1.0; exB = b.time(); }
                break;
            }
            if (rA == null && (buy ? b.high() >= target : b.low() <= target)) {
                rA = rr;
                exA = b.time();
            }
            if (rB == null && bodyBeyondSlow(c, b, i, buy)) {
                rB = (buy ? b.close() - c.entry : c.entry - b.close()) / stopDist;
                exB = b.time();
            }
        }
        Candle last = c.ltf.get(end - 1);
        if (rA == null) { rA = (buy ? last.close() - c.entry : c.entry - last.close()) / stopDist; exA = last.time(); }
        if (rB == null) { rB = (buy ? last.close() - c.entry : c.entry - last.close()) / stopDist; exB = last.time(); }
        double r = 0.5 * rA + 0.5 * rB;
        return new Replayed(exA.isAfter(exB) ? exA : exB, r > 1e-9 ? "WIN" : r < -1e-9 ? "LOSS" : "OPEN", r);
    }

    /** T4: thirds to R1/R2/R3 (S1/S2/S3), stop → break-even after TP1, slow-band runner on the last third. */
    private static Replayed replayPivots(Cand c) {
        boolean buy = c.buy;
        double stopDist = Math.abs(c.entry - c.stop);
        double[] tps = buy
                ? new double[]{c.pivots.r1(), c.pivots.r2(), c.pivots.r3()}
                : new double[]{c.pivots.s1(), c.pivots.s2(), c.pivots.s3()};
        boolean[] filled = new boolean[3];
        double stop = c.stop;
        double realised = 0;
        int end = Math.min(c.idx + LOOK_AHEAD_BARS, c.ltf.size());
        Instant exit = c.ltf.get(Math.min(end, c.ltf.size()) - 1).time();

        for (int i = c.idx + 1; i < end; i++) {
            Candle b = c.ltf.get(i);
            if (buy ? b.low() <= stop : b.high() >= stop) {
                double rStop = (stop - c.entry) / stopDist * (buy ? 1 : -1);
                for (int k = 0; k < 3; k++) {
                    if (!filled[k]) {
                        realised += rStop / 3.0;
                    }
                }
                exit = b.time();
                return new Replayed(exit, realised > 1e-9 ? "WIN" : realised < -1e-9 ? "LOSS" : "OPEN", realised);
            }
            for (int k = 0; k < 3; k++) {
                if (filled[k]) {
                    continue;
                }
                boolean hit = buy ? b.high() >= tps[k] : b.low() <= tps[k];
                if (hit) {
                    filled[k] = true;
                    realised += ((buy ? tps[k] - c.entry : c.entry - tps[k]) / stopDist) / 3.0;
                    exit = b.time();
                    if (k == 0 && (buy ? stop < c.entry : stop > c.entry)) {
                        stop = c.entry; // break-even after TP1
                    }
                }
            }
            if (filled[0] && filled[1] && filled[2]) {
                return new Replayed(exit, "WIN", realised);
            }
            // last unfilled third runs until the slow band body-close
            if (!filled[2] && bodyBeyondSlow(c, b, i, buy)) {
                double rRun = (buy ? b.close() - c.entry : c.entry - b.close()) / stopDist;
                for (int k = 0; k < 3; k++) {
                    if (!filled[k]) {
                        realised += rRun / 3.0;
                    }
                }
                return new Replayed(b.time(), realised > 1e-9 ? "WIN" : realised < -1e-9 ? "LOSS" : "OPEN", realised);
            }
        }
        Candle last = c.ltf.get(end - 1);
        double rMtm = (buy ? last.close() - c.entry : c.entry - last.close()) / stopDist;
        for (int k = 0; k < 3; k++) {
            if (!filled[k]) {
                realised += rMtm / 3.0;
            }
        }
        return new Replayed(last.time(), "OPEN", realised);
    }

    private static boolean bodyBeyondSlow(Cand c, Candle b, int i, boolean buy) {
        return c.slow.ready(i) && (buy ? b.close() < c.slow.lower()[i] : b.close() > c.slow.upper()[i]);
    }

    private static Replayed mtm(Cand c, Candle b, int i, boolean buy, double stopDist) {
        double r = (buy ? b.close() - c.entry : c.entry - b.close()) / stopDist;
        return new Replayed(b.time(), r > 1e-9 ? "WIN" : r < -1e-9 ? "LOSS" : "OPEN", r);
    }

    // ---- candles ----

    private static long seconds(Resolution res) {
        return switch (res) {
            case M5 -> 300L;
            case M15 -> 900L;
            case H1 -> 3600L;
            case H4 -> 4 * 3600L;
            case D1 -> 24 * 3600L;
        };
    }

    private static int dailyChunk(Resolution res) {
        return switch (res) {
            case M5 -> 3;
            case M15 -> 10;
            case H1 -> 30;
            case H4 -> 60;
            case D1 -> 365;
        };
    }

    private static List<Candle> chunked(BrokerClient market, String epic, Resolution res, Instant from, Instant to) {
        List<Candle> all = new ArrayList<>();
        long chunk = (long) dailyChunk(res) * 86400L;
        Instant chunkTo = to;
        int guard = 0;
        while (chunkTo.isAfter(from) && guard++ < 500) {
            Instant chunkFrom = chunkTo.minusSeconds(chunk);
            if (chunkFrom.isBefore(from)) {
                chunkFrom = from;
            }
            all.addAll(market.candles(epic, res, chunkFrom, chunkTo, 1000));
            chunkTo = chunkFrom;
        }
        all.sort(Comparator.comparing(Candle::time));
        List<Candle> out = new ArrayList<>();
        Instant prev = null;
        for (Candle cc : all) {
            if (prev == null || !cc.time().equals(prev)) {
                out.add(cc);
                prev = cc.time();
            }
        }
        return out;
    }

    private static String iso(Instant t) {
        return t == null ? "" : LocalDateTime.ofInstant(t, ZoneOffset.UTC).format(ISO_UTC);
    }
}
