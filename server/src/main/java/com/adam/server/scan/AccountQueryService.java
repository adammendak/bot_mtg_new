package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Position;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.web.dto.AccountView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccountQueryService {

    private static final Logger log = LoggerFactory.getLogger(AccountQueryService.class);

    private final BrokerBooks books;
    private final RiskPolicy risk;

    public AccountQueryService(BrokerBooks books, RiskPolicy risk) {
        this.books = books;
        this.risk = risk;
    }

    public List<AccountView> list() {
        return List.of(view(books.demo()), view(books.live()), view(books.glowne()));
    }

    public AccountView view(BrokerClient client) {
        boolean live = "live".equals(client.book());
        if (!client.configured()) {
            return disconnected(client, switch (client.book()) {
                case "live" -> "LIVE not configured (CAPITAL_LIVE_API_KEY / CAPITAL_LIVE_EMAIL / CAPITAL_LIVE_PASSWORD)";
                case "glowne" -> "GLOWNE not configured (CAPITAL_GLOWNE_API_KEY / CAPITAL_GLOWNE_EMAIL / CAPITAL_GLOWNE_PASSWORD)";
                default -> "DEMO not configured (CAPITAL_API_KEY / CAPITAL_EMAIL / CAPITAL_API_PASSWORD)";
            });
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
                trySelect(client, a.id());
                return connected(client, a);
            }
            // "glowne" (main) and demo: pick the first non-Fintokei account
            Account picked = risk.pickDemoAccount(accounts);
            if (picked == null) {
                return disconnected(client, "no account available");
            }
            trySelect(client, picked.id());
            return connected(client, picked);
        } catch (BrokerException e) {
            log.warn("Account query failed for {} book ({}): {}", client.book(), client.id(), publicMessage(e), e);
            return disconnected(client, publicMessage(e));
        } catch (Exception e) {
            log.warn("Account query failed for {} book ({}): {}", client.book(), client.id(), publicMessage(e), e);
            return disconnected(client, publicMessage(e));
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
            log.warn("Open positions failed for {} book ({}): {}", client.book(), client.id(), publicMessage(e), e);
            return List.of();
        }
    }

    public Map<String, List<Position>> positionsByBook() {
        Map<String, List<Position>> out = new LinkedHashMap<>();
        out.put("demo", positions("demo"));
        out.put("live", positions("live"));
        out.put("glowne", positions("glowne"));
        return out;
    }

    private void trySelect(BrokerClient client, String accountId) {
        try {
            client.selectAccount(accountId);
        } catch (Exception e) {
            log.warn("Account select failed for {} book ({}): {}", client.book(), client.id(), publicMessage(e), e);
        }
    }

    static String publicMessage(Throwable e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        if (e.getCause() != null && e.getCause().getMessage() != null && !e.getCause().getMessage().isBlank()) {
            return e.getCause().getMessage();
        }
        return e.getClass().getSimpleName();
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
