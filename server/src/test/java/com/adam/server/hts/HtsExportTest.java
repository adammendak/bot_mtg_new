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
        return new HtsBacktestService.Params(htf, ltf, days, off, RR, /*runner*/ true,
                /*adxFilter*/ adx != Adx.OFF, /*adxThreshold*/ 20.0, /*skipConsolidation*/ true,
                /*pivotTargets*/ pivots, /*maxNames*/ 4,
                /*stopBufferFrac*/ stopBuf, /*adxPermit*/ adx == Adx.PERMIT);
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
