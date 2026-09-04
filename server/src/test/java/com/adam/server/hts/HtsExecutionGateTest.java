package com.adam.server.hts;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.config.AppProperties;
import com.adam.server.ops.ErrorLog;
import com.adam.server.ops.FeatureFlags;
import com.adam.server.scan.Mailer;
import com.adam.server.sdd.RiskPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The HTS execution gate must actually get a position open: shape the order to
 * the instrument rules, confirm it, resolve the real dealId and persist — or, if
 * the broker rejects it, record nothing and alert.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HtsExecutionGateTest {

    @Mock
    RiskPolicy risk;
    @Mock
    HtsTradeService trades;
    @Mock
    Mailer mailer;
    @Mock
    ErrorLog errorLog;
    @Mock
    BrokerClient broker;

    BrokerBooks books;
    AppProperties props;
    HtsExecutionGate gate;

    final String book = HtsVariant.FAST.book(); // "hts"
    final Account account = new Account("acc1", "Account m5", "PLN", 1000, 1000, 0, true);

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        books = mock(BrokerBooks.class);
        when(books.forBook(book)).thenReturn(broker);
        when(broker.configured()).thenReturn(true);
        when(broker.isSessionOpen()).thenReturn(true);
        when(broker.accounts()).thenReturn(List.of(account));
        when(risk.pickForBook(eq(book), any())).thenReturn(account);
        when(risk.riskAmount(any(), eq(false))).thenReturn(10.0);
        when(risk.sizeFor(anyDouble(), anyDouble(), anyDouble())).thenReturn(0.05);
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.001, 1, 0, 0));
        when(broker.fxRate(anyString(), anyString())).thenReturn(1.0);
        when(broker.placeMarketOrder(any())).thenReturn(new OrderAck("ref1", null, "SUBMITTED"));
        gate = new HtsExecutionGate(books, risk, props, trades,
                FeatureFlags.forTest(), mailer, errorLog);
    }

    private HtsScan signal(double entry, double stop) {
        return new HtsScan(HtsVariant.FAST, Instant.parse("2026-08-30T19:00:00Z"),
                "BTC", "BTCUSD", Direction.BUY, entry, stop, entry + 2 * (entry - stop), true);
    }

    private HtsScan fastSignal(Instant ts, Direction dir) {
        double entry = 78988.65;
        double stop = dir == Direction.BUY ? entry - 165 : entry + 165;
        double target = dir == Direction.BUY ? entry + 330 : entry - 330;
        return new HtsScan(HtsVariant.FAST, ts, "BTC", "BTCUSD", dir, entry, stop, target,
                dir == Direction.BUY);
    }

    private void acceptConfirms() {
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.05));
    }

    @Test
    void fastSkipsSameDirectionReentryWithinTheCooldown() {
        acceptConfirms();
        Instant t0 = Instant.parse("2026-09-02T09:00:00Z");

        gate.executeSignal(fastSignal(t0, Direction.BUY));
        gate.executeSignal(fastSignal(t0.plusSeconds(1800), Direction.BUY)); // +30 min, same dir

        verify(broker, times(1)).placeMarketOrder(any());
        verify(trades, times(1)).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
    }

    @Test
    void fastAllowsReentryWhenTheHtfDirectionFlips() {
        acceptConfirms();
        Instant t0 = Instant.parse("2026-09-02T09:00:00Z");

        gate.executeSignal(fastSignal(t0, Direction.BUY));
        gate.executeSignal(fastSignal(t0.plusSeconds(600), Direction.SELL)); // +10 min, flipped

        verify(broker, times(2)).placeMarketOrder(any());
    }

    @Test
    void fastAllowsReentryAfterTheCooldownElapses() {
        acceptConfirms();
        Instant t0 = Instant.parse("2026-09-02T09:00:00Z");

        gate.executeSignal(fastSignal(t0, Direction.BUY));
        gate.executeSignal(fastSignal(t0.plusSeconds(3660), Direction.BUY)); // +61 min, same dir

        verify(broker, times(2)).placeMarketOrder(any());
    }

    @Test
    void fastArmsTheReentryCooldownEvenWhenTheBrokerRejects() {
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", null, "DELETED", "REJECTED", "RC_NOT_ENOUGH_MARGIN", "BTCUSD", Direction.BUY, null, null));
        Instant t0 = Instant.parse("2026-09-02T09:00:00Z");

        gate.executeSignal(fastSignal(t0, Direction.BUY));                    // submitted, rejected
        gate.executeSignal(fastSignal(t0.plusSeconds(300), Direction.BUY));   // +5 min, same dir

        verify(broker, times(1)).placeMarketOrder(any()); // no re-submit every M5 bar
        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
    }

    @Test
    void confirmsPlacesResolvesDealIdAndPersists() {
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.05));

        gate.executeSignal(signal(78988.65, 78823.036));

        ArgumentCaptor<OrderAck> ack = ArgumentCaptor.forClass(OrderAck.class);
        ArgumentCaptor<HtsScan> scan = ArgumentCaptor.forClass(HtsScan.class);
        verify(trades).recordOpen(scan.capture(), eq(HtsVariant.FAST), eq(book), eq("Account m5"),
                eq(0.05), ack.capture());
        assertThat(ack.getValue().dealId()).isEqualTo("D1");
        // stop rounded to the instrument's 1 decimal place before the order went out
        assertThat(scan.getValue().stopLevel()).isEqualTo(78823.0);
        verify(broker).placeMarketOrder(any(OrderRequest.class));
    }

    @Test
    void doesNotPersistWhenTheBrokerRejectsTheDeal() {
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D9", "REJECTED", "REJECTED", "RISKY_TRADE_PREVENTED",
                "BTCUSD", Direction.BUY, null, null));
        when(broker.openPositions()).thenReturn(List.of());

        gate.executeSignal(signal(78988.65, 78823.036));

        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
        verify(mailer).sendThrottled(eq("exec-hts-reject"), anyString(), anyString());
    }

    @Test
    void widensATooTightStopToTheBrokerMinimum() {
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.001, 1, 500.0, 0));
        realSizeFor();
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.02));

        gate.executeSignal(signal(78988.65, 78823.036)); // ~166 pts stop, min is 500

        ArgumentCaptor<HtsScan> scan = ArgumentCaptor.forClass(HtsScan.class);
        verify(trades).recordOpen(scan.capture(), any(), anyString(), anyString(), anyDouble(), any());
        double dist = scan.getValue().entry() - scan.getValue().stopLevel();
        assertThat(dist).isGreaterThanOrEqualTo(500.0);
    }

    @Test
    void reSizesAfterWideningSoRiskDoesNotBlowOut() {
        // broker min stop 500 pts; the band stop is ~166 pts -> widened. Size must
        // be recomputed from 500, not left sized for 166 (~3x over-risk otherwise).
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.001, 1, 500.0, 0));
        realSizeFor();
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.02));

        gate.executeSignal(signal(78988.65, 78823.036));

        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(broker).placeMarketOrder(req.capture());
        // 10 PLN risk over the widened 500pt stop (pv 1.0) -> 0.02, not ~0.06 (sized for 166pt)
        assertThat(req.getValue().size()).isEqualTo(0.02);
    }

    @Test
    void skipsEntryWhenTheMarketIsNotTradeable() {
        // Weekend: prices stream, the scan fires a signal, but the instrument is
        // CLOSED — the gate must not place a doomed order.
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.001, 1, 0, 0, false));

        gate.executeSignal(signal(78988.65, 78823.036));

        verify(broker, never()).placeMarketOrder(any());
        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
        verify(mailer).sendThrottled(eq("exec-hts-closed-FAST"), anyString(), anyString());
    }

    @Test
    void sizesForThePointValueInAccountCurrency() {
        // 1 point of this instrument is worth 1.0 of its own currency, and the FX
        // to the PLN account is 4.0 — so 1R sizing must divide by stopDist*4, not
        // stopDist (otherwise a "1%" trade risks ~4%).
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.001, 1, 0, 0, true, 0.05, "EUR", 1.0));
        when(broker.fxRate("EUR", "PLN")).thenReturn(4.0);
        realSizeFor();
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.015));

        gate.executeSignal(signal(78988.65, 78823.036)); // stopDist ~165 -> ~662 after point value

        // sizeFor must be fed stopDist * pointValue(1.0) * fx(4.0) ~= 662, not ~165
        verify(risk, org.mockito.Mockito.atLeastOnce())
                .sizeFor(eq(10.0), org.mockito.ArgumentMatchers.doubleThat(d -> d > 600), anyDouble());
        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(broker).placeMarketOrder(req.capture());
        // 10 / (165.6 * 4.0) ~= 0.0151 -> ~0.015, NOT ~0.06 (the FX-less bug)
        assertThat(req.getValue().size()).isBetween(0.013, 0.017);
    }

    /** Mirror of RiskPolicy.sizeFor: riskCash / (stopAtrMult * atr). */
    private void realSizeFor() {
        when(risk.sizeFor(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv ->
                (double) inv.getArgument(0) / ((double) inv.getArgument(2) * (double) inv.getArgument(1)));
    }

    @Test
    void usdInstrumentOnAPlnAccountWithNoBrokerPipValueStillGetsFxConverted() {
        // US100 / GOLD: Capital returns valueOfOnePip = null -> pointValue 0.
        // The size must still divide by stopDist * USDPLN, not stopDist alone.
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("US100", 0.001, 1, 0, 0, true, 0.05, "USD", 0.0));
        when(broker.fxRate("USD", "PLN")).thenReturn(3.7);
        realSizeFor();
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "US100", Direction.BUY, 29480.8, 0.012));

        gate.executeSignal(signal(29480.8, 29247.2)); // stopDist 233.6, risk target 10 PLN

        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(broker).placeMarketOrder(req.capture());
        // 10 / (233.6 * 3.7) ~= 0.0116 -> rounds to ~0.012, NOT ~0.043 (the FX-less bug)
        assertThat(req.getValue().size()).isBetween(0.010, 0.014);
    }

    @Test
    void sameCurrencyAccountUsesNoFxConversion() {
        Account usd = new Account("acc1", "Account m5", "USD", 1000, 1000, 0, true);
        when(broker.accounts()).thenReturn(List.of(usd));
        when(risk.pickForBook(eq(book), any())).thenReturn(usd);
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("US100", 0.001, 1, 0, 0, true, 0.05, "USD", 0.0));
        realSizeFor();
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "US100", Direction.BUY, 29480.8, 0.043));

        gate.executeSignal(signal(29480.8, 29247.2)); // 10 / (233.6 * 1.0) ~= 0.043

        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(broker).placeMarketOrder(req.capture());
        assertThat(req.getValue().size()).isBetween(0.040, 0.046);
    }

    @Test
    void skipsWhenTheSmallestTradeableSizeRisksWellOverTheTarget() {
        when(risk.riskAmount(any(), eq(false))).thenReturn(1.0);          // tiny 1 PLN target
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("US100", 0.1, 1, 0, 0, true, 0.05, "USD", 0.0)); // chunky minDeal
        when(broker.fxRate("USD", "PLN")).thenReturn(3.7);
        realSizeFor();

        gate.executeSignal(signal(29480.8, 29247.2)); // minDeal 0.1 -> risk ~86 PLN >> 1.25 PLN

        verify(broker, never()).placeMarketOrder(any());
        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
        verify(mailer).sendThrottled(eq("exec-hts-oversize-FAST"), anyString(), anyString());
    }

    @Test
    void capsSizeToWhatTheAccountCanMargin() {
        // Tight 20pt stop -> 1R sizing wants ~0.125 lots; DE40-style 5% margin on
        // a ~79k USD notional at 4.0 PLN/USD only leaves room for ~0.05 lots.
        realSizeFor();
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.001, 1, 0, 0, true, 0.05, "USD"));
        when(broker.fxRate("USD", "PLN")).thenReturn(4.0);
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 79000.0, 0.05));

        gate.executeSignal(signal(79000.0, 78980.0)); // stopDist 20

        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(broker).placeMarketOrder(req.capture());
        assertThat(req.getValue().size()).isLessThan(0.1).isGreaterThan(0.0);
    }

    @Test
    void skipsWhenFreeMarginCannotCoverTheMinimumDeal() {
        Account small = new Account("acc1", "Account m5", "PLN", 100, 100, 0, true);
        when(broker.accounts()).thenReturn(List.of(small));
        when(risk.pickForBook(eq(book), any())).thenReturn(small);
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.1, 1, 0, 0, true, 0.05, "USD"));
        when(broker.fxRate("USD", "PLN")).thenReturn(4.0);

        gate.executeSignal(signal(78988.65, 78823.036));

        verify(broker, never()).placeMarketOrder(any());
        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
        verify(mailer).sendThrottled(eq("exec-hts-margin-FAST"), anyString(), anyString());
    }

    private HtsScan okxSignal() {
        return new HtsScan(HtsVariant.CORE_OKX, Instant.parse("2026-08-30T19:00:00Z"),
                "BTC", "BTC-USDT-SWAP", Direction.BUY, 100.0, 98.0, 104.0, true);
    }

    @Test
    void skipsRealMoneyOkxUnlessExplicitlyArmed() {
        props.getOkx().setDemo(false);            // real OKX account
        props.getOkx().setLiveExecutionEnabled(false);

        gate.executeSignal(okxSignal());

        verify(broker, never()).placeMarketOrder(any());
        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
    }

    @Test
    void allowsRealMoneyOkxWhenArmed() {
        props.getOkx().setDemo(false);
        props.getOkx().setLiveExecutionEnabled(true);
        when(books.forBook(HtsVariant.CORE_OKX.book())).thenReturn(broker);
        when(risk.pickForBook(eq(HtsVariant.CORE_OKX.book()), any())).thenReturn(account);
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTC-USDT-SWAP", Direction.BUY, 100.0, 0.05));

        gate.executeSignal(okxSignal());

        verify(broker).placeMarketOrder(any());
    }

    @Test
    void neverStacksASecondPositionForAnOpenModelSymbol() {
        when(trades.hasOpenPosition(HtsVariant.FAST, "BTC")).thenReturn(true);

        gate.executeSignal(signal(78988.65, 78823.036));

        verify(broker, never()).placeMarketOrder(any());
        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
    }
}
