package com.adam.server.scan;

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
import com.adam.server.sdd.SddScan;
import com.adam.server.sdd.SddSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final BrokerClient broker;
    private final AppProperties properties;
    private final ScanStore store;
    private final SignalWebhookPublisher webhooks;
    private final NewsBlackout news;
    private final RiskPolicy risk;
    private final ExecutionGate execution;
    private final Clock clock;
    private final SddEngine engine;

    public ScanService(
            BrokerClient broker,
            AppProperties properties,
            ScanStore store,
            SignalWebhookPublisher webhooks,
            NewsBlackout news,
            RiskPolicy risk,
            ExecutionGate execution,
            Clock clock
    ) {
        this.broker = broker;
        this.properties = properties;
        this.store = store;
        this.webhooks = webhooks;
        this.news = news;
        this.risk = risk;
        this.execution = execution;
        this.clock = clock;
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
        String halt = null;
        List<Position> open = List.of();
        Account account = null;
        try {
            broker.login();
            List<Account> accounts = broker.accounts();
            account = pickAccount(accounts);
            open = broker.openPositions();
            halt = risk.dayHalt(account == null ? 0 : account.profitLoss());
            execution.manageOpen(open);
            for (SddSymbol symbol : SddSymbol.universe()) {
                String epic = symbol.epic(properties);
                SddScan result = scanSymbol(symbol, epic, now);
                symbols.add(result);
                if (result.fullStack() || result.flip()) {
                    webhooks.publish(result);
                    log.info("{}", result.reason());
                    execution.maybeEnter(result, open, account, blackout, halt);
                }
            }
        } catch (BrokerException e) {
            error = e.getMessage();
            log.warn("Scan aborted: {}", e.getMessage());
        } catch (Exception e) {
            error = "scan failed";
            log.warn("Scan failed", e);
        }
        boolean quiet = symbols.stream().noneMatch(s -> s.flip() || s.fullStack());
        if (!quiet && error == null) {
            log.info("SDD scan complete, {} symbols", symbols.size());
        }
        ScanSnapshot snapshot = new ScanSnapshot(
                now,
                broker.id(),
                broker.displayName(),
                properties.isExecutionEnabled(),
                blackout,
                halt,
                List.copyOf(symbols),
                error
        );
        store.save(snapshot);
        return snapshot;
    }

    private SddScan scanSymbol(SddSymbol symbol, String epic, Instant now) {
        Instant fromM15 = now.minus(Duration.ofDays(10));
        Instant fromH1 = now.minus(Duration.ofDays(40));
        Instant fromH4 = now.minus(Duration.ofDays(80));
        List<Candle> m15 = broker.candles(epic, Resolution.M15, fromM15, now, 1000);
        List<Candle> h1 = broker.candles(epic, Resolution.H1, fromH1, now, 500);
        List<Candle> h4 = broker.candles(epic, Resolution.H4, fromH4, now, 300);
        return engine.evaluate(symbol, epic, m15, h1, h4, now);
    }

    private Account pickAccount(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        if (properties.getCapital().isLive()) {
            for (Account a : accounts) {
                if (properties.getLiveAccountName().equals(a.name())) {
                    return a;
                }
            }
        }
        for (Account a : accounts) {
            if (a.preferred()) {
                return a;
            }
        }
        return accounts.getFirst();
    }
}
