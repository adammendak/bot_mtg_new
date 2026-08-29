package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.broker.Resolution;
import com.adam.server.hts.HtsBacktestService;
import com.adam.server.sdd.Adx;
import com.adam.server.web.dto.SwingTradeRow;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTS ("wstęgi") strategy backtest — read API. Admin only until the {@code hts}
 * book exists.
 */
@RestController
public class HtsController {

    private final HtsBacktestService backtest;

    public HtsController(HtsBacktestService backtest) {
        this.backtest = backtest;
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
                skipConsolidation, pivotTargets, maxNames, stopBuf, adxPermit, runnerLock);
        List<SwingTradeRow> rows = backtest.run(p);
        StringBuilder sb = new StringBuilder("entry_time,exit_time,symbol,direction,result,r_multiple\n");
        for (SwingTradeRow r : rows) {
            sb.append(r.entryTime()).append(',').append(r.exitTime()).append(',').append(r.symbol())
                    .append(',').append(r.direction()).append(',').append(r.result())
                    .append(',').append(r.rMultiple()).append('\n');
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(sb.toString());
    }
}
