package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.broker.Books;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.hts.HtsBacktestService;
import com.adam.server.hts.HtsScan;
import com.adam.server.hts.HtsScanService;
import com.adam.server.hts.HtsTradeService;
import com.adam.server.hts.HtsVariant;
import com.adam.server.persistence.HtsSignalEntity;
import com.adam.server.persistence.HtsSignalRepository;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.sdd.Adx;
import com.adam.server.web.dto.HtsJournal;
import com.adam.server.web.dto.HtsScorecardRow;
import com.adam.server.web.dto.SwingTradeRow;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTS ("wstęgi") strategy API — all endpoints gated on the {@code hts} book
 * grant (adam + test have it). {@code GET /api/hts/backtest} returns per-trade
 * CSV ({@code format=trades|csv}, for tools/equity_simulator.py) or a per-symbol
 * JSON summary ({@code format=summary}, for the Analytics panel).
 */
@RestController
public class HtsController {

    private final HtsBacktestService backtest;
    private final HtsScanService scan;
    private final HtsSignalRepository signals;
    private final HtsTradeService htsTrades;

    public HtsController(HtsBacktestService backtest, HtsScanService scan, HtsSignalRepository signals,
                         HtsTradeService htsTrades) {
        this.backtest = backtest;
        this.scan = scan;
        this.signals = signals;
        this.htsTrades = htsTrades;
    }

