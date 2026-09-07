package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.web.dto.SwingTradeRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A/B of HTS gate (4) only: pullback-then-reclaim vs first body close beyond
 * the fast band. The synthetic case is CI; the stored-OHLC case is opt-in
 * ({@code -Dhts.ab.ohlc=/path/to/csv}).
 */
class HtsPullbackAbTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final double RR = 2.0;

    @Test
    void droppingPullbackTakesTheFirstBodyBeyondWhenAStrongTrendNeverDips() {
        Instant end = Instant.now().minus(Duration.ofMinutes(15));
        Duration m15 = Duration.ofMinutes(15);
        int n = 900;
        Instant start = end.minus(m15.multipliedBy(n));
        List<Candle> ltf = trend(start, m15, n, 10_000, 4.0, 0.2);
        List<Candle> htf = resample(ltf, 4);

        ReplayBrokerClient replay = new ReplayBrokerClient();
        replay.put("DE40", Resolution.M15, ltf);
        replay.put("DE40", Resolution.H1, htf);

        HtsBacktestService svc = service(replay);
        HtsBacktestService.Params liveLike = liveLike(Resolution.H1, Resolution.M15, 20, 0);
        List<SwingTradeRow> pullback = svc.run(liveLike);
        List<SwingTradeRow> immediate = svc.run(liveLike.withRequirePullback(false));

        assertThat(immediate).as("immediate body-break should fire in a clean trend").isNotEmpty();
        Set<String> aKeys = keys(pullback);
        int extra = 0;
        for (SwingTradeRow r : immediate) {
            if (!aKeys.contains(key(r))) {
                extra++;
            }
        }
        assertThat(extra).as("B must include at least one bar A would skip").isGreaterThan(0);
    }

    /**
     * Opt-in real-window A/B. CSV files named {@code <epic>_<RES>.csv}
     * (DE40_M15.csv, GOLD_H1.csv, …). Prints the table Adam asked for.
     */
    @Test
    @EnabledIfSystemProperty(named = "hts.ab.ohlc", matches = ".+")
    void storedOhlcAbTable() throws Exception {
        Path dir = Path.of(System.getProperty("hts.ab.ohlc"));
        ReplayBrokerClient replay = ReplayBrokerClient.fromCsvDir(dir);
        HtsBacktestService svc = service(replay);

        int days = Integer.getInteger("hts.ab.days", 55);
        System.out.println("OHLC dir: " + dir.toAbsolutePath());
        for (String epic : List.of("DE40", "GOLD", "US100", "EURUSD", "BTCUSD")) {
            for (Resolution res : List.of(Resolution.M5, Resolution.M15, Resolution.H1, Resolution.H4)) {
                int n = replay.size(epic, res);
                if (n == 0) {
                    continue;
                }
                System.out.printf("  %s %s n=%d %s .. %s%n",
                        epic, res, n, replay.first(epic, res), replay.last(epic, res));
            }
        }

        runPair(svc, "H1/M15", Resolution.H1, Resolution.M15, days);
        runPair(svc, "H1/M5", Resolution.H1, Resolution.M5, days);
        if (replay.size("DE40", Resolution.H4) > 0 || replay.size("BTCUSD", Resolution.H4) > 0) {
            runPair(svc, "H4/M15 (live CORE footnote)", Resolution.H4, Resolution.M15, days);
        }
    }

    private static void runPair(HtsBacktestService svc, String label, Resolution htf, Resolution ltf, int days) {
        HtsBacktestService.Params base = liveLike(htf, ltf, days, 0);
        List<SwingTradeRow> a = svc.run(base);
        List<SwingTradeRow> b = svc.run(base.withRequirePullback(false));
        System.out.println();
        System.out.println("== " + label + "  window=" + days + "d  maxNames=5  runner 2R/lock+1R  no pyramid  ADX off  skipConsol on ==");
        printStats("A pullback", a);
        perTicker(a);
        printStats("B immediate", b);
        perTicker(b);
        Set<String> aKeys = keys(a);
        int extra = 0;
        int weekendNonBtc = 0;
        System.out.println("  extra B entries (A would skip that bar):");
        for (SwingTradeRow r : b) {
            Instant e = parseIso(r.entryTime());
            if (e != null) {
                var dow = e.atZone(ZoneOffset.UTC)
                        .withZoneSameInstant(java.time.ZoneId.of("Europe/Warsaw"))
                        .getDayOfWeek();
                if ((dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY)
                        && !"BTC".equals(r.symbol())) {
                    weekendNonBtc++;
                }
            }
            if (!aKeys.contains(key(r))) {
                extra++;
                System.out.printf("    %s %s %s r=%s%n", r.entryTime(), r.symbol(), r.direction(), r.rMultiple());
            }
        }
        System.out.printf("  B trades whose entry bar A would skip: %d  (nB-nA=%d)%n", extra, b.size() - a.size());
        System.out.printf("  weekend non-BTC entries in B (backtest has NO Sat/Sun BTC-only filter): %d%n",
                weekendNonBtc);
    }

    private static void perTicker(List<SwingTradeRow> rows) {
        java.util.Map<String, double[]> agg = new java.util.TreeMap<>();
        for (SwingTradeRow r : rows) {
            double[] a = agg.computeIfAbsent(r.symbol(), k -> new double[3]);
            a[0]++;
            a[1] += r.rMultiple();
            if (r.rMultiple() > 1e-9) {
                a[2]++;
            }
        }
        for (var e : agg.entrySet()) {
            double[] a = e.getValue();
            System.out.printf("      %-8s n=%-3.0f  WR=%5.1f%%  sumR=%7.2f%n",
                    e.getKey(), a[0], a[0] == 0 ? 0 : 100.0 * a[2] / a[0], a[1]);
        }
    }

    private static void printStats(String name, List<SwingTradeRow> rows) {
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
        System.out.printf(
                "  %-12s n=%-4d  WR=%5.1f%%  sumR=%7.2f  avgR=%6.3f  PF=%5s  maxDD=%6.2fR  avgDur=%.1fh  entries %s .. %s%n",
                name, n, wr * 100.0, sumR, avgR,
                Double.isInfinite(pf) ? "inf" : String.format("%.2f", pf),
                maxDd, avgHrs,
                first == null ? "-" : first, last == null ? "-" : last);
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

    private static Set<String> keys(List<SwingTradeRow> rows) {
        Set<String> out = new HashSet<>();
        for (SwingTradeRow r : rows) {
            out.add(key(r));
        }
        return out;
    }

    private static String key(SwingTradeRow r) {
        return r.symbol() + "|" + r.entryTime() + "|" + r.direction();
    }

    /** Live HTS exits + gates, minus the pullback flag under test. Universe = 5 names, no pyramid. */
    private static HtsBacktestService.Params liveLike(Resolution htf, Resolution ltf, int days, int off) {
        return new HtsBacktestService.Params(
                htf, ltf, days, off, RR, /*runner*/ true,
                /*adxFilter*/ false, /*adxThreshold*/ 20.0, /*skipConsolidation*/ true,
                /*pivotTargets*/ false, /*maxNames*/ 5,
                /*stopBufferFrac*/ 0.25, /*adxPermit*/ false, /*runnerLockR*/ 1.0,
                /*splitEntries*/ 1, /*pyramidMax*/ 0, /*pyramidGapBars*/ 5, /*pyramidMinBufferR*/ 0.5,
                /*supertrendTrail*/ false, /*waveTrendFilter*/ false, /*breakoutEntry*/ false,
                /*requirePullback*/ true);
    }

    private static HtsBacktestService service(ReplayBrokerClient replay) {
        BrokerBooks books = new BrokerBooks(
                replay,
                new UnavailableBrokerClient("live", "test"),
                new UnavailableBrokerClient("glowne", "test"),
                new UnavailableBrokerClient("swing", "test"),
                new UnavailableBrokerClient("hts", "test"),
                new UnavailableBrokerClient("okx", "test"));
        return new HtsBacktestService(books, new AppProperties());
    }

    private static List<Candle> trend(Instant start, Duration step, int count, double seed, double increment,
                                      double wick) {
        List<Candle> out = new ArrayList<>(count);
        Instant t = start;
        double px = seed;
        for (int i = 0; i < count; i++) {
            double open = px;
            double close = px + increment;
            out.add(new Candle(t, open, close + wick, open, close, 10));
            px = close;
            t = t.plus(step);
        }
        return out;
    }

    private static List<Candle> resample(List<Candle> ltf, int group) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i + group <= ltf.size(); i += group) {
            Candle a = ltf.get(i);
            double high = a.high();
            double low = a.low();
            double close = a.close();
            for (int k = 1; k < group; k++) {
                Candle b = ltf.get(i + k);
                high = Math.max(high, b.high());
                low = Math.min(low, b.low());
                close = b.close();
            }
            out.add(new Candle(a.time(), a.open(), high, low, close, 10));
        }
        return out;
    }
}
