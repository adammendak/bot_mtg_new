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
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.05));

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
        when(risk.sizeFor(anyDouble(), eq(500.0), anyDouble())).thenReturn(0.02);
        when(risk.sizeFor(eq(10.0), org.mockito.ArgumentMatchers.doubleThat(d -> d < 200), anyDouble()))
                .thenReturn(0.06);
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.02));

        gate.executeSignal(signal(78988.65, 78823.036));

        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(broker).placeMarketOrder(req.capture());
        assertThat(req.getValue().size()).isEqualTo(0.02); // sized for the widened 500pt stop
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
        when(risk.sizeFor(anyDouble(),
                org.mockito.ArgumentMatchers.doubleThat(d -> d > 600), anyDouble())).thenReturn(0.02);
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.02));

        gate.executeSignal(signal(78988.65, 78823.036)); // stopDist ~165 -> ~662 after point value

        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(broker).placeMarketOrder(req.capture());
        assertThat(req.getValue().size()).isEqualTo(0.02);
    }

    @Test
    void capsSizeToWhatTheAccountCanMargin() {
        // 1R sizing wants 0.5 lots; account has 1000 PLN and DE40-style margin
        // is 5% of a USD notional at ~4.0 PLN/USD -> only ~0.05 lots fit.
        when(risk.sizeFor(anyDouble(), anyDouble(), anyDouble())).thenReturn(0.5);
        when(broker.marketRules(anyString()))
                .thenReturn(new MarketRules("BTCUSD", 0.001, 1, 0, 0, true, 0.05, "USD"));
        when(broker.fxRate("USD", "PLN")).thenReturn(4.0);
        when(broker.confirm("ref1")).thenReturn(new Confirmation(
                "ref1", "D1", "OPEN", "ACCEPTED", null, "BTCUSD", Direction.BUY, 78988.6, 0.05));

        gate.executeSignal(signal(78988.65, 78823.036));

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

    @Test
    void neverStacksASecondPositionForAnOpenModelSymbol() {
        when(trades.hasOpenPosition(HtsVariant.FAST, "BTC")).thenReturn(true);

        gate.executeSignal(signal(78988.65, 78823.036));

        verify(broker, never()).placeMarketOrder(any());
        verify(trades, never()).recordOpen(any(), any(), anyString(), anyString(), anyDouble(), any());
    }
}
