package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.broker.Books;
import com.adam.server.broker.Resolution;
import com.adam.server.hts.HtsBacktestService;
import com.adam.server.hts.HtsScan;
import com.adam.server.hts.HtsScanService;
import com.adam.server.persistence.HtsSignalEntity;
import com.adam.server.persistence.HtsSignalRepository;
import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.persistence.HtsTradeRepository;
import com.adam.server.sdd.Adx;
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
 * HTS ("wstęgi") strategy API. The backtest export is admin-only; the live scan
 * endpoints are gated on the {@code hts} book grant (adam + test have it).
 */
@RestController
public class HtsController {

    private final HtsBacktestService backtest;
    private final HtsScanService scan;
    private final HtsSignalRepository signals;
    private final HtsTradeRepository htsTrades;

    public HtsController(HtsBacktestService backtest, HtsScanService scan, HtsSignalRepository signals,
                         HtsTradeRepository htsTrades) {
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
            @RequestParam(name = "format", defaultValue = "csv") String format,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.isAdmin()) {
            return ResponseEntity.status(403).body("forbidden");
        }
        var p = new HtsBacktestService.Params(
                Resolution.valueOf(htf.toUpperCase()), Resolution.valueOf(ltf.toUpperCase()),
                days, offsetDays, rr, runner, adx,
                adxThreshold <= 0 ? Adx.TREND_THRESHOLD : adxThreshold,
                skipConsolidation, pivotTargets, maxNames, stopBuf, adxPermit, runnerLock,
                Math.max(1, split),
                Math.max(0, pyramidMax), Math.max(1, pyramidGap), pyramidMinBuf,
                supertrendTrail, waveTrendFilter);
        List<SwingTradeRow> rows = backtest.run(p);
        StringBuilder sb = new StringBuilder("entry_time,exit_time,symbol,direction,result,r_multiple\n");
        for (SwingTradeRow r : rows) {
            sb.append(r.entryTime()).append(',').append(r.exitTime()).append(',').append(r.symbol())
                    .append(',').append(r.direction()).append(',').append(r.result())
                    .append(',').append(r.rMultiple()).append('\n');
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(sb.toString());
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
     * HTS trade lifecycle (E-1). {@code GET /api/hts/trades?status=OPEN&limit=100}
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
        int capped = Math.min(Math.max(limit, 1), 500);
        if (status != null && !status.isBlank()) {
            return htsTrades.findByStatusOrderByIdDesc(status.toUpperCase()).stream().limit(capped).toList();
        }
        return htsTrades.findAllByOrderByIdDesc(PageRequest.of(0, capped));
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

    private static boolean denied(Authentication authentication) {
        AppUser user = CurrentUser.of(authentication);
        return user != null && !user.canSeeBook(Books.HTS);
    }
}
