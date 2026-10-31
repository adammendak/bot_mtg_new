package com.adam.server.ladder;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketPrice;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.ladder.LadderLogic.Decision;
import com.adam.server.ladder.LadderLogic.Params;
import com.adam.server.sdd.RiskPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Two independent long-ladder bots on two dedicated Capital.com DEMO sub-accounts: one trades only GER40 (DE40), the
 * other only SP500 (US500). The broker is the source of truth (the sub-account holds nothing but this ladder), so the
 * bot is stateless apart from a few in-memory guards. Every position carries its own take-profit on the broker side;
 * there is no stop-loss by design (see docs/DAILY-SCALP-LONGONLY.md). OFF unless {@code app.ladder.enabled=true}.
 */
@Service
public class LadderBot {

    private static final Logger log = LoggerFactory.getLogger(LadderBot.class);
    private static final long ORDER_COOLDOWN_SECONDS = 120;

    /** One bot = one broker book + one instrument; mutable guards are in-memory only. */
    static final class Bot {
        final Params params;
        final BrokerClient broker;
        final String accountName;
        final String host;
        double startEquity;
        LocalDate lastOpenDay;
        Instant lastOrderAt = Instant.EPOCH;
        boolean notConfiguredLogged;

        Bot(Params params, BrokerClient broker, String accountName, String host, double startEquity) {
            this.params = params;
            this.broker = broker;
            this.accountName = accountName;
            this.host = host;
            this.startEquity = startEquity;
        }
    }

    private final List<Bot> bots = new ArrayList<>();
    private final RiskPolicy risk;
    private final boolean enabled;
    private final double failsafeDropPct;
    private final Clock clock;

    @Autowired
    public LadderBot(
            @Qualifier("ladderGerBroker") BrokerClient gerBroker,
            @Qualifier("ladderSpBroker") BrokerClient spBroker,
            AppProperties properties,
            RiskPolicy risk,
            @Value("${app.ladder.enabled:false}") boolean enabled,
            @Value("${app.ladder.failsafe-drop-pct:25}") double failsafeDropPct,
            @Value("${app.ladder.ger.tp-pct:0.5}") double gerTp,
            @Value("${app.ladder.ger.step-pct:0.5}") double gerStep,
            @Value("${app.ladder.ger.max-positions:10}") int gerMax,
            @Value("${app.ladder.ger.gain-pct:0.2}") double gerGain,
            @Value("${app.ladder.ger.start-equity:0}") double gerStart,
            @Value("${app.ladder.sp.tp-pct:0.75}") double spTp,
            @Value("${app.ladder.sp.step-pct:0.5}") double spStep,
            @Value("${app.ladder.sp.max-positions:10}") int spMax,
            @Value("${app.ladder.sp.gain-pct:0.2}") double spGain,
            @Value("${app.ladder.sp.start-equity:0}") double spStart) {
        this(gerBroker, spBroker, properties, risk, enabled, failsafeDropPct,
                new Params("GER40", "DE40", gerTp, gerStep, gerMax, gerGain, LocalTime.of(7, 5), 15),
                new Params("SP500", "US500", spTp, spStep, spMax, spGain, LocalTime.of(13, 35), 15),
                gerStart, spStart, Clock.systemUTC());
    }

    LadderBot(BrokerClient gerBroker, BrokerClient spBroker, AppProperties properties, RiskPolicy risk,
              boolean enabled, double failsafeDropPct, Params ger, Params sp, double gerStart, double spStart,
              Clock clock) {
        this.risk = risk;
        this.enabled = enabled;
        this.failsafeDropPct = failsafeDropPct;
        this.clock = clock;
        bots.add(new Bot(ger, gerBroker, properties.getLadderGerAccountName(),
                properties.getCapital().getLadderGer().getHost(), gerStart));
        bots.add(new Bot(sp, spBroker, properties.getLadderSpAccountName(),
                properties.getCapital().getLadderSp().getHost(), spStart));
    }

    @Scheduled(cron = "${app.ladder.cron:5 * * * * *}", zone = "UTC")
    public void tick() {
        if (!enabled) {
            return;
        }
        for (Bot b : bots) {
            try {
                runOne(b);
            } catch (Exception e) {
                log.warn("LADDER[{}] tick failed: {} {}", b.params.name(), e.getClass().getSimpleName(),
                        e.getMessage() == null ? "" : e.getMessage().substring(0, Math.min(160, e.getMessage().length())));
            }
        }
    }

