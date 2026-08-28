package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.NewsBlackout;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.sdd.SddEngine;
import com.adam.server.persistence.DurableScanWriter;
import com.adam.server.sdd.SddScan;
import com.adam.server.sdd.SddSymbol;
import com.adam.server.web.dto.AccountView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final BrokerBooks books;
    private final AppProperties properties;
    private final ScanStore store;
    private final SignalWebhookPublisher webhooks;
    private final NewsBlackout news;
    private final RiskPolicy risk;
    private final ExecutionGate execution;
    private final AccountQueryService accounts;
    private final Clock clock;
    private final DurableScanWriter durable;
    private final SddEngine engine;

    public ScanService(
            BrokerBooks books,
            AppProperties properties,
            ScanStore store,
            SignalWebhookPublisher webhooks,
            NewsBlackout news,
            RiskPolicy risk,
            ExecutionGate execution,
            AccountQueryService accounts,
            Clock clock,
            ObjectProvider<DurableScanWriter> durable
    ) {
        this.books = books;
        this.properties = properties;
        this.store = store;
        this.webhooks = webhooks;
        this.news = news;
        this.risk = risk;
        this.execution = execution;
        this.accounts = accounts;
        this.clock = clock;
        this.durable = durable == null ? null : durable.getIfAvailable();
        this.engine = new SddEngine(ZoneId.of(properties.getTimezone()));
    }

    public ScanSnapshot last() {
        return store.last();
    }

    public List<SddScan> signals() {
        return store.signals();
    }

    public ScanSnapshot scan() {
        Instant now = clock.instant();
        boolean blackout = news.blocked(now);
        List<SddScan> symbols = new ArrayList<>();
        String error = null;
        BrokerClient market = books.marketData();
        try {
            if (!market.configured()) {
                throw new BrokerException("no market-data broker configured");
            }
            market.login();
            for (SddSymbol symbol : SddSymbol.universe()) {
                String epic = symbol.epic(properties);
                SddScan result = scanSymbol(market, symbol, epic, now);
                symbols.add(result);
                if (result.fullStack() || result.flip()) {
                    try {
                        webhooks.publish(result);
                    } catch (Exception e) {
                        log.warn("Webhook publish failed: {}", e.getClass().getSimpleName());
                    }
                    log.info("{}", result.reason());
                }
            }
        } catch (BrokerException e) {
            error = AccountQueryService.publicMessage(e);
            log.warn("Scan aborted: {}", error);
        } catch (Exception e) {
            error = AccountQueryService.publicMessage(e);
            log.warn("Scan failed: {}", e.getClass().getSimpleName());
        }

        AccountView demoView = accounts.view(books.demo());
        AccountView liveView = accounts.view(books.live());
        AccountView glowneView = accounts.view(books.glowne());
        String demoHalt = haltFor(demoView);
        String liveHalt = haltFor(liveView);
        String glowneHalt = haltFor(glowneView);

        if (properties.isExecutionEnabled() && !symbols.isEmpty()) {
            List<Position> demoOpen = accounts.positions("demo");
            execution.manageOpen(demoOpen);
            Account demoAccount = demoAccountFrom(demoView);
            for (SddScan result : symbols) {
                if (result.fullStack() || result.flip()) {
                    execution.maybeEnter(result, demoOpen, demoAccount, blackout, demoHalt);
                }
            }
        }

        boolean quiet = symbols.stream().noneMatch(s -> s.flip() || s.fullStack());
        if (!quiet && error == null) {
            log.info("SDD scan complete, {} symbols", symbols.size());
        }
        List<ScanSnapshot.BookScan> bookScans = List.of(
                new ScanSnapshot.BookScan(demoView.id(), demoView.broker(), demoHalt, demoView.error()),
                new ScanSnapshot.BookScan(liveView.id(), liveView.broker(), liveHalt, liveView.error()),
                new ScanSnapshot.BookScan(glowneView.id(), glowneView.broker(), glowneHalt, glowneView.error())
        );
        ScanSnapshot snapshot = new ScanSnapshot(
                now,
                market.id(),
                market.displayName(),
                properties.isExecutionEnabled(),
                blackout,
                List.copyOf(symbols),
                error,
                bookScans,
                webhooks.lastWebhookAt(),
                webhooks.lastWebhookError()
        );
        try {
            webhooks.onScanFinished(snapshot);
        } catch (Exception e) {
            log.warn("Scan webhook follow-up failed: {}", e.getClass().getSimpleName());
        }
        snapshot = new ScanSnapshot(
                snapshot.scannedAt(),
                snapshot.brokerId(),
                snapshot.brokerName(),
                snapshot.executionEnabled(),
                snapshot.newsBlackout(),
                snapshot.symbols(),
                snapshot.error(),
                snapshot.books(),
                webhooks.lastWebhookAt(),
                webhooks.lastWebhookError()
        );
        store.save(snapshot);
        if (durable != null) {
            durable.write(snapshot, demoView, liveView);
        }
        return snapshot;
    }

    private SddScan scanSymbol(BrokerClient market, SddSymbol symbol, String epic, Instant now) {
        try {
            Instant fromM15 = now.minus(Duration.ofDays(10));
            Instant fromH1 = now.minus(Duration.ofDays(40));
            Instant fromH4 = now.minus(Duration.ofDays(80));
            List<Candle> m15 = market.candles(epic, Resolution.M15, fromM15, now, 1000);
            List<Candle> h1 = market.candles(epic, Resolution.H1, fromH1, now, 500);
            List<Candle> h4 = market.candles(epic, Resolution.H4, fromH4, now, 300);
            return engine.evaluate(symbol, epic, m15, h1, h4, now);
        } catch (RuntimeException e) {
            if (isUnknownEpic(e)) {
                log.warn("Skipping unknown Capital.com epic {} ({})", epic, symbol.code());
                return engine.skippedEpic(symbol, epic, now, "epic not found: " + epic);
            }
            String reason = shorten(AccountQueryService.publicMessage(e));
            log.warn("Skipping {} after broker error: {}", symbol.code(), e.getClass().getSimpleName());
            return engine.skippedBroker(symbol, epic, now, reason);
        }
    }

    static boolean isUnknownEpic(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException rest) {
                if (rest.getStatusCode().value() == 404) {
                    return true;
                }
                String body = rest.getResponseBodyAsString();
                if (body != null && body.contains("not-found.epic")) {
                    return true;
                }
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("error.not-found.epic")
                    || msg.contains("epic not found")
                    || msg.contains("not-found.epic"))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "broker error";
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private String haltFor(AccountView view) {
        if (view.dayPnl() == null) {
            return null;
        }
        return risk.dayHalt(view.dayPnl());
    }

    private static Account demoAccountFrom(AccountView view) {
        if (!view.connected() || view.equity() == null) {
            return null;
        }
        return new Account(
                "demo",
                view.accountName() == null ? "demo" : view.accountName(),
                view.currency() == null ? "" : view.currency(),
                view.equity(),
                view.available() == null ? 0 : view.available(),
                view.dayPnl() == null ? 0 : view.dayPnl(),
                true
        );
    }
}
