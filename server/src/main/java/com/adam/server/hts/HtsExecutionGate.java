package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Books;
import com.adam.server.broker.Direction;
import com.adam.server.config.AppProperties;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.sdd.RiskPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places HTS ("wstęgi") entries on the book that belongs to the signal's
 * {@link HtsVariant}:
 * <ul>
 *   <li>demo variants — CORE → {@code demo} ("Account m15"), SWING → {@code swing}
 *       ("Account H1"), FAST → {@code hts} ("Account m5"); gated by
 *       {@code HTS_EXECUTION_ENABLED}.</li>
 *   <li>{@code CORE_LIVE} — {@code live} book ("bot trading konto"), <b>real
 *       money</b>: 1 % of account risk, honours {@link RiskPolicy#pickLiveAccount}
 *       (equity-refuse / Fintokei), skips when open P/L is past
 *       {@code LIVE_HALT_PLN} or the size is below {@code MIN_DEAL_SIZE};
 *       gated by its own {@code HTS_LIVE_EXECUTION_ENABLED}.</li>
 * </ul>
 *
 * <p><b>One position per signal, stop only</b> — no take-profit is sent with the
 * entry. {@link HtsPositionMonitor} owns the exit: it closes half at TP1 (1:2
 * R:R), locks the runner's stop at break-even + 1R, then trails the fast band
 * and flattens the runner on a body close beyond the slow band. SDD opened two
 * tickets; HTS opens one and manages it.
 *
 * <p>Idempotency is both in memory (fast path) and in {@code hts_trades} via
 * {@link HtsTradeService#alreadyExecuted}, so a restart inside the same signal
 * bar cannot re-enter. Every fill is recorded to {@code hts_trades} and the
 * position monitor fills the outcome on close.
 */
@Component
public class HtsExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(HtsExecutionGate.class);

    private final BrokerBooks books;
    private final RiskPolicy risk;
    private final AppProperties properties;
    private final HtsTradeService trades;
    private final com.adam.server.ops.FeatureFlags flags;
    private final com.adam.server.scan.Mailer mailer;
    private final com.adam.server.ops.ErrorLog errorLog;
    private final Set<String> placed = ConcurrentHashMap.newKeySet();

    /**
     * FAST re-entry guard. FAST acts on every M5 close, so after a stop-out (or a
     * rejected order) it re-signals the same direction 5 minutes later and churns
     * the m5 sub-account. Take one entry per HTF swing: block a same-direction
     * re-entry for a symbol until the HTF direction flips, with a
     * {@link #FAST_REENTRY_COOLDOWN} floor so a persistent HTF trend still lets a
     * fresh entry through eventually. Key: {@code VARIANT|SYMBOL}.
     */
    private final Map<String, SwingEntry> lastFastEntry = new ConcurrentHashMap<>();
    private static final Duration FAST_REENTRY_COOLDOWN = Duration.ofMinutes(60);

    private record SwingEntry(Direction dir, Instant at) {
    }

    public HtsExecutionGate(
            BrokerBooks books,
            RiskPolicy risk,
            AppProperties properties,
            HtsTradeService trades,
            com.adam.server.ops.FeatureFlags flags,
            com.adam.server.scan.Mailer mailer,
            com.adam.server.ops.ErrorLog errorLog
    ) {
        this.books = books;
        this.risk = risk;
        this.properties = properties;
        this.trades = trades;
        this.flags = flags;
        this.mailer = mailer;
        this.errorLog = errorLog;
    }

    /** Best-effort: never throws to the scan. */
    public void executeSignal(HtsScan s) {
        if (s == null || s.direction() == null || s.variant() == null) {
            return;
        }
        boolean live = s.variant().live();
        String flag = live ? "hts.live-execution" : "hts.execution";
        if (!flags.enabled(flag)) {
            // A signal that is NOT executed must be loud — this is how one slips by.
            log.warn("HTS [{}] execution SKIPPED for {} {} — feature flag {} is OFF",
                    s.variant().name(), s.symbol(), s.direction(), flag);
            return;
        }
        String book = s.variant().book();
        // OKX variants carry live=false (gated by the demo hts.execution flag), so a
        // real-money OKX account (OKX_DEMO=false) would trade on the demo tier with
        // no day-P/L halt. Require an explicit opt-in for that case.
        if (Books.OKX.equalsIgnoreCase(book)
                && !properties.getOkx().isDemo()
                && !properties.getOkx().isLiveExecutionEnabled()) {
            log.warn("HTS [{}] execution SKIPPED for {} {} — OKX is in REAL-money mode (OKX_DEMO=false) "
                            + "but OKX_LIVE_EXECUTION_ENABLED is not set",
                    s.variant().name(), s.symbol(), s.direction());
            return;
        }
        String key = s.variant().name() + "|" + s.symbol() + "|" + s.direction().name() + "|"
                + (s.timestamp() == null ? 0 : s.timestamp().toEpochMilli());
        if (!placed.add(key)) {
            return; // this bar already placed
        }
        if (trades.alreadyExecuted(s)) {
            return; // persisted across a restart within the same signal bar
        }
        if (trades.hasOpenPosition(s.variant(), s.symbol()) && !trades.allowMmsAddOn(s)) {
            // One position per signal, actively managed — never stack a new entry
            // every bar while the previous one is still open. MMS may add ×1
            // once when the optional add-on flag is on and this setup is clean.
            log.info("HTS [{}] execution skipped {} {} — a position for this model/symbol is already open",
                    s.variant().name(), s.symbol(), s.direction());
            placed.remove(key);
            return;
        }
        if (s.variant() == HtsVariant.FAST && fastReentryBlocked(s)) {
            log.info("HTS [FAST] execution skipped {} {} — one entry per HTF swing; same-direction "
                    + "re-entry is on cooldown ({} min since the last entry, HTF not flipped)",
                    s.symbol(), s.direction(), FAST_REENTRY_COOLDOWN.toMinutes());
            placed.remove(key);
            return;
        }
        try {
            BrokerClient broker = books.forBook(book);
            if (broker == null || !broker.configured()) {
                log.info("HTS [{}] execution skipped {} — {} broker not configured",
                        s.variant().name(), s.symbol(), book);
                placed.remove(key);
                return;
            }
            if (!broker.isSessionOpen()) {
                broker.login();
            }
            List<Account> accounts = broker.accounts();

            Account account;
            if (live) {
                RiskPolicy.LivePick pick = risk.pickLiveAccount(accounts);
                if (pick.hideReason() != null) {
                    log.warn("HTS [{}] LIVE execution skipped {} — {}",
                            s.variant().name(), s.symbol(), pick.hideReason());
                    placed.remove(key);
                    return;
                }
                account = pick.account();
                if (account != null) {
                    double dayPnl = trades.realisedPnlSince(book, trades.startOfToday()) + account.profitLoss();
                    if (dayPnl <= properties.getLiveHaltPln()) {
                        log.warn("HTS [{}] LIVE execution skipped {} — day P/L {} past halt {}",
                                s.variant().name(), s.symbol(), dayPnl, properties.getLiveHaltPln());
                        placed.remove(key);
                        return;
                    }
                }
            } else {
                account = risk.pickForBook(book, accounts);
                // OKX runs real money (no demo), so give it the same day-P/L
                // circuit breaker CORE_LIVE has. Halt threshold is app.live-halt-pln
                // (a rough floor — OKX settles in USDT, not PLN).
                if (Books.OKX.equalsIgnoreCase(book) && account != null) {
                    double dayPnl = trades.realisedPnlSince(book, trades.startOfToday()) + account.profitLoss();
                    if (dayPnl <= properties.getLiveHaltPln()) {
                        log.warn("HTS [{}] OKX execution skipped {} — day P/L {} past halt {}",
                                s.variant().name(), s.symbol(), dayPnl, properties.getLiveHaltPln());
                        placed.remove(key);
                        return;
                    }
                }
            }
            if (account == null) {
                log.warn("HTS [{}] execution {}: no account on book {}", s.variant().name(), s.symbol(), book);
                placed.remove(key);
                return;
            }
            try {
                broker.selectAccount(account.id());
            } catch (Exception ignored) {
                // already selected
            }

            // Shape the order so the broker actually accepts it: snap the size to
            // the instrument step, round the stop to instrument precision, and
            // widen a too-tight M5 band stop out to the broker minimum. Without
            // this Capital returns a dealReference and then REJECTS the deal on
            // confirm, so no position ever opens.
            MarketRules rules = broker.marketRules(s.epic());
            if (!rules.tradeable()) {
                // Weekend / session break: prices still stream so the scan fires a
                // signal, but Capital rejects every POST /positions with an empty
                // reason. Skip cleanly instead of placing a doomed order and
                // e-mailing a failure every cycle.
                log.warn("HTS [{}] {} entry skipped — {} not tradeable right now (market closed / weekend)",
                        s.variant().name(), s.symbol(), s.epic());
                placed.remove(key);
                mailer.sendThrottled("exec-hts-closed-" + s.variant().name(),
                        mailTag(s) + " entry skipped — market closed",
                        s.variant().name() + " " + s.symbol() + " " + s.direction()
                                + " signal fired but " + s.epic() + " is not tradeable right now "
                                + "(weekend / market closed). No order was placed.\n\n"
                                + "(further skips within 30 min are suppressed)");
                return;
            }

            double fx = broker.fxRate(rules.currency(), account.currency());
            if (fx <= 0) {
                fx = 1.0;
            }
            // Value of one price point per 1.0 size, IN THE ACCOUNT CURRENCY.
            // A standard CFD moves one unit of the instrument currency per point
            // (Capital lotSize=1), so when Capital returns no valueOfOnePip
            // (null for US100 / GOLD) fall back to 1.0 *in the instrument
            // currency* and then convert — NOT to 1.0 in the account currency,
            // which silently drops FX and sizes a USD instrument on a PLN
            // account ~USDPLN× too big (a "1%" trade actually risked ~3.7%).
            double pointValue = rules.pointValue() > 0 ? rules.pointValue() : 1.0;
            double pointValueAcct = pointValue * fx;

            double stopDist = Math.abs(s.entry() - s.stopLevel());
            // Cash at risk if the entry stop is hit = riskPercent of account equity.
            // MMS ×1 ≈ 1% account for a 1% price move; after a full SL outside
            // the bands the unit drops to ×0.1 until the first profitable setup
            // restores ×1. An add-on wick SL does not cut the unit.
            boolean mms = s.variant().strategy() == HtsVariant.Strategy.MMS;
            double riskUnit = mms ? trades.mmsRiskUnit(s.variant(), s.symbol()) : 1.0;
            double cash = risk.riskAmount(account, live) * properties.getHtsRiskPercent() * riskUnit;
            double sizeDist = mms && s.entry() > 0 ? s.entry() * 0.01 : stopDist;
            double size = risk.sizeFor(cash, sizeDist * pointValueAcct, 1.0);
            if (size <= 0 || stopDist <= 0) {
                log.warn("HTS [{}] execution {}: size/stop is zero (cash {}, stopDist {}, pointValue {})",
                        s.variant().name(), s.symbol(), cash, stopDist, pointValueAcct);
                placed.remove(key);
                return;
            }
            if (live && size < properties.getMinDealSize()) {
                log.warn("HTS [{}] LIVE execution skipped {} — size {} below min deal {} (1R would risk too little)",
                        s.variant().name(), s.symbol(), size, properties.getMinDealSize());
                placed.remove(key);
                return;
            }

            boolean buy = s.direction() == Direction.BUY;
            double adjEntry = rules.roundPrice(s.entry());
            double adjStop = rules.roundPrice(s.stopLevel());
            if (rules.minStopDistancePoints() > 0
                    && Math.abs(adjEntry - adjStop) < rules.minStopDistancePoints()) {
                adjStop = rules.roundPrice(buy
                        ? adjEntry - rules.minStopDistancePoints()
                        : adjEntry + rules.minStopDistancePoints());
                log.info("HTS [{}] {} stop widened to broker minimum {} pts ({} -> {})",
                        s.variant().name(), s.symbol(), rules.minStopDistancePoints(),
                        s.stopLevel(), adjStop);
            }
            if (Math.abs(adjEntry - adjStop) <= 0) {
                log.warn("HTS [{}] execution {}: stop equals entry after rounding ({})",
                        s.variant().name(), s.symbol(), adjEntry);
                placed.remove(key);
                return;
            }
            double adjDist = Math.abs(adjEntry - adjStop);
            // If the stop was widened to the broker minimum, re-size from the NEW
            // distance so risk stays ~1R — otherwise a 165pt band stop widened to
            // a 500pt broker minimum would risk ~3× the intended amount.
            if (adjDist > stopDist * 1.0001 && !mms) {
                double resized = risk.sizeFor(cash, adjDist * pointValueAcct, 1.0);
                log.info("HTS [{}] {} re-sized after stop widen: {} -> {} (dist {} -> {})",
                        s.variant().name(), s.symbol(), size, resized, stopDist, adjDist);
                size = resized;
            }
            double adjSize = rules.roundSize(size);
            if (adjSize <= 0) {
                log.warn("HTS [{}] execution {}: size rounds to zero (raw {}, minDeal {})",
                        s.variant().name(), s.symbol(), size, rules.minDealSize());
                placed.remove(key);
                return;
            }

            double minSize = Math.max(rules.minDealSize(), live ? properties.getMinDealSize() : 0);

            // Cap the size to what the account can actually margin. The 1R risk
            // sizing can produce a notional the account can't cover — Capital then
            // rejects the whole deal with RISK_CHECK and nothing opens. Shrink
            // below 1R rather than over-leverage; skip if even the smallest deal
            // won't fit.
            double freeFunds = account.available() > 0 ? account.available() : account.balance();
            if (rules.marginFactor() > 0 && freeFunds > 0) {
                double marginPerUnit = adjEntry * rules.marginFactor() * fx;
                if (marginPerUnit > 0) {
                    double maxSize = (freeFunds * properties.getHtsMarginBuffer()) / marginPerUnit;
                    if (minSize > 0 && maxSize < minSize) {
                        log.warn("HTS [{}] {} entry skipped — free margin {} {} covers < the minimum deal "
                                        + "(margin/unit ~{} {}, buffer {}); account too small for this instrument",
                                s.variant().name(), s.symbol(), freeFunds, account.currency(),
                                marginPerUnit, account.currency(), properties.getHtsMarginBuffer());
                        placed.remove(key);
                        mailer.sendThrottled("exec-hts-margin-" + s.variant().name(),
                                mailTag(s) + " entry skipped — account too small for " + s.symbol(),
                                s.variant().name() + " " + s.symbol() + " " + s.direction() + " signal fired but the "
                                        + "account's free margin (" + freeFunds + " " + account.currency() + ") does not "
                                        + "cover even the broker minimum deal size. No order was placed.\n\n"
                                        + "(further skips within 30 min are suppressed)");
                        return;
                    }
                    if (adjSize > maxSize) {
                        double capped = rules.roundSize(maxSize);
                        log.warn("HTS [{}] {} size capped by free margin: {} -> {} (free {} {}, margin/unit ~{} {})",
                                s.variant().name(), s.symbol(), adjSize, capped,
                                freeFunds, account.currency(), marginPerUnit, account.currency());
                        adjSize = capped;
                    }
                }
            }

            if (adjSize <= 0 || (minSize > 0 && adjSize < minSize)) {
                log.warn("HTS [{}] LIVE execution skipped {} — size {} below min deal {} after shaping",
                        s.variant().name(), s.symbol(), adjSize, minSize);
                placed.remove(key);
                return;
            }
            // After rounding to minDeal / step, the actual cash at risk can exceed
            // the 1R target (a tiny account on a chunky-minDeal instrument). Don't
            // place an oversized ticket.
            double actualRisk = adjDist * adjSize * pointValueAcct;
            if (actualRisk > cash * 1.25) {
                log.warn("HTS [{}] {} entry skipped — rounded size {} risks ~{} {} > 1.25x target {} {} "
                                + "(min deal / step forces oversize on this account)",
                        s.variant().name(), s.symbol(), adjSize,
                        Math.round(actualRisk * 100) / 100.0, account.currency(),
                        Math.round(cash * 100) / 100.0, account.currency());
                placed.remove(key);
                mailer.sendThrottled("exec-hts-oversize-" + s.variant().name(),
                        mailTag(s) + " entry skipped — size too big for " + s.symbol(),
                        s.variant().name() + " " + s.symbol() + " " + s.direction() + ": the smallest tradeable "
                                + "size (" + adjSize + ") risks ~" + (Math.round(actualRisk * 100) / 100.0) + " "
                                + account.currency() + ", over 1.25x the " + (Math.round(cash * 100) / 100.0) + " "
                                + account.currency() + " target. No order was placed.\n\n"
                                + "(further skips within 30 min are suppressed)");
                return;
            }
            double adjTarget = buy ? adjEntry + HtsEngine.RR * adjDist : adjEntry - HtsEngine.RR * adjDist;
            if (adjEntry != s.entry() || adjStop != s.stopLevel() || adjSize != size) {
                log.info("HTS [{}] {} order shaped for broker: entry {}->{} stop {}->{} size {}->{}",
                        s.variant().name(), s.symbol(), s.entry(), adjEntry,
                        s.stopLevel(), adjStop, size, adjSize);
            }
            size = adjSize;
            s = new HtsScan(s.variant(), s.timestamp(), s.symbol(), s.epic(), s.direction(),
                    adjEntry, adjStop, adjTarget, s.htfUp());

            // Stop only — the position monitor runs the TP1 partial + trailing runner.
            OrderRequest req = new OrderRequest(
                    s.epic(), s.direction(), size, null, "MARKET",
                    s.stopLevel(), null, null, false);
            OrderAck ack = broker.placeMarketOrder(req);
            if (ack == null || ack.dealReference() == null) {
                log.warn("HTS [{}] entry {} returned no deal reference", s.variant().name(), s.symbol());
                placed.remove(key);
                return;
            }
            // An order went to the broker — arm the FAST re-entry cooldown now,
            // whether it fills or gets rejected, so a REJECTED order does not
            // re-submit every M5 bar.
            noteFastEntry(s);

            // Capital's POST /positions only hands back a dealReference; the real
            // dealId (and whether the deal was actually accepted) comes from the
            // confirm. Without this the position monitor can never reconcile the
            // trade — same pattern the SDD gate uses.
            Position position = resolvePosition(broker, ack.dealReference(), s);
            if (position == null) {
                log.warn("HTS [{}] entry {} {} submitted (ref {}) but NOT confirmed open — not recording",
                        s.variant().name(), s.symbol(), s.direction(), ack.dealReference());
                placed.remove(key);
                mailer.sendThrottled("exec-hts-reject", mailTag(s) + " entry not confirmed open — " + s.symbol(),
                        "An HTS entry for " + s.variant().name() + " (" + s.variant().htf() + "/" + s.variant().ltf()
                                + ") " + s.symbol() + " " + s.direction()
                                + " was submitted (ref " + ack.dealReference() + ") but the broker did not "
                                + "confirm an open position. See the confirm reason in the logs.\n\n"
                                + "(further failures within 30 min are suppressed)");
                return;
            }
            String dealId = position.dealId();
            // Persist the POSITION's own dealReference (Capital prefixes it "p_...",
            // vs the ORDER's "o_..." we got from placeMarketOrder) — not ack's, which
            // reconcile's live.refs() (also position dealReferences) can never match
            // since the two namespaces never intersect. See resolvePosition's javadoc.
            String dealReference = position.dealReference() != null ? position.dealReference() : ack.dealReference();
            OrderAck confirmed = new OrderAck(dealReference, dealId, "ACCEPTED");
            log.info("HTS [{}] entry placed on {} {} {} size {} @ {} stop {} dealId {} (TP1 target {} managed)",
                    s.variant().name(), book, s.symbol(), s.direction(), size,
                    s.entry(), s.stopLevel(), dealId, s.targetLevel());
            try {
                trades.recordOpen(s, s.variant(), book, account.name(), size, confirmed);
            } catch (Exception e) {
                log.warn("HTS [{}] entry {} placed but not recorded: {}",
                        s.variant().name(), s.symbol(), e.getClass().getSimpleName());
            }
        } catch (Exception e) {
            // Surface the broker's own message (OKX/Capital put the reject code +
            // text there — e.g. "OKX /api/v5/trade/order failed: code=51010 msg=..."),
            // not just "BrokerException", so a live failure is diagnosable.
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("HTS [{}] execution failed for {}: {}", s.variant().name(), s.symbol(), detail);
            placed.remove(key);
            errorLog.record("hts-exec", s.variant().name(), s.symbol(), e);
            mailer.sendThrottled("exec-hts", mailTag(s) + " execution failed — " + s.symbol(),
                    "Placing an HTS entry failed for " + s.variant().name() + " " + s.symbol()
                            + " " + s.direction() + ":\n\n" + detail
                            + "\n\n(further failures within 30 min are suppressed)");
        }
    }

    /**
     * True when FAST already entered this symbol in the same direction and the HTF
     * has not flipped since, and less than {@link #FAST_REENTRY_COOLDOWN} has
     * elapsed. A direction flip (new HTF swing) always clears the block.
     */
    private boolean fastReentryBlocked(HtsScan s) {
        SwingEntry last = lastFastEntry.get(s.variant().name() + "|" + s.symbol());
        if (last == null || last.dir() != s.direction()) {
            return false;
        }
        Instant now = s.timestamp() != null ? s.timestamp() : Instant.now();
        return Duration.between(last.at(), now).compareTo(FAST_REENTRY_COOLDOWN) < 0;
    }

    /** Record a FAST entry attempt so {@link #fastReentryBlocked} can gate the next one. */
    private void noteFastEntry(HtsScan s) {
        if (s.variant() != HtsVariant.FAST) {
            return;
        }
        Instant at = s.timestamp() != null ? s.timestamp() : Instant.now();
        lastFastEntry.put(s.variant().name() + "|" + s.symbol(), new SwingEntry(s.direction(), at));
    }

    /** Mail subject prefix that names the model and its timeframe, e.g. "HTS [CORE H4/M15]". */
    private static String mailTag(HtsScan s) {
        if (s == null || s.variant() == null) {
            return "HTS";
        }
        return "HTS [" + s.variant().name() + " " + s.variant().htf() + "/" + s.variant().ltf() + "]";
    }

    /**
     * Resolve the live {@link Position} for a fresh entry, <b>verified against
     * {@link BrokerClient#openPositions()}</b> before {@code confirm()}'s dealId
     * is trusted — the confirm alone is not enough.
     *
     * <p>Found live during the 2026-09-11..18 ST_V3 rollout: every single one of
     * 94 trades across 4 books got a {@code confirm()} dealId that never showed
     * up in a subsequent {@code openPositions()} call — so the reconcile loop
     * force-closed every trade as {@code MANUAL} ~10 minutes later (two
     * 5-minute "not found" misses), while the real position kept running on
     * the broker, unmanaged (no TP1/band-cross/stop logic watching it any
     * more). Confirmed by cross-checking: at the same moment a trade's stored
     * dealId was reported absent, {@code openPositions()} for that same book
     * still listed 4-8 positions, none matching any {@code deal_id} ever
     * recorded in {@code hts_trades}.
     *
     * <p>One dead end ruled out along the way: matching by {@code dealReference}
     * between the order and the position does NOT work — Capital prefixes an
     * order's reference {@code "o_..."} and the resulting position's own
     * reference {@code "p_..."}, two namespaces that never intersect (this is
     * also why {@code HtsTradeService}'s {@code live.refs()} reconcile
     * fallback was silently dead: it stored the order's {@code "o_..."} ref
     * and compared it against positions' {@code "p_..."} refs). Fixed here by
     * persisting the resolved position's own {@code dealReference} instead
     * (see the caller) — that fallback now actually works.
     *
     * <p>Preference order once a position list is fetched:
     * <ol>
     *   <li>{@code confirm()}'s dealId, but only once verified present in
     *       {@code openPositions()};</li>
     *   <li>the freshest live position on the same epic + direction (by
     *       {@code openedAt}), same heuristic {@code ExecutionGate} uses, now
     *       tie-broken by recency instead of REST response order — needed
     *       because satellite books can hold more than one position on the
     *       same epic/direction at once.</li>
     * </ol>
     * Returns {@code null} only when the deal was rejected, or nothing above
     * finds a match — the caller then does NOT record a phantom OPEN trade.
     */
    private Position resolvePosition(BrokerClient broker, String dealReference, HtsScan s) {
        Confirmation confirmation = null;
        String confirmedDealId = null;
        try {
            confirmation = broker.confirm(dealReference);
            if (confirmation != null) {
                if (confirmation.dealId() != null && confirmation.accepted()) {
                    confirmedDealId = confirmation.dealId();
                } else {
                    log.warn("HTS [{}] {} {} confirm: status={} dealStatus={} reason={}",
                            s.variant().name(), s.symbol(), s.direction(),
                            confirmation.status(), confirmation.dealStatus(), confirmation.reason());
                    if ("REJECTED".equalsIgnoreCase(confirmation.dealStatus())) {
                        return null; // definitively rejected — no point matching positions
                    }
                }
            }
        } catch (Exception e) {
            log.warn("HTS [{}] {} confirm failed ({}) — trying position match",
                    s.variant().name(), s.symbol(), e.getClass().getSimpleName());
        }
        try {
            List<Position> positions = broker.openPositions();
            if (confirmedDealId != null) {
                for (Position p : positions) {
                    if (confirmedDealId.equals(p.dealId())) {
                        return p; // verified against the live list
                    }
                }
                log.warn("HTS [{}] {} {}: confirm() dealId {} not found in openPositions() "
                                + "(dealReference {}) — falling back to freshest {} position match",
                        s.variant().name(), s.symbol(), s.direction(), confirmedDealId, dealReference, s.epic());
            }
            Position freshest = null;
            for (Position p : positions) {
                if (p.direction() != s.direction()
                        || p.epic() == null || !p.epic().equalsIgnoreCase(s.epic())) {
                    continue;
                }
                if (freshest == null
                        || (p.openedAt() != null && (freshest.openedAt() == null || p.openedAt().isAfter(freshest.openedAt())))) {
                    freshest = p;
                }
            }
            if (freshest != null) {
                return freshest;
            }
        } catch (Exception e) {
            // openPositions() itself unavailable (transient broker/network glitch) —
            // don't drop an otherwise-accepted deal over it; fall back to a synthetic
            // Position built from the confirm (dealReference null, so the caller
            // keeps the order's "o_..." ref — same as this method not existing at
            // all, i.e. no worse than before this fix).
            if (confirmedDealId != null) {
                log.warn("HTS [{}] {} openPositions() unavailable ({}) — trusting unverified confirm dealId {}",
                        s.variant().name(), s.symbol(), e.getClass().getSimpleName(), confirmedDealId);
                return new Position(confirmedDealId, null, confirmation.epic(), confirmation.direction(),
                        confirmation.size() == null ? 0 : confirmation.size(),
                        confirmation.level() == null ? 0 : confirmation.level(),
                        null, null, 0, null, null);
            }
        }
        return null;
    }
}
