package com.adam.server.ladder;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Candle;
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
import java.time.Duration;
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
        final boolean variantB;
        final int trendSma;
        double startEquity;
        LocalDate lastOpenDay;
        Instant lastOrderAt = Instant.EPOCH;
        boolean notConfiguredLogged;
        Boolean trendOk;
        LocalDate trendDay;

        Bot(Params params, BrokerClient broker, String accountName, String host, double startEquity,
            boolean variantB, int trendSma) {
            this.params = params;
            this.broker = broker;
            this.accountName = accountName;
            this.host = host;
            this.startEquity = startEquity;
            this.variantB = variantB;
            this.trendSma = trendSma;
        }
    }

    private final List<Bot> bots = new ArrayList<>();
    private final RiskPolicy risk;
    private final boolean enabled;
    private final boolean enabledB;
    private final double failsafeDropPct;
    private final Clock clock;

    @Autowired
    public LadderBot(
            @Qualifier("ladderGerBroker") BrokerClient gerBroker,
            @Qualifier("ladderSpBroker") BrokerClient spBroker,
            @Qualifier("ladderGer2Broker") BrokerClient ger2Broker,
            @Qualifier("ladderSp2Broker") BrokerClient sp2Broker,
            AppProperties properties,
            RiskPolicy risk,
            @Value("${app.ladder.enabled:false}") boolean enabled,
            @Value("${app.ladder-b.enabled:false}") boolean enabledB,
            @Value("${app.ladder-b.trend-sma:50}") int trendSma,
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
            @Value("${app.ladder.sp.start-equity:0}") double spStart,
            @Value("${app.ladder-b.ger.start-equity:0}") double ger2Start,
            @Value("${app.ladder-b.sp.start-equity:0}") double sp2Start) {
        this(gerBroker, spBroker, ger2Broker, sp2Broker, properties, risk, enabled, enabledB, trendSma, failsafeDropPct,
                new Params("GER40", "DE40", gerTp, gerStep, gerMax, gerGain, LocalTime.of(7, 5), 15),
                new Params("SP500", "US500", spTp, spStep, spMax, spGain, LocalTime.of(13, 35), 15),
                gerStart, spStart, ger2Start, sp2Start, Clock.systemUTC());
    }

    /** Variant B runs the same parameters as A (only the trend filter differs), so B reuses A's {@link Params}. */
    LadderBot(BrokerClient gerBroker, BrokerClient spBroker, BrokerClient ger2Broker, BrokerClient sp2Broker,
              AppProperties properties, RiskPolicy risk, boolean enabled, boolean enabledB, int trendSma,
              double failsafeDropPct, Params ger, Params sp, double gerStart, double spStart, double ger2Start,
              double sp2Start, Clock clock) {
        this.risk = risk;
        this.enabled = enabled;
        this.enabledB = enabledB;
        this.failsafeDropPct = failsafeDropPct;
        this.clock = clock;
        bots.add(new Bot(ger, gerBroker, properties.getLadderGerAccountName(),
                properties.getCapital().getLadderGer().getHost(), gerStart, false, 0));
        bots.add(new Bot(sp, spBroker, properties.getLadderSpAccountName(),
                properties.getCapital().getLadderSp().getHost(), spStart, false, 0));
        bots.add(new Bot(renamed(ger, "-B"), ger2Broker, properties.getLadderGer2AccountName(),
                properties.getCapital().getLadderGer2().getHost(), ger2Start, true, trendSma));
        bots.add(new Bot(renamed(sp, "-B"), sp2Broker, properties.getLadderSp2AccountName(),
                properties.getCapital().getLadderSp2().getHost(), sp2Start, true, trendSma));
    }

    private static Params renamed(Params p, String suffix) {
        return new Params(p.name() + suffix, p.epic(), p.tpPct(), p.stepPct(), p.maxPositions(), p.gainPct(),
                p.openUtc(), p.openWindowMin());
    }

    @Scheduled(cron = "${app.ladder.cron:5 * * * * *}", zone = "UTC")
    public void tick() {
        if (!enabled && !enabledB) {
            return;
        }
        for (Bot b : bots) {
            if (b.variantB ? !enabledB : !enabled) {
                continue;
            }
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
        List<Account> accounts = c.accounts();
        Account acc = risk.pickTvAccount(accounts, b.accountName);
        if (acc == null || acc.id() == null) {
            log.warn("LADDER[{}] sub-account '{}' not found on this login; accounts seen: {}", p.name(),
                    b.accountName, accounts == null ? List.of() : accounts.stream().map(Account::name).toList());
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
        Boolean trendOk = trendOk(b, now);
        if (trendOk == null) {
            return;       // the trend could not be determined yet: no entries until it can (retried next tick)
        }
        Decision d = LadderLogic.decide(p, entries, px.bid(), now, b.lastOpenDay, equity, b.startEquity, failsafeDropPct,
                trendOk);
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

    /**
     * Variant B filter, once per UTC day: is the previous completed daily close above the mean of the last
     * {@code trendSma} completed daily closes? Variant A (trendSma 0) always passes. {@code null} = not determinable.
     */
    private Boolean trendOk(Bot b, Instant now) {
        if (b.trendSma <= 0) {
            return Boolean.TRUE;
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (b.trendOk != null && today.equals(b.trendDay)) {
            return b.trendOk;
        }
        List<Candle> d1 = b.broker.candles(b.params.epic(), Resolution.D1, now.minus(Duration.ofDays(150)), now, 150);
        Boolean ok = LadderTrend.allow(d1, now, b.trendSma);
        if (ok == null) {
            log.warn("LADDER[{}] trend filter: fewer than {} completed daily candles for {} — no entries", b.params.name(),
                    b.trendSma, b.params.epic());
            return null;
        }
        b.trendOk = ok;
        b.trendDay = today;
        log.info("LADDER[{}] trend filter SMA{} for {}: {}", b.params.name(), b.trendSma, today,
                ok ? "entries ALLOWED" : "entries BLOCKED (previous daily close is below the average)");
        return ok;
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
