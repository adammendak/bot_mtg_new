package com.adam.server.web;

import com.adam.server.broker.model.Position;
import com.adam.server.scan.AccountQueryService;
import com.adam.server.web.dto.AccountView;
import com.adam.server.web.dto.OverviewView;
import com.adam.server.web.dto.PositionRiskView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AccountController {

    private final AccountQueryService accounts;

    public AccountController(AccountQueryService accounts) {
        this.accounts = accounts;
    }

    @GetMapping(value = "/api/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AccountView> accounts() {
        return accounts.list();
    }

    /** All accounts in one view: kind (DEMO/LIVE/MAIN), strategy, positions tally. */
    @GetMapping(value = "/api/overview", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<OverviewView> overview() {
        return accounts.overview();
    }

    @GetMapping(value = "/api/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object positions(@RequestParam(name = "account", required = false) String account) {
        if (account == null || account.isBlank()) {
            Map<String, List<Position>> both = accounts.positionsByBook();
            return both;
        }
        return accounts.positions(account);
    }

    /** Positions with per-position cash risk (1R in currency). */
    @GetMapping(value = "/api/positions/risk", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object positionsWithRisk(@RequestParam(name = "account", required = false) String account) {
        if (account == null || account.isBlank()) {
            Map<String, List<PositionRiskView>> both = accounts.positionsWithRiskByBook();
            return both;
        }
        return accounts.positionsWithRisk(account);
    }
}
