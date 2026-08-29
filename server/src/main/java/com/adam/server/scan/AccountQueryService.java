package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.Books;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.web.dto.AccountView;
import com.adam.server.web.dto.OverviewView;
import com.adam.server.web.dto.PositionRiskView;
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
    private final AppProperties properties;

    public AccountQueryService(BrokerBooks books, RiskPolicy risk, AppProperties properties) {
        this.books = books;
        this.risk = risk;
        this.properties = properties;
    }

    public List<AccountView> list() {
        return List.of(view(books.demo()), view(books.live()), view(books.glowne()), view(books.swing()));
    }

    /** All books in one row each: account metrics + book kind + strategy + position tally. */
    public List<OverviewView> overview() {
        return List.of(
                overview(books.demo()), overview(books.live()),
                overview(books.glowne()), overview(books.swing()));
    }

    /** Overview filtered to the books the caller may see (non-admin users). */
    public List<OverviewView> overviewFor(com.adam.server.auth.AppUser user) {
        return overview().stream()
                .filter(o -> user == null || user.canSeeBook(o.id()))
                .toList();
    }

    private OverviewView overview(BrokerClient client) {
        AccountView v = view(client);
        int count = 0;
        double positionsPnl = 0.0;
        double maxLossPln = 0.0;
        int withoutStop = 0;
        String riskCurrency = v.currency();
        double[] exposure = new double[]{0.0, 0.0};
        double halt = Books.LIVE.equals(client.book()) ? properties.getLiveHaltPln() : properties.getHaltPln();
        double hardHalt = properties.getHardHaltPln();
        Double remainingToHalt = v.dayPnl() == null ? null : v.dayPnl() - halt;
        if (v.connected() && v.equity() != null) {
            try {
                List<Position> open = client.openPositions();
                for (Position p : open) {
                    count++;
                    positionsPnl += p.unrealizedPnl();
                    if (p.stopLevel() == null) {
                        withoutStop++;
                    } else {
                        // Worst case if this stop is hit: entry -> stop, signed by direction.
                        double distance = Direction.BUY == p.direction()
                                ? p.level() - p.stopLevel()
                                : p.stopLevel() - p.level();
                        if (distance > 0) {
                            maxLossPln += distance * p.size();
                        }
                        if (p.currency() != null && !p.currency().isBlank()) {
                            riskCurrency = p.currency();
                        }
                    }
                }
                exposure = RiskExposure.compute(open);
            } catch (Exception e) {
                log.warn("Open positions failed for {} book ({}): {}", client.book(), client.id(), publicMessage(e), e);
            }
        }
        return new OverviewView(
                v.id(),
                v.broker(),
                kindOf(v.id()),
                displayNameOf(client),
                v.accountName(),
                strategyOf(v.id()),
                properties.isExecutionEnabled(),
                v.equity(),
                v.available(),
                v.dayPnl(),
                v.currency(),
                v.connected(),
                v.error(),
                count,
                count == 0 ? 0.0 : positionsPnl,
                maxLossPln,
                withoutStop,
                riskCurrency,
                exposure[0],
                exposure[1],
                halt,
                hardHalt,
                remainingToHalt
        );
    }

    /** DEMO / LIVE / MAIN — derived from the book id so the UI can badge every row. */
    static String kindOf(String book) {
        return switch (book) {
            case "live" -> "LIVE";
            case "glowne" -> "MAIN";
            case "swing" -> "SWING";
            default -> "DEMO";
        };
    }

    /** Human label: broker + environment, with the main book spelled out. */
    private static String displayNameOf(BrokerClient client) {
        String base = client.displayName();
        if (Books.GLOWNE.equals(client.book())) {
            return "Główne (main)";
        }
        return base;
    }

    /** The strategy attached to a book. */
    static String strategyOf(String book) {
        return Books.SWING.equals(book) ? "SDD-SWING" : "SDD-M15";
    }

    public AccountView view(BrokerClient client) {
        boolean live = Books.LIVE.equals(client.book());
        if (!client.configured()) {
            return disconnected(client, switch (client.book()) {
                case "live" -> "LIVE not configured (CAPITAL_LIVE_API_KEY / CAPITAL_LIVE_EMAIL / CAPITAL_LIVE_PASSWORD)";
                case "glowne" -> "GLOWNE not configured (CAPITAL_GLOWNE_API_KEY / CAPITAL_GLOWNE_EMAIL / CAPITAL_GLOWNE_PASSWORD)";
                case "swing" -> "SWING not configured (CAPITAL_SWING_API_KEY / CAPITAL_SWING_EMAIL / CAPITAL_SWING_PASSWORD)";
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
            // glowne / swing target their own named accounts; demo picks preferred/first.
            Account picked = Books.GLOWNE.equals(client.book())
                    ? risk.pickGlowneAccount(accounts)
                    : Books.SWING.equals(client.book())
                    ? risk.pickSwingAccount(accounts)
                    : risk.pickDemoAccount(accounts);
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
        for (String book : Books.ALL) {
            out.put(book, positions(book));
        }
        return out;
    }

    /** Positions grouped by book, filtered to what the caller may see. */
    public Map<String, List<Position>> positionsByBookFor(com.adam.server.auth.AppUser user) {
        Map<String, List<Position>> out = new LinkedHashMap<>();
        for (String book : Books.ALL) {
            if (user != null && !user.canSeeBook(book)) {
                continue;
            }
            out.put(book, positions(book));
        }
        return out;
    }

    /** Open positions with per-position cash risk (1R in currency). */
    public List<PositionRiskView> positionsWithRisk(String book) {
        return positions(book).stream()
                .map(AccountQueryService::withRisk)
                .toList();
    }

    public Map<String, List<PositionRiskView>> positionsWithRiskByBook() {
        Map<String, List<PositionRiskView>> out = new LinkedHashMap<>();
        for (String book : Books.ALL) {
            out.put(book, positionsWithRisk(book));
        }
        return out;
    }

    /** Risk view grouped by book, filtered to what the caller may see. */
    public Map<String, List<PositionRiskView>> positionsWithRiskByBookFor(com.adam.server.auth.AppUser user) {
        Map<String, List<PositionRiskView>> out = new LinkedHashMap<>();
        for (String book : Books.ALL) {
            if (user != null && !user.canSeeBook(book)) {
                continue;
            }
            out.put(book, positionsWithRisk(book));
        }
        return out;
    }

    private static PositionRiskView withRisk(Position p) {
        return new PositionRiskView(
                p.dealId(),
                p.epic(),
                p.direction(),
                p.size(),
                p.level(),
                p.stopLevel(),
                p.unrealizedPnl(),
                p.currency(),
                PositionRiskView.riskOf(p.direction(), p.level(), p.stopLevel(), p.size())
        );
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