    /**
     * {@code GET /api/hts/backtest?htf=H4&ltf=M15&days=30&rr=2&format=trades} →
     * {@code text/csv} per-trade export for tools/equity_simulator.py.
     */
    @GetMapping(value = "/api/hts/backtest", produces = {MediaType.APPLICATION_JSON_VALUE, "text/csv"})
    public Object backtest(
            @RequestParam(name = "htf", defaultValue = "H4") String htf,
            @RequestParam(name = "ltf", defaultValue = "M15") String ltf,
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "offsetDays", defaultValue = "0") int offsetDays,
            @RequestParam(name = "rr", defaultValue = "2.0") double rr,
            @RequestParam(name = "runner", defaultValue = "false") boolean runner,
            @RequestParam(name = "adx", defaultValue = "false") boolean adx,
            @RequestParam(name = "adxThreshold", defaultValue = "20.0") double adxThreshold,
            @RequestParam(name = "skipConsolidation", defaultValue = "true") boolean skipConsolidation,
            @RequestParam(name = "pivotTargets", defaultValue = "false") boolean pivotTargets,
            @RequestParam(name = "maxNames", defaultValue = "4") int maxNames,
            @RequestParam(name = "stopBuf", defaultValue = "0.25") double stopBuf,
            @RequestParam(name = "adxPermit", defaultValue = "false") boolean adxPermit,
            @RequestParam(name = "runnerLock", defaultValue = "1.0") double runnerLock,
            @RequestParam(name = "split", defaultValue = "1") int split,
            @RequestParam(name = "pyramidMax", defaultValue = "0") int pyramidMax,
            @RequestParam(name = "pyramidGap", defaultValue = "5") int pyramidGap,
            @RequestParam(name = "pyramidMinBuf", defaultValue = "0.5") double pyramidMinBuf,
            @RequestParam(name = "supertrendTrail", defaultValue = "false") boolean supertrendTrail,
            @RequestParam(name = "waveTrendFilter", defaultValue = "false") boolean waveTrendFilter,
            @RequestParam(name = "breakout", defaultValue = "false") boolean breakout,
            @RequestParam(name = "format", defaultValue = "csv") String format,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(Books.HTS)) {
            return ResponseEntity.status(403).body("forbidden");
        }
        var p = new HtsBacktestService.Params(
                Resolution.valueOf(htf.toUpperCase()), Resolution.valueOf(ltf.toUpperCase()),
                days, offsetDays, rr, runner, adx,
                adxThreshold <= 0 ? Adx.TREND_THRESHOLD : adxThreshold,
                skipConsolidation, pivotTargets, maxNames, stopBuf, adxPermit, runnerLock,
                Math.max(1, split),
                Math.max(0, pyramidMax), Math.max(1, pyramidGap), pyramidMinBuf,
                supertrendTrail, waveTrendFilter, breakout);
        if ("summary".equalsIgnoreCase(format) || "json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(backtest.summary(p));
        }
        List<SwingTradeRow> rows = backtest.run(p);
        StringBuilder sb = new StringBuilder("entry_time,exit_time,symbol,direction,result,r_multiple\n");
        for (SwingTradeRow r : rows) {
            sb.append(r.entryTime()).append(',').append(r.exitTime()).append(',').append(r.symbol())
                    .append(',').append(r.direction()).append(',').append(r.result())
                    .append(',').append(r.rMultiple()).append('\n');
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(sb.toString());
    }

    /**
     * E-9 parameter sweep. {@code GET /api/hts/backtest/sweep?rr=1.5,2,2.5&stopBuf=0,0.25&runnerLock=1&adxPermit=false,true}
     * — portfolio aggregate per combo (cross-product capped at 24 cells).
     */
    @GetMapping(value = "/api/hts/backtest/sweep", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object sweep(
            @RequestParam(name = "htf", defaultValue = "H4") String htf,
            @RequestParam(name = "ltf", defaultValue = "M15") String ltf,
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "offsetDays", defaultValue = "0") int offsetDays,
            @RequestParam(name = "rr", defaultValue = "1.5,2,2.5") String rr,
            @RequestParam(name = "stopBuf", defaultValue = "0,0.25") String stopBuf,
            @RequestParam(name = "runnerLock", defaultValue = "1") String runnerLock,
            @RequestParam(name = "adxPermit", defaultValue = "false,true") String adxPermit,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.isAdmin()) {
            return ResponseEntity.status(403).body("forbidden");
        }
        var base = new HtsBacktestService.Params(
                Resolution.valueOf(htf.toUpperCase()), Resolution.valueOf(ltf.toUpperCase()),
                days, offsetDays, 2.0, true, false, Adx.TREND_THRESHOLD,
                true, false, 4, 0.25, false, 1.0, 1, 0, 5, 0.5, false, false, false);
        return backtest.sweep(base, doubles(rr), doubles(stopBuf), doubles(runnerLock), bools(adxPermit));
    }

    /**
     * E-10 walk-forward split. Same params as {@code /api/hts/backtest}; the
     * replayed trades are split by entry time at {@code split} (0..1, default 0.7)
     * into in-sample / out-of-sample halves.
     */
    @GetMapping(value = "/api/hts/backtest/oos", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object oos(
            @RequestParam(name = "htf", defaultValue = "H4") String htf,
            @RequestParam(name = "ltf", defaultValue = "M15") String ltf,
            @RequestParam(name = "days", defaultValue = "60") int days,
            @RequestParam(name = "offsetDays", defaultValue = "0") int offsetDays,
            @RequestParam(name = "rr", defaultValue = "2.0") double rr,
            @RequestParam(name = "stopBuf", defaultValue = "0.25") double stopBuf,
            @RequestParam(name = "runnerLock", defaultValue = "1.0") double runnerLock,
            @RequestParam(name = "adxPermit", defaultValue = "false") boolean adxPermit,
            @RequestParam(name = "split", defaultValue = "0.7") double split,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.isAdmin()) {
            return ResponseEntity.status(403).body("forbidden");
        }
        var p = new HtsBacktestService.Params(
                Resolution.valueOf(htf.toUpperCase()), Resolution.valueOf(ltf.toUpperCase()),
                days, offsetDays, rr, true, false, Adx.TREND_THRESHOLD,
                true, false, 4, stopBuf, adxPermit, runnerLock, 1, 0, 5, 0.5, false, false, false);
        return backtest.oos(p, split);
    }

    private static double[] doubles(String csv) {
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    private static boolean[] bools(String csv) {
        String[] parts = csv.split(",");
        boolean[] out = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Boolean.parseBoolean(parts[i].trim());
        }
        return out;
    }

    /** The most recent HTS scan's signals (in memory), plus scan status. */
    @GetMapping(value = "/api/hts/last", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object last(Authentication authentication) {
        if (denied(authentication)) {
            return Map.of("error", "forbidden");
        }
        // LinkedHashMap, not Map.of — scannedAt is null before the first scan.
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("scannedAt", scan.lastScanAt() == null ? null : scan.lastScanAt().toString());
        out.put("error", scan.lastError() == null ? "" : scan.lastError());
        out.put("signals", scan.last());
        return out;
    }

    /** Persisted HTS signal history (newest first). */
    @GetMapping(value = "/api/hts/signals", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<HtsSignalEntity> signals(
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            Authentication authentication
    ) {
        if (denied(authentication)) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), 500);
        return signals.findAllByOrderByIdDesc(PageRequest.of(0, capped));
    }

    /**
     * HTS trade lifecycle (E-1 / E-3). {@code GET /api/hts/trades?status=OPEN&limit=100}
     * — persisted entries per variant/timeframe, newest first. Omit {@code status}
     * for all.
     */
    @GetMapping(value = "/api/hts/trades", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<HtsTradeEntity> trades(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            Authentication authentication
    ) {
        if (denied(authentication)) {
            return List.of();
        }
        return htsTrades.recent(status, limit);
    }

    /**
     * HTS forward-test scorecard (E-4). {@code GET /api/hts/scorecard} — one row
     * per timeframe model (win rate, avg R, ΣR, max DD in R, realised P/L),
     * aggregated from {@code hts_trades}. Feeds the September keep/drop call.
     */
    @GetMapping(value = "/api/hts/scorecard", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<HtsScorecardRow> scorecard(Authentication authentication) {
        if (denied(authentication)) {
            return List.of();
        }
        return htsTrades.scorecard();
    }

    /**
     * HTS trade journal (E-8). {@code GET /api/hts/journal?variant=&symbol=&from=&to=}
     * — closed trades sliced into a per-day series, an R histogram and
     * per-reason / per-symbol groups. {@code from}/{@code to} are ISO instants.
     */
    @GetMapping(value = "/api/hts/journal", produces = MediaType.APPLICATION_JSON_VALUE)
    public HtsJournal journal(
            @RequestParam(name = "variant", required = false) String variant,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            Authentication authentication
    ) {
        if (denied(authentication)) {
            return new HtsJournal(0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
        }
        return htsTrades.journal(variant, symbol, parseInstant(from), parseInstant(to));
    }

    private static java.time.Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(s.trim());
        } catch (RuntimeException e) {
            try {
                return java.time.LocalDate.parse(s.trim()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /** Manual scan trigger (also a Scheduler backup). */
    @PostMapping(value = "/api/hts/scan", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object trigger(Authentication authentication) {
        if (denied(authentication)) {
            return Map.of("error", "forbidden");
        }
        List<HtsScan> out = scan.scan();
        return Map.of("count", out.size(), "signals", out);
    }

    /**
     * Live end-to-end execution check. {@code POST /api/hts/test-entry?variant=FAST&symbol=BTC&direction=BUY}
     * fires ONE synthetic entry through the real gate (place → confirm → recordOpen)
     * on the variant's demo book, at the current market mid with a 0.4% stop.
     * Honours the {@code hts.execution} flag. Reports whether an {@code hts_trades}
     * row was written and the resolved dealId.
     */
    @PostMapping(value = "/api/hts/test-entry", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object testEntry(
            @RequestParam(name = "variant", defaultValue = "FAST") String variant,
            @RequestParam(name = "symbol", defaultValue = "BTC") String symbol,
            @RequestParam(name = "direction", defaultValue = "BUY") String direction,
            Authentication authentication
    ) {
        if (denied(authentication)) {
            return Map.of("error", "forbidden");
        }
        HtsVariant v;
        Direction dir;
        try {
            v = HtsVariant.valueOf(variant.trim().toUpperCase());
            dir = Direction.valueOf(direction.trim().toUpperCase());
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error",
                    "variant must be CORE/SWING/FAST/CORE_LIVE/CORE_OKX/FAST_OKX and direction BUY/SELL");
        }
        return scan.testEntry(v, symbol, dir);
    }

    private static boolean denied(Authentication authentication) {
        AppUser user = CurrentUser.of(authentication);
        return user != null && !user.canSeeBook(Books.HTS);
    }
}
