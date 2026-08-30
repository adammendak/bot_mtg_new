package com.adam.server.hts;

import com.adam.server.broker.Resolution;
import com.adam.server.web.dto.SwingTradeRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Local tool (NOT for CI): HTS backtest across the 3 timeframe models. Grid:
 * ADX {off, hard-gate T3, permit T3'} × stop-buffer {0.0, 0.25} × month-2-back +
 * recent. Consolidation filter always on (per the videos). {@code pivotTargets}
 * run separately at the end for the T4 comparison. Needs the {@code local}
 * profile with real Capital.com demo credentials.
 */
@SpringBootTest
@ActiveProfiles("local")
class HtsExportTest {

    private static final Path OUT = Path.of(System.getProperty("java.io.tmpdir"), "hts");
    private static final double RR = 2.0;

    @Autowired
    HtsBacktestService backtest;

    private enum Adx { OFF, HARD, PERMIT }

    private HtsBacktestService.Params params(Resolution htf, Resolution ltf, int days, int off,
                                             Adx adx, double stopBuf, boolean pivots) {
        return params(htf, ltf, days, off, adx, stopBuf, pivots, 1);
    }

    private HtsBacktestService.Params params(Resolution htf, Resolution ltf, int days, int off,
                                             Adx adx, double stopBuf, boolean pivots, int split) {
        return params(htf, ltf, days, off, adx, stopBuf, pivots, split, 0, false, false);
    }

    private HtsBacktestService.Params params(Resolution htf, Resolution ltf, int days, int off,
                                             Adx adx, double stopBuf, boolean pivots, int split,
                                             int pyramidMax, boolean stTrail, boolean wtFilter) {
        return params(htf, ltf, days, off, adx, stopBuf, pivots, split, pyramidMax, stTrail, wtFilter, false);
    }

    private HtsBacktestService.Params params(Resolution htf, Resolution ltf, int days, int off,
                                             Adx adx, double stopBuf, boolean pivots, int split,
                                             int pyramidMax, boolean stTrail, boolean wtFilter, boolean breakout) {
        return new HtsBacktestService.Params(htf, ltf, days, off, RR, /*runner*/ true,
                /*adxFilter*/ adx != Adx.OFF, /*adxThreshold*/ 20.0, /*skipConsolidation*/ true,
                /*pivotTargets*/ pivots, /*maxNames*/ 4,
                /*stopBufferFrac*/ stopBuf, /*adxPermit*/ adx == Adx.PERMIT, /*runnerLockR*/ 1.0,
                /*splitEntries*/ split,
                /*pyramidMax*/ pyramidMax, /*pyramidGapBars*/ 5, /*pyramidMinBufferR*/ 0.5,
                /*supertrendTrail*/ stTrail, /*waveTrendFilter*/ wtFilter, /*breakoutEntry*/ breakout);
    }

    /**
     * Breakout vs pullback entry, HTF supporting, on the settled config (ADX-permit,
     * buf 0.25, runner-lock). D1/H1 and H4/M15, both windows. Own fresh session.
     */
    @Test
    void breakoutEntry() throws Exception {
        Files.createDirectories(OUT);
        Resolution[][] pairs = {{Resolution.D1, Resolution.H1}, {Resolution.H4, Resolution.M15}};
        String[] pairName = {"d1h1", "h4m15"};
        int[][] windows = {{30, 30}, {30, 0}};
        String[] wn = {"m2back", "recent"};
        for (int pi = 0; pi < pairs.length; pi++) {
            for (int w = 0; w < windows.length; w++) {
                run(pairName[pi] + "_" + wn[w] + "_pullback",
                        params(pairs[pi][0], pairs[pi][1], windows[w][0], windows[w][1],
                                Adx.PERMIT, 0.25, false, 1, 0, false, false, false));
                run(pairName[pi] + "_" + wn[w] + "_breakout",
                        params(pairs[pi][0], pairs[pi][1], windows[w][0], windows[w][1],
                                Adx.PERMIT, 0.25, false, 1, 0, false, false, true));
            }
        }
        System.out.println("CSV dir: " + OUT);
    }

    /**
     * T7 + T8 focused: pyramid {0,1,2,3}, Supertrend-trail on/off, WaveTrend-filter
     * on/off — on the best config so far (ADX-permit, buf 0.25), both swing models,
     * both windows. Own fresh session.
     */
    @Test
    void pyramidAndIndicators() throws Exception {
        pyrGrid(Resolution.D1, Resolution.H1, "d1h1");
    }

    /** Same grid, H4/M15 only — its own fresh session (the combined run throttles before it). */
    @Test
    void pyramidAndIndicatorsH4M15() throws Exception {
        pyrGrid(Resolution.H4, Resolution.M15, "h4m15");
    }

    private void pyrGrid(Resolution htf, Resolution ltf, String pairName) throws Exception {
        Files.createDirectories(OUT);
        int[][] windows = {{30, 30}, {30, 0}};
        String[] wn = {"m2back", "recent"};
        for (int w = 0; w < windows.length; w++) {
            for (int pyr : new int[]{0, 1, 2, 3}) {
                run(pairName + "_" + wn[w] + "_pyr" + pyr,
                        params(htf, ltf, windows[w][0], windows[w][1], Adx.PERMIT, 0.25, false, 1, pyr, false, false));
            }
            run(pairName + "_" + wn[w] + "_sttrail",
                    params(htf, ltf, windows[w][0], windows[w][1], Adx.PERMIT, 0.25, false, 1, 0, true, false));
            run(pairName + "_" + wn[w] + "_wtfilter",
                    params(htf, ltf, windows[w][0], windows[w][1], Adx.PERMIT, 0.25, false, 1, 0, false, true));
        }
        System.out.println("CSV dir: " + OUT);
    }

    private void run(String label, HtsBacktestService.Params p) throws Exception {
        List<SwingTradeRow> rows = backtest.run(p);
        write(OUT.resolve("hts_" + label + ".csv"), rows);
        System.out.println("== " + label + " ==");
        perTicker(rows);
    }

    /**
     * T6 focused: split‑entry {1,2,3} on the best config so far (ADX‑permit, buf 0.25),
     * both swing models, both windows. Own fresh session.
     */
    @Test
    void splitEntry() throws Exception {
        Files.createDirectories(OUT);
        Resolution[][] pairs = {{Resolution.D1, Resolution.H1}, {Resolution.H4, Resolution.M15}};
        String[] pairName = {"d1h1", "h4m15"};
        int[][] windows = {{30, 30}, {30, 0}};
        String[] wn = {"m2back", "recent"};
        for (int pi = 0; pi < pairs.length; pi++) {
            for (int w = 0; w < windows.length; w++) {
                for (int split : new int[]{1, 2, 3}) {
                    var p = params(pairs[pi][0], pairs[pi][1], windows[w][0], windows[w][1],
                            Adx.PERMIT, 0.25, false, split);
                    List<SwingTradeRow> rows = backtest.run(p);
                    String label = pairName[pi] + "_" + wn[w] + "_split" + split;
                    write(OUT.resolve("hts_" + label + ".csv"), rows);
                    System.out.println("== " + label + " ==");
                    perTicker(rows);
                }
            }
        }
        System.out.println("CSV dir: " + OUT);
    }

    @Test
    void grid() throws Exception {
        Files.createDirectories(OUT);
        Resolution[][] pairs = {
                {Resolution.H4, Resolution.M15}, {Resolution.D1, Resolution.H1}, {Resolution.H1, Resolution.M5}
        };
        String[] pairName = {"h4m15", "d1h1", "h1m5"};
        int[][] windows = {{30, 30}, {30, 0}};
        String[] wn = {"m2back", "recent"};

        for (int pi = 0; pi < pairs.length; pi++) {
            for (int w = 0; w < windows.length; w++) {
                for (Adx adx : Adx.values()) {
                    for (double stopBuf : new double[]{0.0, 0.25}) {
                        var p = params(pairs[pi][0], pairs[pi][1], windows[w][0], windows[w][1],
                                adx, stopBuf, false);
                        List<SwingTradeRow> rows = backtest.run(p);
                        String label = pairName[pi] + "_" + wn[w] + "_adx" + adx.name().toLowerCase()
                                + "_buf" + (int) Math.round(stopBuf * 100);
                        write(OUT.resolve("hts_" + label + ".csv"), rows);
                        System.out.println("== " + label + " ==");
                        perTicker(rows);
                    }
                }
            }
        }

        // T4: pivot multi-target mode, best-guess params (permit ADX, 0.25 buffer).
        for (int pi = 0; pi < pairs.length; pi++) {
            for (int w = 0; w < windows.length; w++) {
                var p = params(pairs[pi][0], pairs[pi][1], windows[w][0], windows[w][1],
                        Adx.PERMIT, 0.25, true);
                List<SwingTradeRow> rows = backtest.run(p);
                String label = pairName[pi] + "_" + wn[w] + "_pivots";
                write(OUT.resolve("hts_" + label + ".csv"), rows);
                System.out.println("== " + label + " ==");
                perTicker(rows);
            }
        }
        System.out.println("CSV dir: " + OUT);
    }

    /**
     * Focused re-run: D1/H1 + the pivot-target (T4) pass on their own, with a
     * fresh session. In the full {@link #grid()} run the tail pairs come back
     * empty once Capital.com throttles / M5 history runs out — this isolates them.
     */
    @Test
    void d1h1AndPivots() throws Exception {
        Files.createDirectories(OUT);
        int[][] windows = {{30, 30}, {30, 0}};
        String[] wn = {"m2back", "recent"};
        for (int w = 0; w < windows.length; w++) {
            for (Adx adx : Adx.values()) {
                for (double stopBuf : new double[]{0.0, 0.25}) {
                    var p = params(Resolution.D1, Resolution.H1, windows[w][0], windows[w][1], adx, stopBuf, false);
                    List<SwingTradeRow> rows = backtest.run(p);
                    String label = "d1h1_" + wn[w] + "_adx" + adx.name().toLowerCase()
                            + "_buf" + (int) Math.round(stopBuf * 100);
                    write(OUT.resolve("hts_" + label + ".csv"), rows);
                    System.out.println("== " + label + " ==");
                    perTicker(rows);
                }
            }
        }
        Resolution[][] pivotPairs = {{Resolution.H4, Resolution.M15}, {Resolution.D1, Resolution.H1}};
        String[] pivotName = {"h4m15", "d1h1"};
        for (int pi = 0; pi < pivotPairs.length; pi++) {
            for (int w = 0; w < windows.length; w++) {
                var p = params(pivotPairs[pi][0], pivotPairs[pi][1], windows[w][0], windows[w][1],
                        Adx.PERMIT, 0.25, true);
                List<SwingTradeRow> rows = backtest.run(p);
                String label = pivotName[pi] + "_" + wn[w] + "_pivots";
                write(OUT.resolve("hts_" + label + ".csv"), rows);
                System.out.println("== " + label + " ==");
                perTicker(rows);
            }
        }
        System.out.println("CSV dir: " + OUT);
    }

    static void perTicker(List<SwingTradeRow> rows) {
        Map<String, double[]> agg = new TreeMap<>(); // n, W, L, sumR
        for (SwingTradeRow r : rows) {
            double[] a = agg.computeIfAbsent(r.symbol(), k -> new double[4]);
            a[0]++;
            if ("WIN".equals(r.result())) {
                a[1]++;
            } else if ("LOSS".equals(r.result())) {
                a[2]++;
            }
            a[3] += r.rMultiple();
        }
        double tn = 0;
        double tr = 0;
        for (var e : agg.entrySet()) {
            double[] a = e.getValue();
            System.out.printf("  %-8s n=%-4.0f W=%-3.0f L=%-3.0f sumR=%7.2f avgR=%7.3f%n",
                    e.getKey(), a[0], a[1], a[2], a[3], a[0] == 0 ? 0 : a[3] / a[0]);
            tn += a[0];
            tr += a[3];
        }
        System.out.printf("  %-8s n=%-4.0f %19s sumR=%7.2f avgR=%7.3f%n", "ALL", tn, "", tr, tn == 0 ? 0 : tr / tn);
    }

    static void write(Path f, List<SwingTradeRow> rows) throws Exception {
        StringBuilder sb = new StringBuilder("entry_time,exit_time,symbol,direction,result,r_multiple\n");
        for (SwingTradeRow r : rows) {
            sb.append(r.entryTime()).append(',').append(r.exitTime()).append(',').append(r.symbol()).append(',')
                    .append(r.direction()).append(',').append(r.result()).append(',').append(r.rMultiple()).append('\n');
        }
        Files.writeString(f, sb.toString());
    }
}
