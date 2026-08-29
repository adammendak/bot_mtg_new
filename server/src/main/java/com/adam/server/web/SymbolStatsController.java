package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.scan.SymbolStatsService;
import com.adam.server.web.dto.SymbolStats;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-symbol performance statistics (win rate, expectancy, profit factor) per
 * book, computed from the broker's closed-trade transactions.
 */
@RestController
public class SymbolStatsController {

    private final SymbolStatsService stats;

    public SymbolStatsController(SymbolStatsService stats) {
        this.stats = stats;
    }

    @GetMapping(value = "/api/symbol-stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SymbolStats> stats(
            @RequestParam(name = "book", defaultValue = "demo") String book,
            @RequestParam(name = "days", defaultValue = "0") int days,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return List.of();
        }
        return stats.stats(book, days);
    }
}
