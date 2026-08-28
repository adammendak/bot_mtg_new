package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Direction;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.web.dto.AccountView;
import com.adam.server.web.dto.OverviewView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountQueryServiceTest {

    @Mock
    BrokerClient demo;

    private AccountQueryService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        BrokerBooks books = new BrokerBooks(demo, new UnavailableBrokerClient("live", "test"),
                new UnavailableBrokerClient("glowne", "test"));
        service = new AccountQueryService(books, new RiskPolicy(props), props);
        when(demo.book()).thenReturn("demo");
        when(demo.id()).thenReturn("capital");
        when(demo.configured()).thenReturn(true);
    }

    @Test
    void surfacesGenericExceptionMessageInsteadOfUnavailable(CapturedOutput output) {
        when(demo.isSessionOpen()).thenReturn(true);
        when(demo.accounts()).thenThrow(new IllegalStateException("balance field was null"));

        AccountView view = service.view(demo);

        assertThat(view.connected()).isFalse();
        assertThat(view.error()).isEqualTo("balance field was null");
        assertThat(view.error()).doesNotContain("unavailable");
        assertThat(output.getOut() + output.getErr())
                .contains("demo")
                .contains("capital")
                .contains("balance field was null");
    }

    @Test
    void surfacesCauseMessageWhenExceptionMessageIsBlank(CapturedOutput output) {
        when(demo.isSessionOpen()).thenReturn(true);
        when(demo.accounts()).thenThrow(new RuntimeException(null, new IllegalStateException("root cause here")));

        AccountView view = service.view(demo);

        assertThat(view.error()).isEqualTo("root cause here");
        assertThat(view.error()).doesNotContain("DEMO unavailable");
        assertThat(output.getOut() + output.getErr()).contains("root cause here");
    }

    @Test
    void listingSucceedsWhenSelectAccountThrows() {
        when(demo.isSessionOpen()).thenReturn(true);
        when(demo.accounts()).thenReturn(List.of(new Account("acc-1", "paper", "PLN", 1234, 1000, 12, true)));
        doThrow(new IllegalStateException("PUT /session rejected")).when(demo).selectAccount("acc-1");

        AccountView view = service.view(demo);

        assertThat(view.connected()).isTrue();
        assertThat(view.accountName()).isEqualTo("paper");
        assertThat(view.equity()).isEqualTo(1234);
        assertThat(view.error()).isNull();
    }

    @Test
    void overviewTagsDemoKindAndCountsPositions() {
        when(demo.isSessionOpen()).thenReturn(true);
        when(demo.accounts()).thenReturn(List.of(new Account("acc-1", "paper", "PLN", 1234, 1000, 12, true)));
        when(demo.openPositions()).thenReturn(List.of(
                new Position("d1", "r1", "GER40", Direction.BUY, 1.0, 18000, 17900.0, null, 25.0, "PLN", null),
                new Position("d2", "r2", "US100", Direction.SELL, 0.5, 20000, null, null, -8.0, "PLN", null)
        ));

        List<OverviewView> rows = service.overview();

        assertThat(rows).hasSize(3);
        OverviewView demoRow = rows.stream().filter(r -> r.id().equals("demo")).findFirst().orElseThrow();
        assertThat(demoRow.kind()).isEqualTo("DEMO");
        assertThat(demoRow.strategy()).isEqualTo("SDD-M15");
        assertThat(demoRow.connected()).isTrue();
        assertThat(demoRow.positionsCount()).isEqualTo(2);
        assertThat(demoRow.positionsPnl()).isEqualTo(17.0);
        // live/glowne are unconfigured -> disconnected rows still carry kind + strategy
        assertThat(rows.stream().filter(r -> r.id().equals("live")).findFirst().orElseThrow().kind()).isEqualTo("LIVE");
        assertThat(rows.stream().filter(r -> r.id().equals("glowne")).findFirst().orElseThrow().kind()).isEqualTo("MAIN");
        assertThat(rows.stream().filter(r -> r.id().equals("live")).findFirst().orElseThrow().connected()).isFalse();
    }

    @Test
    void kindOfClassifiesEveryBook() {
        assertThat(AccountQueryService.kindOf("demo")).isEqualTo("DEMO");
        assertThat(AccountQueryService.kindOf("live")).isEqualTo("LIVE");
        assertThat(AccountQueryService.kindOf("glowne")).isEqualTo("MAIN");
        assertThat(AccountQueryService.kindOf("unknown")).isEqualTo("DEMO");
    }
}
