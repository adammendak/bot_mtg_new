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
import com.adam.server.sdd.Supertrend;
import com.adam.server.sdd.WaveTrend;
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
 * edge. Optional gates: skip when the bands are consolidating (T2), and an ADX
 * gate that is either a hard trend filter (T3) or a colour-zone permit that
 * still allows early, pre-cross entries (T3', {@code adxPermit}).
 *
 * <p><b>Stop</b>: structural — the far edge of the fast band at entry, pushed
 * out by {@code stopBufferFrac} × fast-band-width so a wick to the edge doesn't
 * stop us exactly on the structure line.
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
    private static final double ADX_BLUE_FLOOR = 15.0;     // T3': below this ADX is the "blue" no-trend zone → no entry
    private static final double DI_OPPOSE_MARGIN = 5.0;    // T3': permit entry unless the opposing DI leads by more than this
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
            int maxNames,
            double stopBufferFrac,
            boolean adxPermit,
            double runnerLockR,
            int splitEntries,
            // T7 — pyramiding
            int pyramidMax,
            int pyramidGapBars,
            double pyramidMinBufferR,
            // T8 — indicator options
            boolean supertrendTrail,
            boolean waveTrendFilter
    ) {
        public static Params core(int days, int offsetDays, double rr) {
            return new Params(Resolution.H4, Resolution.M15, days, offsetDays, rr,
                    /*runner*/ false, /*adxFilter*/ false, /*adxThreshold*/ Adx.TREND_THRESHOLD,
                    /*skipConsolidation*/ false, /*pivotTargets*/ false, /*maxNames*/ 4,
                    /*stopBufferFrac*/ 0.25, /*adxPermit*/ false, /*runnerLockR*/ 1.0,
                    /*splitEntries*/ 1,
                    /*pyramidMax*/ 0, /*pyramidGapBars*/ 5, /*pyramidMinBufferR*/ 0.5,
                    /*supertrendTrail*/ false, /*waveTrendFilter*/ false);
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
            Replayed r;
            if (p.pivotTargets()) {
                r = replayPivots(c);
            } else if (p.pyramidMax() > 0) {
                r = replayPyramid(c, p.rr(), p.runnerLockR(), p.supertrendTrail(),
                        p.pyramidMax(), p.pyramidGapBars(), p.pyramidMinBufferR());
            } else if (p.splitEntries() > 1) {
                r = replaySplit(c, p.rr(), p.runnerLockR(), p.supertrendTrail(), p.splitEntries());
            } else {
                r = replayRr(c, p.rr(), p.runner(), p.runnerLockR(), p.supertrendTrail());
            }
            out.add(new SwingTradeRow(iso(c.time), iso(r.exit), c.symbol, c.buy ? "LONG" : "SHORT",
                    r.result, Math.round(r.r * 10000.0) / 10000.0));
            if (p.maxNames() > 0) {
                openUntil.put(c.symbol, r.exit);
            }
        }
        return out;
    }

    private record Cand(Instant time, String symbol, boolean buy, double entry, double stop,
                        List<Candle> ltf, Band.Series fast, Band.Series slow, int idx, PivotPoints.Levels pivots,
                        double[] stLine) {
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
        // T8: Supertrend line for the runner trail (aligned 1:1 with ltf), and
        // WaveTrend for the entry veto (don't join a move already stretched our way).
        double[] stLine = null;
        if (p.supertrendTrail()) {
            List<Supertrend.Point> st = Supertrend.compute(ltf);
            stLine = new double[ltf.size()];
            java.util.Arrays.fill(stLine, Double.NaN);
            for (int k = 0; k < st.size() && k < stLine.length; k++) {
                stLine[k] = st.get(k).line();
            }
        }
        List<WaveTrend.Point> wt = p.waveTrendFilter() ? WaveTrend.compute(ltf) : null;

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
                    && adxOk(adx, i, true, p) && waveTrendOk(wt, i, true)) {
                dir = 1;
            } else if (ltfDn && htfDn && !consolidating(lFast, lSlow, i, false, p.skipConsolidation())
                    && pulledBack(ltf, lFast, i, false) && bar.close() < lFast.lower()[i]
                    && adxOk(adx, i, false, p) && waveTrendOk(wt, i, false)) {
                dir = -1;
            }
            if (dir == null) {
                continue;
            }
            boolean buy = dir > 0;
            double entry = bar.close();
            // structural stop = far edge of the fast band, pushed slightly further
            // out by a fraction of the band's own width so a wick into the edge
            // doesn't stop us at the exact structure line.
            double bandW = Math.max(0, lFast.upper()[i] - lFast.lower()[i]);
            double buf = Math.max(0, p.stopBufferFrac()) * bandW;
            double stop = buy ? lFast.lower()[i] - buf : lFast.upper()[i] + buf;
            if (buy ? stop >= entry : stop <= entry) {
                continue;
            }
            PivotPoints.Levels piv = p.pivotTargets()
                    ? PivotPoints.previousCompleted(ltf.subList(0, i + 1), bar.time(), ZONE) : null;
            if (p.pivotTargets() && piv == null) {
                continue;
            }
            out.add(new Cand(bar.time(), code, buy, entry, stop, ltf, lFast, lSlow, i, piv, stLine));
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

    /**
     * ADX gate. Two modes:
     * <ul>
     *   <li><b>hard</b> ({@code adxPermit=false}, T3): ADX ≥ threshold <i>and</i>
     *       the aligned DI leads — only fully confirmed trends pass.</li>
     *   <li><b>permit</b> ({@code adxPermit=true}, T3'): ADX is a colour-zone
     *       permit, not a strength gate. Veto only the "blue" no-trend zone
     *       ({@code ADX < ADX_BLUE_FLOOR}) or a clearly opposing DI; early,
     *       pre-cross entries are allowed.</li>
     * </ul>
     */
    private static boolean adxOk(List<Adx.Point> adx, int i, boolean buy, Params p) {
        if (!p.adxFilter() || adx == null || i >= adx.size()) {
            return true;
        }
        Adx.Point a = adx.get(i);
        double aligned = buy ? a.plusDi() : a.minusDi();
        double against = buy ? a.minusDi() : a.plusDi();
        if (p.adxPermit()) {
            if (Double.isNaN(a.adx()) || a.adx() < ADX_BLUE_FLOOR) {
                return false;
            }
            return against <= aligned + DI_OPPOSE_MARGIN;
        }
        if (!a.trending(p.adxThreshold())) {
            return false;
        }
        return aligned >= against;
    }

    /**
     * T8 WaveTrend entry veto: don't join when momentum is already stretched the
     * way we'd be trading (long into overbought / short into oversold) — wait for
     * it to come back. Oversold on a long (or overbought on a short) is fine —
     * that's the pullback we want. Null series = filter off.
     */
    private static boolean waveTrendOk(List<WaveTrend.Point> wt, int i, boolean buy) {
        if (wt == null || i >= wt.size()) {
            return true;
        }
        double wt1 = wt.get(i).wt1();
        if (Double.isNaN(wt1)) {
            return true;
        }
        return buy ? wt1 < WaveTrend.OVERBOUGHT : wt1 > WaveTrend.OVERSOLD;
    }

    // ---- exit models ----

    /**
     * The level the runner's stop trails: the Supertrend line (T8
     * {@code supertrendTrail}) if available, otherwise the far edge of the fast
     * band. Both sit on the protective side of price in a trend.
     */
    private static double trailEdge(Cand c, int i, boolean buy, boolean stTrail) {
        if (stTrail && c.stLine != null && i < c.stLine.length && !Double.isNaN(c.stLine[i])) {
            return c.stLine[i];
        }
        if (c.fast.ready(i)) {
            return buy ? c.fast.lower()[i] : c.fast.upper()[i];
        }
        return buy ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }

    /**
     * Fixed-RR / runner exit.
     *
     * <p>{@code runner=false}: whole position to {@code rr × stopDist}.
     *
     * <p>{@code runner=true}: half takes the fixed {@code rr} target (the videos
     * use 1:2); the other half is the runner. When {@code lockR > 0} the runner's
     * stop, <b>once TP1 is filled</b>, ratchets up to {@code entry ± lockR×stopDist}
     * (a locked profit — „przycina do 1% zysku") and then trails the far edge of
     * the fast band, whichever is higher. The runner still exits for good when a
     * candle body closes beyond the slow band. {@code lockR ≤ 0} = no lock/trail,
     * runner just held to the slow-band body-close.
     */
    private static Replayed replayRr(Cand c, double rr, boolean runner, double lockR, boolean stTrail) {
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

        boolean lockTrail = lockR > 0;
        double lockLevel = buy ? c.entry + lockR * stopDist : c.entry - lockR * stopDist;
        double runnerStop = c.stop;
        Double rA = null;
        Double rB = null;
        Instant exA = null;
        Instant exB = null;
        for (int i = c.idx + 1; i < end && (rA == null || rB == null); i++) {
            Candle b = c.ltf.get(i);

            // half A — fixed RR target; a stop hit before it fills takes both halves
            if (rA == null) {
                if (buy ? b.low() <= c.stop : b.high() >= c.stop) {
                    rA = -1.0;
                    exA = b.time();
                    if (rB == null) { rB = -1.0; exB = b.time(); }
                    break;
                }
                if (buy ? b.high() >= target : b.low() <= target) {
                    rA = rr;
                    exA = b.time();
                }
            }

            // half B — runner
            if (rB == null) {
                boolean afterTp1 = lockTrail && rA != null && rA > 0;
                if (afterTp1) {
                    double edge = trailEdge(c, i, buy, stTrail);
                    runnerStop = buy
                            ? Math.max(runnerStop, Math.max(lockLevel, edge))
                            : Math.min(runnerStop, Math.min(lockLevel, edge));
                }
                double effStop = afterTp1 ? runnerStop : c.stop;
                if (buy ? b.low() <= effStop : b.high() >= effStop) {
                    rB = (buy ? effStop - c.entry : c.entry - effStop) / stopDist;
                    exB = b.time();
                } else if (bodyBeyondSlow(c, b, i, buy)) {
                    rB = (buy ? b.close() - c.entry : c.entry - b.close()) / stopDist;
                    exB = b.time();
                }
            }
        }
        Candle last = c.ltf.get(end - 1);
        if (rA == null) { rA = (buy ? last.close() - c.entry : c.entry - last.close()) / stopDist; exA = last.time(); }
        if (rB == null) { rB = (buy ? last.close() - c.entry : c.entry - last.close()) / stopDist; exB = last.time(); }
        double r = 0.5 * rA + 0.5 * rB;
        return new Replayed(exA.isAfter(exB) ? exA : exB, r > 1e-9 ? "WIN" : r < -1e-9 ? "LOSS" : "OPEN", r);
    }

    /**
     * T7 — pyramiding. The base unit runs the {@link #replayRr} runner-lock model
     * (TP1 at {@code rr} on half, then a stop locked at {@code lockR} that trails
     * {@link #trailEdge}). <b>After TP1</b>, on every later bar that re-prints the
     * entry setup (fresh pullback into the fast band + a body reclaim, still
     * trending, not consolidating), an extra unit is added — but only while there
     * is banked profit to fund it: the add is sized so its risk to the shared
     * trailing stop equals the current profit cushion, capped at 1R of the base
     * unit. All units share one trailing stop; a stop hit or a slow-band body
     * close flattens the whole stack.
     *
     * <p>{@code bankedR} = the R that would remain if everything were stopped at
     * the shared stop right now (TP1 proceeds + every open unit marked to that
     * stop). The stack's account risk therefore stays ≈ house money; nominal size
     * grows with the trend. {@code r_multiple} is still in base-1R units so the
     * numbers compare with the other exit models.
     */
    private static Replayed replayPyramid(Cand c, double rr, double lockR, boolean stTrail,
                                          int pyrMax, int gapBars, double minBufferR) {
        boolean buy = c.buy;
        double stopDist = Math.abs(c.entry - c.stop);
        double target = buy ? c.entry + rr * stopDist : c.entry - rr * stopDist;
        double lockLevel = buy ? c.entry + Math.max(0, lockR) * stopDist : c.entry - Math.max(0, lockR) * stopDist;
        int end = Math.min(c.idx + LOOK_AHEAD_BARS, c.ltf.size());

        boolean tp1 = false;
        double runnerStop = c.stop;
        List<double[]> units = new ArrayList<>();   // each: {entryPrice, sizeR}
        int lastAddBar = Integer.MIN_VALUE / 2;

        for (int i = c.idx + 1; i < end; i++) {
            Candle b = c.ltf.get(i);

            if (!tp1) {
                if (buy ? b.low() <= c.stop : b.high() >= c.stop) {
                    return new Replayed(b.time(), "LOSS", -1.0);   // stopped before TP1
                }
                if (buy ? b.high() >= target : b.low() <= target) {
                    tp1 = true;
                    runnerStop = buy ? Math.max(runnerStop, lockLevel) : Math.min(runnerStop, lockLevel);
                }
                continue;
            }

            // ---- after TP1 ----
            double edge = trailEdge(c, i, buy, stTrail);
            runnerStop = buy ? Math.max(runnerStop, Math.max(lockLevel, edge))
                             : Math.min(runnerStop, Math.min(lockLevel, edge));

            if (units.size() < pyrMax && i - lastAddBar >= Math.max(1, gapBars) && addSignal(c, i, buy)) {
                double cushion = bankedR(c, rr, units, runnerStop, stopDist);
                double addDist = Math.abs(b.close() - runnerStop);
                if (cushion >= minBufferR && addDist > 0) {
                    double riskR = Math.min(cushion, 1.0);          // R risked to the shared stop
                    double sizeR = riskR * stopDist / addDist;      // base-1R-equivalent size
                    units.add(new double[]{b.close(), sizeR});
                    lastAddBar = i;
                }
            }

            if (buy ? b.low() <= runnerStop : b.high() >= runnerStop) {
                return closeStack(c, rr, units, runnerStop, stopDist, buy, b.time());
            }
            if (bodyBeyondSlow(c, b, i, buy)) {
                return closeStack(c, rr, units, b.close(), stopDist, buy, b.time());
            }
        }
        Candle last = c.ltf.get(end - 1);
        if (!tp1) {
            return mtm(c, last, end - 1, buy, stopDist);           // never reached TP1
        }
        return closeStack(c, rr, units, last.close(), stopDist, buy, last.time());
    }

    /** Profit that survives a stop-out at {@code stopPrice} right now, in base-1R units. */
    private static double bankedR(Cand c, double rr, List<double[]> units, double stopPrice, double stopDist) {
        boolean buy = c.buy;
        double r = 0.5 * rr;                                        // TP1 already banked on half the base
        r += 0.5 * (buy ? stopPrice - c.entry : c.entry - stopPrice) / stopDist;   // base runner half
        for (double[] u : units) {
            r += u[1] * (buy ? stopPrice - u[0] : u[0] - stopPrice) / stopDist;
        }
        return r;
    }

    /** Flatten the whole stack at {@code exitPrice}; R in base-1R units. */
    private static Replayed closeStack(Cand c, double rr, List<double[]> units, double exitPrice,
                                       double stopDist, boolean buy, Instant at) {
        double r = 0.5 * rr;
        r += 0.5 * (buy ? exitPrice - c.entry : c.entry - exitPrice) / stopDist;
        for (double[] u : units) {
            r += u[1] * (buy ? exitPrice - u[0] : u[0] - exitPrice) / stopDist;
        }
        return new Replayed(at, r > 1e-9 ? "WIN" : r < -1e-9 ? "LOSS" : "OPEN", r);
    }

    /** A later bar that re-prints the entry setup: still trending, not consolidating, pullback + body reclaim. */
    private static boolean addSignal(Cand c, int i, boolean buy) {
        if (!c.fast.ready(i) || !c.slow.ready(i)) {
            return false;
        }
        boolean trend = buy ? c.fast.lower()[i] > c.slow.upper()[i]
                            : c.fast.upper()[i] < c.slow.lower()[i];
        if (!trend || consolidating(c.fast, c.slow, i, buy, true)) {
            return false;
        }
        double close = c.ltf.get(i).close();
        boolean reclaim = buy ? close > c.fast.upper()[i] : close < c.fast.lower()[i];
        return reclaim && pulledBack(c.ltf, c.fast, i, buy);
    }

    /**
     * T6 — split entry. Ladder {@code n} equal‑notional rungs from the signal
     * close down toward the structural stop (top half of the entry→stop range),
     * filled over the next {@link #PULLBACK_BARS} bars. The position that forms
     * has a <b>blended entry</b> and a size fraction = filledRungs / n. Risk is
     * still measured against the <b>original</b> entry→stop distance, so a
     * partially‑filled ladder that stops out loses <i>less</i> than 1R ("give the
     * market breathing room, capped loss"). Exit = the same runner‑lock model as
     * {@link #replayRr} (TP1 at {@code rr}, then locked profit + fast‑band trail,
     * final exit on a slow‑band body close).
     */
    private static Replayed replaySplit(Cand c, double rr, double lockR, boolean stTrail, int n) {
        boolean buy = c.buy;
        double origDist = Math.abs(c.entry - c.stop);
        double step = origDist / (2.0 * n);
        double[] rung = new double[n];
        boolean[] filled = new boolean[n];
        for (int k = 0; k < n; k++) {
            rung[k] = buy ? c.entry - k * step : c.entry + k * step;
        }
        filled[0] = true;
        int end = Math.min(c.idx + LOOK_AHEAD_BARS, c.ltf.size());
        int fillDeadline = Math.min(end, c.idx + 1 + PULLBACK_BARS);

        // fill rungs that trade through before the stop
        int fi = c.idx + 1;
        for (; fi < fillDeadline; fi++) {
            Candle b = c.ltf.get(fi);
            if (buy ? b.low() <= c.stop : b.high() >= c.stop) {
                break;
            }
            for (int k = 1; k < n; k++) {
                if (!filled[k] && (buy ? b.low() <= rung[k] : b.high() >= rung[k])) {
                    filled[k] = true;
                }
            }
        }
        int nf = 0;
        double sum = 0;
        for (int k = 0; k < n; k++) {
            if (filled[k]) { nf++; sum += rung[k]; }
        }
        double entry = sum / nf;
        double sizeFrac = (double) nf / n;

        double target = buy ? c.entry + rr * origDist : c.entry - rr * origDist;
        double lockLevel = buy ? c.entry + lockR * origDist : c.entry - lockR * origDist;
        double runnerStop = c.stop;
        Double rA = null;
        Double rB = null;
        Instant exA = null;
        Instant exB = null;
        for (int i = c.idx + 1; i < end && (rA == null || rB == null); i++) {
            Candle b = c.ltf.get(i);
            if (rA == null) {
                if (buy ? b.low() <= c.stop : b.high() >= c.stop) {
                    rA = (buy ? c.stop - entry : entry - c.stop) / origDist;
                    exA = b.time();
                    if (rB == null) { rB = rA; exB = b.time(); }
                    break;
                }
                if (buy ? b.high() >= target : b.low() <= target) {
                    rA = rr;
                    exA = b.time();
                }
            }
            if (rB == null) {
                boolean afterTp1 = lockR > 0 && rA != null && rA > 0;
                if (afterTp1) {
                    double edge = trailEdge(c, i, buy, stTrail);
                    runnerStop = buy
                            ? Math.max(runnerStop, Math.max(lockLevel, edge))
                            : Math.min(runnerStop, Math.min(lockLevel, edge));
                }
                double effStop = afterTp1 ? runnerStop : c.stop;
                if (buy ? b.low() <= effStop : b.high() >= effStop) {
                    rB = (buy ? effStop - entry : entry - effStop) / origDist;
                    exB = b.time();
                } else if (bodyBeyondSlow(c, b, i, buy)) {
                    rB = (buy ? b.close() - entry : entry - b.close()) / origDist;
                    exB = b.time();
                }
            }
        }
        Candle last = c.ltf.get(end - 1);
        if (rA == null) { rA = (buy ? last.close() - entry : entry - last.close()) / origDist; exA = last.time(); }
        if (rB == null) { rB = (buy ? last.close() - entry : entry - last.close()) / origDist; exB = last.time(); }
        double r = sizeFrac * (0.5 * rA + 0.5 * rB);
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
