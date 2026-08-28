package com.adam.server.config;

import com.adam.server.broker.Direction;
import com.adam.server.persistence.BrokerSnapshotEntity;
import com.adam.server.persistence.BrokerSnapshotRepository;
import com.adam.server.persistence.SddScanEntity;
import com.adam.server.persistence.SddScanRepository;
import com.adam.server.persistence.SddSignalEntity;
import com.adam.server.persistence.SddSignalRepository;
import com.adam.server.scan.ScanSnapshot;
import com.adam.server.sdd.SddScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeds the local dev database with realistic historical data so the dashboard
 * (daily equity chart, signals list) can be inspected and performance-checked
 * against a large dataset. Runs ONLY with the {@code dev} Spring profile and is
 * idempotent — it skips seeding when {@code broker_snapshots} already contains rows.
 *
 * <p>Configuration (all optional, see {@code application-dev.properties}):
 * <ul>
 *   <li>{@code app.seed.days} — how many days of history to generate (default 730)</li>
 *   <li>{@code app.seed.intraday-per-day} — snapshots per day (0/1 = daily close only)</li>
 *   <li>{@code app.seed.demo-start} / {@code app.seed.live-start} — starting equity</li>
 *   <li>{@code app.seed.signal-per-day-max} — max trade signals generated per day</li>
 * </ul>
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final String[] UNIVERSE = {"GER40", "XAU", "US100", "EURUSD", "BTC"};
    private static final int SAVE_BATCH = 1000;

    private final BrokerSnapshotRepository brokers;
    private final SddScanRepository scans;
    private final SddSignalRepository signals;
    private final ObjectMapper mapper;

    @Value("${app.seed.enabled:true}")
    private boolean enabled;
    @Value("${app.seed.days:730}")
    private int days;
    @Value("${app.seed.intraday-per-day:0}")
    private int intradayPerDay;
    @Value("${app.seed.demo-start:10000}")
    private double demoStart;
    @Value("${app.seed.live-start:437}")
    private double liveStart;
    @Value("${app.seed.signal-per-day-max:3}")
    private int signalPerDayMax;

    public DevDataSeeder(
            BrokerSnapshotRepository brokers,
            SddScanRepository scans,
            SddSignalRepository signals,
            ObjectMapper mapper
    ) {
        this.brokers = brokers;
        this.scans = scans;
        this.signals = signals;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            log.info("[dev-seed] disabled (app.seed.enabled=false)");
            return;
        }
        long existing = brokers.count();
        if (existing > 0) {
            log.info("[dev-seed] broker_snapshots already has {} rows — skipping seed. " +
                    "To reseed, delete the data/ H2 file (dev) or truncate broker_snapshots.", existing);
            return;
        }
        log.info("[dev-seed] generating {} days of historical data (intraday/day={}) for demo+live...",
                days, intradayPerDay);
        long t0 = System.currentTimeMillis();
        int snapshots = seedBrokerSnapshots();
        int signalsWritten = seedScansAndSignals();
        long ms = System.currentTimeMillis() - t0;
        log.info("[dev-seed] done: {} broker_snapshots, {} signals, {} ms", snapshots, signalsWritten, ms);
    }

    private int seedBrokerSnapshots() throws Exception {
        Random rnd = new Random(20260828L); // deterministic → same data every seed
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusDays(days - 1L);
        int perDay = Math.max(1, intradayPerDay);
        double demoEq = demoStart;
        double liveEq = liveStart;
        List<BrokerSnapshotEntity> batch = new ArrayList<>();
        int written = 0;

        for (int d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            // each book gets a daily P/L roughly proportional to its equity
            double demoDayPnl = gauss(rnd) * demoEq * 0.0025;
            double liveDayPnl = gauss(rnd) * liveEq * 0.004;
            double demoOpen = demoEq;
            double liveOpen = liveEq;
            for (int i = 0; i < perDay; i++) {
                double frac = perDay == 1 ? 1.0 : (double) (i + 1) / perDay;
                double demoPnl = demoDayPnl * frac + gauss(rnd) * demoEq * 0.0004;
                double livePnl = liveDayPnl * frac + gauss(rnd) * liveEq * 0.0006;
                demoEq = demoOpen + demoPnl;
                liveEq = liveOpen + livePnl;

                batch.add(snapshotRow("demo", "Account", demoEq, demoPnl, timeFor(date, i, perDay)));
                batch.add(snapshotRow("live", "bot trading konto", liveEq, livePnl, timeFor(date, i, perDay)));
                if (batch.size() >= SAVE_BATCH) {
                    brokers.saveAll(batch);
                    written += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            brokers.saveAll(batch);
            written += batch.size();
        }
        return written;
    }

    private BrokerSnapshotEntity snapshotRow(String book, String name, double equity, double dayPnl, Instant at)
            throws Exception {
        BrokerSnapshotEntity row = new BrokerSnapshotEntity();
        row.setBook(book);
        row.setBroker("capital");
        row.setAccountName(name);
        row.setEquity(round2(equity));
        row.setAvailable(round2(equity * 0.95));
        row.setDayPnl(round2(dayPnl));
        row.setCurrency("PLN");
        row.setConnected(true);
        row.setCapturedAt(at);
        row.setPayload(bookPayload(book, name, equity, dayPnl));
        return row;
    }

    private int seedScansAndSignals() throws Exception {
        Random rnd = new Random(7L); // deterministic
        LocalDate start = LocalDate.now(ZONE).minusDays(days - 1L);
        List<SddSignalEntity> signalBatch = new ArrayList<>();
        int written = 0;
        for (int d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            Instant ts = date.atTime(21, 5).atZone(ZONE).toInstant();
            List<SddScan> daySignals = new ArrayList<>();
            int n = rnd.nextInt(signalPerDayMax + 1);
            for (int s = 0; s < n; s++) {
                daySignals.add(randomSignal(rnd, date));
            }
            ScanSnapshot snap = new ScanSnapshot(
                    ts, "capital", "Capital.com", false, false, daySignals, null,
                    List.of(
                            new ScanSnapshot.BookScan("demo", "capital", null, null),
                            new ScanSnapshot.BookScan("live", "capital", null, null)
                    ),
                    null, null
            );
            SddScanEntity scanRow = new SddScanEntity();
            scanRow.setScannedAt(ts);
            scanRow.setBrokerId("capital");
            scanRow.setBrokerName("Capital.com");
            scanRow.setExecutionEnabled(false);
            scanRow.setNewsBlackout(false);
            scanRow.setPayload(mapper.writeValueAsString(snap));
            SddScanEntity saved = scans.save(scanRow); // need the id for signal linkage
            written++;

            for (SddScan sig : daySignals) {
                SddSignalEntity sigRow = new SddSignalEntity();
                sigRow.setScanId(saved.getId());
                sigRow.setScannedAt(sig.timestamp());
                sigRow.setSymbol(sig.symbol());
                sigRow.setEpic(sig.epic());
                sigRow.setDirection(sig.direction() == null ? null : sig.direction().name());
                sigRow.setFullStack(sig.fullStack());
                sigRow.setFlip(sig.flip());
                sigRow.setReason(sig.reason());
                sigRow.setPayload(mapper.writeValueAsString(sig));
                signalBatch.add(sigRow);
            }
            if (signalBatch.size() >= SAVE_BATCH) {
                signals.saveAll(signalBatch);
                written += signalBatch.size();
                signalBatch.clear();
            }
        }
        if (!signalBatch.isEmpty()) {
            signals.saveAll(signalBatch);
            written += signalBatch.size();
        }
        return written;
    }

    private SddScan randomSignal(Random rnd, LocalDate date) {
        String sym = UNIVERSE[rnd.nextInt(UNIVERSE.length)];
        boolean flip = true;
        boolean full = rnd.nextBoolean();
        Direction dir = rnd.nextBoolean() ? Direction.BUY : Direction.SELL;
        Instant ts = date.atTime(21, 5 + rnd.nextInt(5)).atZone(ZONE).toInstant();
        double entry = switch (sym) {
            case "XAU" -> 2300 + rnd.nextDouble() * 200;
            case "EURUSD" -> 1.05 + rnd.nextDouble() * 0.1;
            case "BTC" -> 55000 + rnd.nextDouble() * 30000;
            case "US100" -> 18000 + rnd.nextDouble() * 3000;
            default -> 15000 + rnd.nextDouble() * 4000; // GER40
        };
        double atr = entry * 0.003;
        double stop = dir == Direction.BUY ? entry - 2.5 * atr : entry + 2.5 * atr;
        SddScan.Setup setup = new SddScan.Setup(flip, full, full, full);
        return new SddScan(
                ts, sym, sym.equals("BTC") ? "BTCUSD" : sym, dir, setup, stop, atr, atr, entry,
                full,
                full ? "full stack " + dir : "HA flip without full stack",
                List.of(), true, flip, full,
                "H4 HA aligned, RMA33 aligned", true
        );
    }

    private String bookPayload(String book, String name, double equity, double dayPnl) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", book);
        m.put("broker", "capital");
        m.put("accountName", name);
        m.put("equity", round2(equity));
        m.put("available", round2(equity * 0.95));
        m.put("dayPnl", round2(dayPnl));
        m.put("currency", "PLN");
        m.put("connected", true);
        m.put("error", null);
        return mapper.writeValueAsString(m);
    }

    private static Instant timeFor(LocalDate date, int slot, int perDay) {
        int minutesFromOpen = perDay == 1 ? 720 : (int) (slot * (720.0 / (perDay - 1)));
        return date.atTime(LocalTime.of(9, 0).plusMinutes(minutesFromOpen)).atZone(ZONE).toInstant();
    }

    private static double gauss(Random rnd) {
        return rnd.nextGaussian();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
