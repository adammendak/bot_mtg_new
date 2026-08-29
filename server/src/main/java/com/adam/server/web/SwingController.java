package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.broker.Books;
import com.adam.server.persistence.SwingSignalEntity;
import com.adam.server.persistence.SwingSignalRepository;
import com.adam.server.swing.SwingBacktestService;
import com.adam.server.swing.SwingScan;
import com.adam.server.swing.SwingScanService;
import com.adam.server.web.dto.BacktestResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SDD-SWING (H1) read API — for comparing the swing strategy against SDD-M15.
 * Gated on the {@code swing} book grant (adam + test have it).
 */
@RestController
public class SwingController {

    private final SwingScanService scan;
    private final SwingSignalRepository signals;
    private final SwingBacktestService backtest;

    public SwingController(SwingScanService scan, SwingSignalRepository signals, SwingBacktestService backtest) {
        this.scan = scan;
        this.signals = signals;
        this.backtest = backtest;
    }

    /** The most recent scan's signals (in memory), plus scan status. */
    @GetMapping(value = "/api/swing/last", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object last(Authentication authentication) {
        if (denied(authentication)) {
            return Map.of("error", "forbidden");
        }
        // LinkedHashMap, not Map.of — scannedAt is null before the first scan and
        // Map.of rejects null values (NPE -> 500).
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("scannedAt", scan.lastScanAt() == null ? null : scan.lastScanAt().toString());
        out.put("error", scan.lastError() == null ? "" : scan.lastError());
        out.put("signals", scan.last());
        return out;
    }

    /** Persisted swing signal history (newest first). */
    @GetMapping(value = "/api/swing/signals", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SwingSignalEntity> signals(
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
     * Replay the SDD-SWING (H1) engine over the last {@code days} (default 30,
     * max 180) and report win rate / avg R / profit factor per symbol.
     * Runs against Capital.com market data, so it only works on a deployed
     * instance with credentials.
     */
    @GetMapping(value = "/api/swing/backtest", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<BacktestResult> backtest(
            @RequestParam(name = "days", defaultValue = "30") int days,
            Authentication authentication
    ) {
        if (denied(authentication)) {
            return List.of();
        }
        return backtest.run(days);
    }

    /** Manual scan trigger (also a Scheduler backup). */
    @PostMapping(value = "/api/swing/scan", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object trigger(Authentication authentication) {
        if (denied(authentication)) {
            return Map.of("error", "forbidden");
        }
        List<SwingScan> out = scan.scan();
        return Map.of("count", out.size(), "signals", out);
    }

    private static boolean denied(Authentication authentication) {
        AppUser user = CurrentUser.of(authentication);
        return user != null && !user.canSeeBook(Books.SWING);
    }
}
