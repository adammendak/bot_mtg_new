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
import com.adam.server.ladder.LadderLogic.Params;
import com.adam.server.sdd.RiskPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LadderBotTest {

    private static final Params GER = new Params("GER40", "DE40", 0.5, 0.5, 10, 0.2, LocalTime.of(7, 5), 15);
    private static final Params SP = new Params("SP500", "US500", 0.75, 0.5, 10, 0.2, LocalTime.of(13, 35), 15);

    private BrokerClient ger;
    private BrokerClient sp;
    private AppProperties props;
    private RiskPolicy risk;
    private Account account;

    @BeforeEach
    void setUp() {
        ger = mock(BrokerClient.class);
        sp = mock(BrokerClient.class);          // not configured → idle
        props = new AppProperties();
        risk = mock(RiskPolicy.class);
        account = new Account("acc-1", "GerLadder", "EUR", 10000, 10000, 0, true);
        when(ger.configured()).thenReturn(true);
        when(ger.isSessionOpen()).thenReturn(true);
        when(ger.accounts()).thenReturn(List.of(account));
        when(risk.pickTvAccount(any(), eq("GerLadder"))).thenReturn(account);
        when(ger.marketRules("DE40")).thenReturn(new MarketRules("DE40", 0.01, 1, 0, 0, true, 0.05, "EUR", 1.0, 0.1));
        when(ger.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 24000.0, 24002.0, Instant.now()));
        when(ger.openPositions()).thenReturn(List.of());
        when(ger.placeMarketOrder(any())).thenReturn(new OrderAck("ref-1", null, "SUBMITTED"));
        when(ger.confirm("ref-1")).thenReturn(new Confirmation("ref-1", "d-1", "OPEN", "ACCEPTED", null, "DE40",
                Direction.BUY, 24002.0, 0.17));
    }

    private LadderBot bot(boolean enabled, String at) {
        return new LadderBot(ger, sp, props, risk, enabled, 25, GER, SP, 10000, 10000,
                Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
    }

    private static Position pos(double level) {
        return new Position("d", "r", "DE40", Direction.BUY, 0.17, level, null, level * 1.005, 0, "EUR", Instant.now());
    }

    @Test
    void doesNothingWhileDisabled() {
        bot(false, "2026-11-03T07:06:00Z").tick();
        verify(ger, never()).placeMarketOrder(any());
        verify(ger, never()).marketPrice(anyString());
    }

    @Test
    void dailyOpenBuysWithTheTakeProfitAttachedAndTheRightSize() {
        bot(true, "2026-11-03T07:06:00Z").tick();

        ArgumentCaptor<OrderRequest> cap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(ger).placeMarketOrder(cap.capture());
        OrderRequest r = cap.getValue();
        assertThat(r.direction()).isEqualTo(Direction.BUY);
        assertThat(r.stopLevel()).isNull();                       // no SL by design
        assertThat(r.profitLevel()).isEqualTo(24122.0);           // 24002 * 1.005 = 24122.01 → step 0.1
        assertThat(r.size()).isEqualTo(0.17);                     // 0.4 x 10000 / 24002
        verify(ger).selectAccount("acc-1");
        verify(sp, never()).placeMarketOrder(any());
    }

    @Test
    void laddersWhenTheBidFallsHalfAPercentBelowTheLowestEntry() {
        when(ger.openPositions()).thenReturn(List.of(pos(24000.0), pos(24100.0)));
        when(ger.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 23879.0, 23881.0, Instant.now()));

        bot(true, "2026-11-03T15:00:00Z").tick();

        verify(ger).placeMarketOrder(any());
    }

    @Test
    void doesNotLadderAboveTheNextLevel() {
        when(ger.openPositions()).thenReturn(List.of(pos(24000.0)));
        when(ger.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 23900.0, 23902.0, Instant.now()));

        bot(true, "2026-11-03T15:00:00Z").tick();

        verify(ger, never()).placeMarketOrder(any());
    }

    @Test
    void refusesAnyHostThatIsNotTheDemoApi() {
        props.getCapital().getLadderGer().setHost("https://api-capital.backend-capital.com");

        bot(true, "2026-11-03T07:06:00Z").tick();

        verify(ger, never()).placeMarketOrder(any());
    }

    @Test
    void doesNotBuyTwiceInARowBeforeThePositionShowsUp() {
        when(ger.openPositions()).thenReturn(List.of(pos(24000.0)));
        when(ger.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 23870.0, 23872.0, Instant.now()));
        LadderBot b = bot(true, "2026-11-03T15:00:00Z");

        b.tick();
        b.tick();      // broker still lists only the old position

        verify(ger, org.mockito.Mockito.times(1)).placeMarketOrder(any());
    }

    @Test
    void skipsWhenTheMarketIsClosed() {
        when(ger.marketRules("DE40")).thenReturn(new MarketRules("DE40", 0.01, 1, 0, 0, false));

        bot(true, "2026-11-03T07:06:00Z").tick();

        verify(ger, never()).placeMarketOrder(any());
    }

    @Test
    void failsafeStopsNewEntriesAfterA25PercentDrawdown() {
        Account hurt = new Account("acc-1", "GerLadder", "EUR", 10000, 4000, -2600, true);
        when(risk.pickTvAccount(any(), eq("GerLadder"))).thenReturn(hurt);
        when(ger.openPositions()).thenReturn(List.of(pos(24000.0)));
        when(ger.marketPrice("DE40")).thenReturn(new MarketPrice("DE40", 23000.0, 23002.0, Instant.now()));

        bot(true, "2026-11-03T15:00:00Z").tick();

        verify(ger, never()).placeMarketOrder(any());
    }
}