    void runOne(Bot b) {
        Params p = b.params;
        BrokerClient c = b.broker;
        if (!c.configured()) {
            if (!b.notConfiguredLogged) {
                log.info("LADDER[{}] broker credentials are not set — bot idle", p.name());
                b.notConfiguredLogged = true;
            }
            return;
        }
        // Demo only, ever: refuse any host that is not the Capital.com demo API.
        if (b.host == null || !b.host.contains("demo-api-capital")) {
            log.error("LADDER[{}] refusing to trade: host '{}' is not the Capital.com demo API", p.name(), b.host);
            return;
        }
        if (!c.isSessionOpen()) {
            c.login();
        }
        Account acc = risk.pickTvAccount(c.accounts(), b.accountName);
        if (acc == null || acc.id() == null) {
            log.warn("LADDER[{}] sub-account '{}' not found on this login", p.name(), b.accountName);
            return;
        }
        c.selectAccount(acc.id());

        MarketRules rules = c.marketRules(p.epic());
        if (!rules.tradeable()) {
            return;
        }
        MarketPrice px = c.marketPrice(p.epic());
        if (px == null || px.bid() <= 0 || px.ask() <= 0) {
            return;
        }
        List<Double> entries = new ArrayList<>();
        for (Position pos : c.openPositions()) {
            if (p.epic().equalsIgnoreCase(pos.epic()) && pos.direction() == Direction.BUY) {
                entries.add(pos.level());
            }
        }
        double equity = acc.balance() + acc.profitLoss();
        if (b.startEquity <= 0) {
            b.startEquity = acc.balance();
            log.info("LADDER[{}] start equity for the failsafe = {} (set LADDER_*_START_EQUITY to pin it across restarts)",
                    p.name(), Math.round(b.startEquity));
        }
        Instant now = clock.instant();
        Decision d = LadderLogic.decide(p, entries, px.bid(), now, b.lastOpenDay, equity, b.startEquity, failsafeDropPct);
        if (!d.buy()) {
            if (d.reason().startsWith("FAILSAFE")) {
                log.warn("LADDER[{}] {}", p.name(), d.reason());
            }
            return;
        }
        if (now.isBefore(b.lastOrderAt.plusSeconds(ORDER_COOLDOWN_SECONDS))) {
            return;    // the previous order may not be visible in openPositions() yet
        }
        placeBuy(b, acc, rules, px, equity, d, now, entries.size());
    }

    private void placeBuy(Bot b, Account acc, MarketRules rules, MarketPrice px, double equity, Decision d, Instant now,
                          int openCount) {
        Params p = b.params;
        BrokerClient c = b.broker;
        double fx = c.fxRate(rules.currency(), acc.currency());
        if (fx <= 0) {
            fx = 1.0;
        }
        double pointValueAcct = (rules.pointValue() > 0 ? rules.pointValue() : 1.0) * fx;
        double rawSize = p.notionalMultiple() * equity / (px.ask() * pointValueAcct);
        double size = rules.roundSize(rawSize);
        if (rawSize <= 0 || size <= 0 || (rules.minDealSize() > 0 && rawSize < rules.minDealSize() / 2.0)) {
            log.warn("LADDER[{}] skip: size {} is below the broker minimum {} (equity {}, n={})", p.name(), rawSize,
                    rules.minDealSize(), Math.round(equity), p.notionalMultiple());
            return;
        }
        double margin = (rules.marginFactor() > 0 ? rules.marginFactor() : 0.05) * size * px.ask() * pointValueAcct;
        if (margin * 1.2 > acc.available()) {
            log.warn("LADDER[{}] skip: margin {} exceeds the available funds {}", p.name(), Math.round(margin),
                    Math.round(acc.available()));
            return;
        }
        double tp = rules.roundPrice(px.ask() * (1.0 + p.tpPct() / 100.0));
        OrderRequest req = new OrderRequest(p.epic(), Direction.BUY, size, null, "MARKET", null, null, tp, false);
        OrderAck ack = c.placeMarketOrder(req);
        b.lastOrderAt = now;
        if ("DAILY_OPEN".equals(d.reason())) {
            b.lastOpenDay = now.atZone(ZoneOffset.UTC).toLocalDate();
        }
        String result = "SUBMITTED";
        try {
            if (ack != null && ack.dealReference() != null) {
                Confirmation conf = c.confirm(ack.dealReference());
                if (conf != null) {
                    result = conf.dealStatus() + (conf.reason() == null ? "" : " " + conf.reason());
                }
            }
        } catch (Exception e) {
            result = "UNCONFIRMED (" + e.getClass().getSimpleName() + ")";
        }
        log.info("LADDER[{}] BUY {} {} @~{} TP {} ({} open before) — {} → {}", p.name(), size, p.epic(), px.ask(), tp,
                openCount, d.reason(), result);
    }
}
