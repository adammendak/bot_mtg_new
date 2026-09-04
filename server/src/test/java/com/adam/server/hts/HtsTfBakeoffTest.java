package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.Adx;
import com.adam.server.web.dto.SwingTradeRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-HTS 3-month TF bake-off + add-ons + RR curiosity. CI covers the
 * session-cut helper and CSV replay. The stored-OHLC table is opt-in:
 * {@code -Dhts.bakeoff.ohlc=/tmp/hts-ohlc -Dhts.bakeoff.days=91}.
 */
class HtsTfBakeoffTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final double RR = 2.0;
    private static final List<String> EPICS = List.of("DE40", "GOLD", "US100", "EURUSD", "BTCUSD");
    private static final List<String> NAMES = List.of("GER40", "XAU", "US100", "EURUSD", "BTC");

    @Test
    void sessionFilterMatchesWarsawWeekdaysAndKeepsBtc() {
        Instant night = Instant.parse("2026-07-15T01:00:00Z"); // Wed 03:00 Warsaw (CEST)
        Instant open = Instant.parse("2026-07-15T06:00:00Z");  // Wed 08:00
        Instant last = Instant.parse("2026-07-15T19:55:00Z");  // Wed 21:55
        Instant close = Instant.parse("2026-07-15T20:00:00Z"); // Wed 22:00 — dead
        Instant saturday = Instant.parse("2026-07-18T10:00:00Z");

        assertThat(HtsBacktestService.sessionAllows(night, "GER40", true)).isFalse();
        assertThat(HtsBacktestService.sessionAllows(open, "GER40", true)).isTrue();
        assertThat(HtsBacktestService.sessionAllows(last, "XAU", true)).isTrue();
        assertThat(HtsBacktestService.sessionAllows(close, "US100", true)).isFalse();
        assertThat(HtsBacktestService.sessionAllows(saturday, "EURUSD", true)).isFalse();
        assertThat(HtsBacktestService.sessionAllows(night, "BTC", true)).isTrue();
        assertThat(HtsBacktestService.sessionAllows(saturday, "BTC", true)).isTrue();
        assertThat(HtsBacktestService.sessionAllows(night, "GER40", false)).isTrue();
    }

    @Test
    void replayBrokerServesCsvAndRefusesOrders() throws Exception {
        Path dir = Files.createTempDirectory("hts-replay");
        Files.writeString(dir.resolve("DE40_H1.csv"),
                "time,open,high,low,close,volume\n"
                        + "2026-07-01T00:00:00Z,1,2,0.5,1.5,10\n"
                        + "2026-07-01T01:00:00Z,1.5,2.5,1,2,10\n");
        ReplayBrokerClient replay = ReplayBrokerClient.fromCsvDir(dir);
        assertThat(replay.size("DE40", Resolution.H1)).isEqualTo(2);
        assertThat(replay.first("DE40", Resolution.H1)).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        List<Candle> slice = replay.candles("DE40", Resolution.H1,
                Instant.parse("2026-07-01T00:30:00Z"), Instant.parse("2026-07-01T01:00:00Z"), 1000);
        assertThat(slice).hasSize(1);
        assertThat(slice.getFirst().close()).isEqualTo(2.0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> replay.placeMarketOrder(null))
                .isInstanceOf(com.adam.server.broker.BrokerException.class);
    }

    @Test
    @EnabledIfSystemProperty(named = "hts.bakeoff.ohlc", matches = ".+")
    void storedOhlcTfBakeoffAndAddons() throws Exception {
        Path dir = Path.of(System.getProperty("hts.bakeoff.ohlc"));
        ReplayBrokerClient replay = ReplayBrokerClient.fromCsvDir(dir);
        HtsBacktestService svc = service(replay);
        int days = Integer.getInteger("hts.bakeoff.days", 91);

        System.out.println("OHLC dir: " + dir.toAbsolutePath());
        System.out.println("eval days=" + days + "  (from Instant.now()-LTF minus days; Yahoo M5/M15 cap ~60d)");
        System.out.printf("%-8s %-4s %6s  %s .. %s%n", "name", "TF", "bars", "first", "last");
        for (int i = 0; i < EPICS.size(); i++) {
            String epic = EPICS.get(i);
            String name = NAMES.get(i);
            for (Resolution res : List.of(Resolution.M5, Resolution.M15, Resolution.H1, Resolution.H4, Resolution.D1)) {
                int n = replay.size(epic, res);
                if (n == 0) {
                    System.out.printf("%-8s %-4s %6s  (missing)%n", name, res, 0);
                    continue;
                }
                System.out.printf("%-8s %-4s %6d  %s .. %s%n",
                        name, res, n, replay.first(epic, res), replay.last(epic, res));
            }
        }

        record Pair(String label, Resolution htf, Resolution ltf) {
        }
        List<Pair> tfs = List.of(
                new Pair("CORE H4/M15", Resolution.H4, Resolution.M15),
                new Pair("FAST H1/M5", Resolution.H1, Resolution.M5),
                new Pair("SWING D1/H1", Resolution.D1, Resolution.H1)
        );

        System.out.println();
        System.out.println("======== PART A — TF bake-off (live baseline, no add-ons) ========");
        Map<String, List<SwingTradeRow>> baselines = new TreeMap<>();
        for (Pair p : tfs) {
            List<SwingTradeRow> rows = svc.run(liveLike(p.htf, p.ltf, days));
            baselines.put(p.label, rows);
            System.out.println("== " + p.label + " baseline ==");
            printStats("baseline", rows);
            perTicker(rows);
        }

        System.out.println();
        System.out.println("======== PART B — add-ons (one lever vs that TF's baseline) ========");
        System.out.println("ADX gate: LTF Wilder ADX(14) >= 20 (Adx.TREND_THRESHOLD / HtsSignalContext");
        System.out.println("  'trend' vs 'no-trend (blue)') AND aligned DI leads (existing T3 hard).");
        System.out.println("No +1R lock: runnerLockR=0 — after TP1 trail fast only, never worse than");
        System.out.println("  the original structural stop. 50% @ 2R unchanged.");
        System.out.println("Session: Warsaw [08:00, 22:00) weekdays; BTC kept overnight+weekend.");
        for (Pair p : tfs) {
            HtsBacktestService.Params base = liveLike(p.htf, p.ltf, days);
            System.out.println("== " + p.label + " ==");
            printStats("baseline", baselines.get(p.label));
            printStats("ADX ON", svc.run(base.withAdx(true, false)));
            printStats("no +1R lock", svc.run(base.withRunnerLockR(0)));
            if (p.ltf == Resolution.M5 || p.ltf == Resolution.M15) {
                String tag = p.ltf == Resolution.M5 ? "session FAST" : "session CORE (footnote)";
                printStats(tag, svc.run(base.withSessionFilter(true)));
            } else {
                System.out.println("  session     skipped on SWING (too few bars; not applied)");
            }
        }

        // Stacked best-of only if a lever clearly helped — print CORE/FAST session+ADX as extra
        // only when both beat baseline on avg R. Computed after the one-at-a-time table.
        System.out.println();
        System.out.println("======== stacked extras (only printed, labeled stacked) ========");
        for (Pair p : tfs) {
            HtsBacktestService.Params base = liveLike(p.htf, p.ltf, days);
            List<SwingTradeRow> adx = svc.run(base.withAdx(true, false));
            List<SwingTradeRow> lock = svc.run(base.withRunnerLockR(0));
            Stats b = stats(baselines.get(p.label));
            boolean adxHelps = stats(adx).avgR > b.avgR && stats(adx).n >= 5;
            boolean lockHelps = stats(lock).avgR > b.avgR && stats(lock).n >= 5;
            if (adxHelps && lockHelps) {
                printStats(p.label + " stacked ADX+noLock",
                        svc.run(base.withAdx(true, false).withRunnerLockR(0)));
            }
            if (p.ltf == Resolution.M5) {
                List<SwingTradeRow> sess = svc.run(base.withSessionFilter(true));
                if (stats(sess).avgR > b.avgR && adxHelps) {
                    printStats(p.label + " stacked ADX+session",
                            svc.run(base.withAdx(true, false).withSessionFilter(true)));
                }
            }
        }

        System.out.println();
        System.out.println("======== RR curiosity (baseline engine; ADX off, lock +1R, no session) ========");
        System.out.println("2R live = TP1 50% @ 2R + runner.  1:1 = same 50% scale at 1R.");
        System.out.println("0.5:1 = FULL close at 0.5R (runner=false) — 50% scale-out at 0.5R is awkward.");
        for (Pair p : tfs) {
            HtsBacktestService.Params base = liveLike(p.htf, p.ltf, days);
            System.out.println("== " + p.label + " RR ==");
            printStats("2R live", baselines.get(p.label));
            printStats("1:1 50%", svc.run(base.withRr(1.0, true)));
            printStats("0.5:1 full", svc.run(base.withRr(0.5, false)));
        }
    }

    private record Stats(int n, double wr, double sumR, double avgR, double pf, double maxDd, double avgHrs,
                         Instant first, Instant last) {
    }

    private static Stats stats(List<SwingTradeRow> rows) {
        int n = rows.size();
        int wins = 0;
        double sumR = 0;
        double grossWin = 0;
        double grossLoss = 0;
        double cum = 0;
        double peak = 0;
        double maxDd = 0;
        long durSec = 0;
        int durN = 0;
        Instant first = null;
        Instant last = null;
        for (SwingTradeRow r : rows) {
            double rm = r.rMultiple();
            sumR += rm;
            if (rm > 1e-9) {
                wins++;
                grossWin += rm;
            } else if (rm < -1e-9) {
                grossLoss += -rm;
            }
            cum += rm;
            peak = Math.max(peak, cum);
            maxDd = Math.max(maxDd, peak - cum);
            Instant e = parseIso(r.entryTime());
            Instant x = parseIso(r.exitTime());
            if (e != null) {
                first = first == null || e.isBefore(first) ? e : first;
                last = last == null || e.isAfter(last) ? e : last;
            }
            if (e != null && x != null) {
                durSec += Math.max(0, x.getEpochSecond() - e.getEpochSecond());
                durN++;
            }
        }
        double wr = n == 0 ? 0 : (double) wins / n;
        double avgR = n == 0 ? 0 : sumR / n;
        double pf = grossLoss > 0 ? grossWin / grossLoss : (grossWin > 0 ? Double.POSITIVE_INFINITY : 0);
        double avgHrs = durN == 0 ? 0 : (durSec / (double) durN) / 3600.0;
        return new Stats(n, wr, sumR, avgR, pf, maxDd, avgHrs, first, last);
    }

    private static void printStats(String name, List<SwingTradeRow> rows) {
        Stats s = stats(rows);
        System.out.printf(
                "  %-22s n=%-4d  WR=%5.1f%%  sumR=%7.2f  avgR=%6.3f  PF=%5s  maxDD=%6.2fR  avgDur=%.1fh  entries %s .. %s%n",
                name, s.n, s.wr * 100.0, s.sumR, s.avgR,
                Double.isInfinite(s.pf) ? "inf" : String.format("%.2f", s.pf),
                s.maxDd, s.avgHrs,
                s.first == null ? "-" : s.first, s.last == null ? "-" : s.last);
    }

    private static void perTicker(List<SwingTradeRow> rows) {
        Map<String, double[]> agg = new TreeMap<>();
        for (SwingTradeRow r : rows) {
            double[] a = agg.computeIfAbsent(r.symbol(), k -> new double[3]);
            a[0]++;
            a[1] += r.rMultiple();
            if (r.rMultiple() > 1e-9) {
                a[2]++;
            }
        }
        if (agg.isEmpty()) {
            System.out.println("      (0 trades)");
            return;
        }
        for (var e : agg.entrySet()) {
            double[] a = e.getValue();
            System.out.printf("      %-8s n=%-3.0f  WR=%5.1f%%  sumR=%7.2f%n",
                    e.getKey(), a[0], a[0] == 0 ? 0 : 100.0 * a[2] / a[0], a[1]);
        }
    }

    private static Instant parseIso(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, ISO).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Live HTS: pullback+reclaim, consolidating skip, stop 0.25× width, TP1 50% @ 2R,
     * runner lock +1R + trail, ADX off, no news, no pyramid, universe=5.
     */
    private static HtsBacktestService.Params liveLike(Resolution htf, Resolution ltf, int days) {
        return new HtsBacktestService.Params(
                htf, ltf, days, 0, RR, /*runner*/ true,
                /*adxFilter*/ false, /*adxThreshold*/ Adx.TREND_THRESHOLD, /*skipConsolidation*/ true,
                /*pivotTargets*/ false, /*maxNames*/ 5,
                /*stopBufferFrac*/ 0.25, /*adxPermit*/ false, /*runnerLockR*/ 1.0,
                /*splitEntries*/ 1, /*pyramidMax*/ 0, /*pyramidGapBars*/ 5, /*pyramidMinBufferR*/ 0.5,
                /*supertrendTrail*/ false, /*waveTrendFilter*/ false, /*breakoutEntry*/ false,
                /*sessionFilter*/ false);
    }

    private static HtsBacktestService service(ReplayBrokerClient replay) {
        BrokerBooks books = new BrokerBooks(
                replay,
                new UnavailableBrokerClient("live", "test"),
                new UnavailableBrokerClient("glowne", "test"),
                new UnavailableBrokerClient("swing", "test"),
                new UnavailableBrokerClient("hts", "test"));
        return new HtsBacktestService(books, new AppProperties());
    }
}
