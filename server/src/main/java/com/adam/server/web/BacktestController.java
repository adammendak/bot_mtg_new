package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.scan.BacktestService;
import com.adam.server.web.dto.BacktestResult;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Backtesting endpoint: replays the SDD engine over historical candles and
 * reports win rate / expectancy / profit factor per symbol.
 */
@RestController
public class BacktestController {

    private final BacktestService backtest;

    public BacktestController(BacktestService backtest) {
        this.backtest = backtest;
    }

    @GetMapping(value = "/api/backtest", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<BacktestResult> backtest(
            @RequestParam(name = "book", defaultValue = "demo") String book,
            @RequestParam(name = "days", defaultValue = "90") int days,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return List.of();
        }
        return backtest.run(book, days);
    }
}
