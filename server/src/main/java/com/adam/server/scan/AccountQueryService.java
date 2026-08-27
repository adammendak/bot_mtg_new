package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Position;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.web.dto.AccountView;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccountQueryService {

    private final BrokerBooks books;
    private final RiskPolicy risk;

    public AccountQueryService(BrokerBooks books, RiskPolicy risk) {
        this.books = books;
        this.risk = risk;
    }

    public List<AccountView> list() {
        return List.of(view(books.demo()), view(books.live()));
    }

    public AccountView view(BrokerClient client) {
        boolean live = "live".equals(client.book());
        if (!client.configured()) {
            return disconnected(client, live
                    ? "LIVE not configured (CAPITAL_LIVE_API_KEY / CAPITAL_LIVE_EMAIL / CAPITAL_LIVE_PASSWORD)"
                    : "DEMO not configured (CAPITAL_API_KEY / CAPITAL_EMAIL / CAPITAL_API_PASSWORD)");
        }
        try {
            if (!client.isSessionOpen()) {
                client.login();
            }
            List<Account> accounts = client.accounts();
            if (live) {
                RiskPolicy.LivePick pick = risk.pickLiveAccount(accounts);
                if (!pick.visible()) {
                    return new AccountView(
                            client.book(),
                            client.id(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            true,
                            pick.hideReason()
                    );
                }
                Account a = pick.account();
                client.selectAccount(a.id());
                return connected(client, a);
            }
            Account demo = risk.pickDemoAccount(accounts);
            if (demo == null) {
                return disconnected(client, "no DEMO account");
            }
            client.selectAccount(demo.id());
            return connected(client, demo);
        } catch (BrokerException e) {
            return disconnected(client, e.getMessage());
        } catch (Exception e) {
            return disconnected(client, live ? "LIVE unavailable" : "DEMO unavailable");
        }
    }

    public List<Position> positions(String book) {
        BrokerClient client = books.forBook(book);
        AccountView snapshot = view(client);
        if (!snapshot.connected() || snapshot.equity() == null) {
            return List.of();
        }
        try {
            return client.openPositions();
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, List<Position>> positionsByBook() {
        Map<String, List<Position>> out = new LinkedHashMap<>();
        out.put("demo", positions("demo"));
        out.put("live", positions("live"));
        return out;
    }

    private static AccountView connected(BrokerClient client, Account a) {
        return new AccountView(
                client.book(),
                client.id(),
                a.name(),
                a.balance(),
                a.available(),
                a.profitLoss(),
                a.currency(),
                true,
                null
        );
    }

    private static AccountView disconnected(BrokerClient client, String error) {
        return new AccountView(
                client.book(),
                client.id(),
                null,
                null,
                null,
                null,
                null,
                false,
                error
        );
    }
}
