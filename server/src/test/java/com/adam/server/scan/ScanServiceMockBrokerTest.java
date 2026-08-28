package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.UnavailableBrokerClient;
import com.adam.server.broker.model.Account;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import com.adam.server.sdd.NewsBlackout;
import com.adam.server.sdd.RiskPolicy;
import com.adam.server.sdd.SddScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ScanServiceMockBrokerTest {

    static final String TEST_SECRET = "crsr_UNITTEST_SENDER_KEY";

    @Mock
    BrokerClient broker;

    @Test
    void scanUsesBrokerSpiNotCapitalJson() {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")).toInstant();
        Clock clock = Clock.fixed(now, ZoneId.of("Europe/Warsaw"));
        AppProperties props = new AppProperties();
        props.setBroker("paper");
        props.setNewsCalendarUrl("");
        when(broker.id()).thenReturn("mock");
        when(broker.displayName()).thenReturn("Mock broker");
        when(broker.book()).thenReturn("demo");
        when(broker.configured()).thenReturn(true);
        when(broker.accounts()).thenReturn(List.of(new Account("1", "paper", "PLN", 1000, 1000, 0, true)));
        stubCandles(now, "MISSING");

        ScanStore store = new ScanStore();
        ScanService service = newService(props, clock, store);

        ScanSnapshot snapshot = service.scan();
        assertThat(snapshot.brokerId()).isEqualTo("mock");
        assertThat(snapshot.symbols()).hasSize(5);
        assertThat(snapshot.symbols().stream().map(SddScan::symbol)).containsExactly("GER40", "XAU", "US100", "EURUSD", "BTC");
        assertThat(snapshot.error()).isNull();
        assertThat(snapshot.books()).hasSize(3);
        assertThat(store.last()).isEqualTo(snapshot);
    }

    @Test
    void scanContinuesWhenOneEpic404s(CapturedOutput output) {
        Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")).toInstant();
        Clock clock = Clock.fixed(now, ZoneId.of("Europe/Warsaw"));
        AppProperties props = new AppProperties();
        props.setBroker("paper");
        props.setNewsCalendarUrl("");
        props.getSdd().getEpics().setBtc("MISSING");
        when(broker.id()).thenReturn("mock");
        when(broker.displayName()).thenReturn("Mock broker");
        when(broker.book()).thenReturn("demo");
        when(broker.configured()).thenReturn(true);
        when(broker.accounts()).thenReturn(List.of(new Account("1", "paper", "PLN", 1000, 1000, 0, true)));
        stubCandles(now, "MISSING");

        ScanSnapshot snapshot = newService(props, clock, new ScanStore()).scan();

        assertThat(snapshot.error()).isNull();
        assertThat(snapshot.symbols()).hasSize(5);
        assertThat(snapshot.symbols().stream().map(SddScan::symbol))
                .containsExactly("GER40", "XAU", "US100", "EURUSD", "BTC");
        SddScan btc = snapshot.symbols().getLast();
        assertThat(btc.epic()).isEqualTo("MISSING");
        assertThat(btc.failed()).contains("epic_not_found");
        assertThat(btc.reason()).contains("epic not found: MISSING");
        assertThat(btc.fullStack()).isFalse();
        assertThat(snapshot.symbols().stream().filter(s -> !"BTC".equals(s.symbol())))
                .allSatisfy(s -> assertThat(s.failed()).doesNotContain("epic_not_found"));
        assertThat(output.getOut() + output.getErr()).contains("MISSING");
        assertThat(output.getOut() + output.getErr()).doesNotContain("scan failed");
    }

    @Test
    void perSymbolBrokerErrorDoesNotAbortUniverseOrFailover() throws Exception {
        try (RecordingWebhookServer server = new RecordingWebhookServer()) {
            Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")).toInstant();
            Clock clock = Clock.fixed(now, ZoneId.of("Europe/Warsaw"));
            AppProperties props = webhookProps(server);
            when(broker.id()).thenReturn("mock");
            when(broker.displayName()).thenReturn("Mock broker");
            when(broker.book()).thenReturn("demo");
            when(broker.configured()).thenReturn(true);
            when(broker.accounts()).thenReturn(List.of(new Account("1", "paper", "PLN", 1000, 1000, 0, true)));
            stubCandlesThrowingOn("GOLD", now, new BrokerException("Capital.com demo candles failed: HTTP 500"));

            ScanSnapshot snapshot = newService(props, clock, new ScanStore()).scan();

            assertThat(snapshot.error()).isNull();
            assertThat(snapshot.symbols()).hasSize(5);
            SddScan gold = snapshot.symbols().stream().filter(s -> "XAU".equals(s.symbol())).findFirst().orElseThrow();
            assertThat(gold.failed()).contains("broker_error");
            assertThat(gold.reason()).contains("HTTP 500");
            assertThat(server.ofType("failover")).isEmpty();
        }
    }

    @Test
    void webhook500DoesNotFailScan(CapturedOutput output) throws Exception {
        try (RecordingWebhookServer server = new RecordingWebhookServer()) {
            server.status = 500;
            Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")).toInstant();
            Clock clock = Clock.fixed(now, ZoneId.of("Europe/Warsaw"));
            AppProperties props = webhookProps(server);
            when(broker.id()).thenReturn("mock");
            when(broker.displayName()).thenReturn("Mock broker");
            when(broker.book()).thenReturn("demo");
            when(broker.configured()).thenReturn(true);
            when(broker.accounts()).thenReturn(List.of(new Account("1", "paper", "PLN", 1000, 1000, 0, true)));
            stubCandles(now, "MISSING");

            SignalWebhookPublisher publisher = new SignalWebhookPublisher(props, RestClient.builder()) {
                @Override
                public void onScanFinished(ScanSnapshot snapshot) {
                    super.onScanFinished(snapshot);
                    publish(SignalWebhookPublisherTest.fullStackScan());
                }
            };
            ScanStore store = new ScanStore();
            ScanSnapshot snapshot = newService(props, clock, store, publisher).scan();

            assertThat(snapshot.error()).isNull();
            assertThat(snapshot.symbols()).hasSize(5);
            assertThat(store.last().symbols()).hasSize(5);
            assertThat(snapshot.lastWebhookError()).isEqualTo("HTTP 500");
            assertThat(output.getOut() + output.getErr()).contains("HTTP 500");
            assertThat(output.getOut() + output.getErr()).doesNotContain(TEST_SECRET);
            assertThat(output.getOut() + output.getErr()).doesNotContain("querysecret");
        }
    }

    @Test
    void failedScanEmitsFailover() throws Exception {
        try (RecordingWebhookServer server = new RecordingWebhookServer()) {
            Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")).toInstant();
            Clock clock = Clock.fixed(now, ZoneId.of("Europe/Warsaw"));
            AppProperties props = webhookProps(server);
            when(broker.id()).thenReturn("mock");
            when(broker.displayName()).thenReturn("Mock broker");
            when(broker.book()).thenReturn("demo");
            when(broker.configured()).thenReturn(true);
            doThrow(new BrokerException("Capital.com demo login failed")).when(broker).login();

            ScanSnapshot snapshot = newService(props, clock, new ScanStore()).scan();

            assertThat(snapshot.error()).contains("login failed");
            assertThat(snapshot.symbols()).isEmpty();
            assertThat(server.ofType("failover")).hasSize(1);
            RecordingWebhookServer.Recorded failover = server.ofType("failover").getFirst();
            assertThat(failover.authorization()).isEqualTo("Bearer " + TEST_SECRET);
            assertThat(failover.webhookSecret()).isEqualTo(TEST_SECRET);
            assertThat(failover.body()).contains("\"type\":\"failover\"");
            assertThat(failover.body()).contains("\"reason\":\"scan_failed\"");
            assertThat(failover.body()).contains("\"scannedAt\":null");
            assertThat(server.ofType("scan_ok")).isEmpty();
        }
    }

    @Test
    void skipped404DoesNotEmitFailover() throws Exception {
        try (RecordingWebhookServer server = new RecordingWebhookServer()) {
            Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")).toInstant();
            Clock clock = Clock.fixed(now, ZoneId.of("Europe/Warsaw"));
            AppProperties props = webhookProps(server);
            props.getSdd().getEpics().setBtc("MISSING");
            when(broker.id()).thenReturn("mock");
            when(broker.displayName()).thenReturn("Mock broker");
            when(broker.book()).thenReturn("demo");
            when(broker.configured()).thenReturn(true);
            when(broker.accounts()).thenReturn(List.of(new Account("1", "paper", "PLN", 1000, 1000, 0, true)));
            stubCandles(now, "MISSING");

            ScanSnapshot snapshot = newService(props, clock, new ScanStore()).scan();

            assertThat(snapshot.error()).isNull();
            assertThat(snapshot.symbols()).hasSize(5);
            assertThat(server.ofType("failover")).isEmpty();
            assertThat(server.ofType("scan_ok")).isEmpty();
        }
    }

    @Test
    void successAfterFailureEmitsScanOkOnce() throws Exception {
        try (RecordingWebhookServer server = new RecordingWebhookServer()) {
            Instant now = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")).toInstant();
            Clock clock = Clock.fixed(now, ZoneId.of("Europe/Warsaw"));
            AppProperties props = webhookProps(server);
            when(broker.id()).thenReturn("mock");
            when(broker.displayName()).thenReturn("Mock broker");
            when(broker.book()).thenReturn("demo");
            when(broker.configured()).thenReturn(true);

            doThrow(new BrokerException("Capital.com demo login failed")).when(broker).login();
            SignalWebhookPublisher publisher = new SignalWebhookPublisher(props, RestClient.builder());
            ScanService service = newService(props, clock, new ScanStore(), publisher);

            ScanSnapshot failed = service.scan();
            assertThat(failed.error()).isNotNull();
            assertThat(server.ofType("failover")).hasSize(1);

            org.mockito.Mockito.reset(broker);
            when(broker.id()).thenReturn("mock");
            when(broker.displayName()).thenReturn("Mock broker");
            when(broker.book()).thenReturn("demo");
            when(broker.configured()).thenReturn(true);
            when(broker.accounts()).thenReturn(List.of(new Account("1", "paper", "PLN", 1000, 1000, 0, true)));
            stubCandles(now, "MISSING");

            ScanSnapshot recovered = service.scan();
            assertThat(recovered.error()).isNull();
            assertThat(recovered.symbols()).hasSize(5);
            assertThat(server.ofType("scan_ok")).hasSize(1);
            assertThat(server.ofType("scan_ok").getFirst().body()).contains("\"type\":\"scan_ok\"");
            assertThat(server.ofType("scan_ok").getFirst().authorization()).isEqualTo("Bearer " + TEST_SECRET);

            service.scan();
            assertThat(server.ofType("scan_ok")).hasSize(1);
            assertThat(server.ofType("failover")).hasSize(1);
        }
    }

    @Test
    void schedulerFailoverFailureDoesNotThrow() {
        ScanService scanService = org.mockito.Mockito.mock(ScanService.class);
        SignalWebhookPublisher webhooks = org.mockito.Mockito.mock(SignalWebhookPublisher.class);
        when(scanService.scan()).thenThrow(new IllegalStateException("scheduler blew up"));
        org.mockito.Mockito.doThrow(new RuntimeException("hook down"))
                .when(webhooks).publishFailover(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        ScanScheduler scheduler = new ScanScheduler(scanService, webhooks);
        assertThatCode(scheduler::onM15Close).doesNotThrowAnyException();
    }

    private void stubCandles(Instant now, String missingEpic) {
        stubCandlesThrowingOn(missingEpic, now, notFoundEpic());
    }

    private void stubCandlesThrowingOn(String badEpic, Instant now, RuntimeException error) {
        when(broker.candles(any(), eq(Resolution.M15), any(), any(), anyInt())).thenAnswer(inv -> {
            if (badEpic.equals(inv.getArgument(0))) {
                throw error;
            }
            return rising(now, Duration.ofMinutes(15), 200);
        });
        when(broker.candles(any(), eq(Resolution.H1), any(), any(), anyInt())).thenAnswer(inv -> {
            if (badEpic.equals(inv.getArgument(0))) {
                throw error;
            }
            return rising(now, Duration.ofHours(1), 180);
        });
        when(broker.candles(any(), eq(Resolution.H4), any(), any(), anyInt())).thenAnswer(inv -> {
            if (badEpic.equals(inv.getArgument(0))) {
                throw error;
            }
            return rising(now, Duration.ofHours(4), 80);
        });
    }

    private static AppProperties webhookProps(RecordingWebhookServer server) {
        AppProperties props = new AppProperties();
        props.setBroker("paper");
        props.setNewsCalendarUrl("");
        props.setWebhookUrls(server.urlWithQuery("token=querysecret"));
        props.setWebhookSecret(TEST_SECRET);
        return props;
    }

    private ScanService newService(AppProperties props, Clock clock, ScanStore store) {
        return newService(props, clock, store, new SignalWebhookPublisher(props, RestClient.builder()));
    }

    private ScanService newService(AppProperties props, Clock clock, ScanStore store, SignalWebhookPublisher publisher) {
        BrokerBooks books = new BrokerBooks(broker, new UnavailableBrokerClient("live", "test"),
                new UnavailableBrokerClient("glowne", "test"));
        RiskPolicy risk = new RiskPolicy(props);
        AccountQueryService accounts = new AccountQueryService(books, risk);
        return new ScanService(
                books,
                props,
                store,
                publisher,
                new NewsBlackout(props, RestClient.builder(), clock),
                risk,
                new ExecutionGate(props, books, risk),
                accounts,
                clock,
                null
        );
    }

    private static HttpClientErrorException notFoundEpic() {
        return HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                new HttpHeaders(),
                "{\"errorCode\":\"error.not-found.epic\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }

    private static List<Candle> rising(Instant now, Duration step, int count) {
        List<Candle> out = new ArrayList<>();
        Instant t = now.minus(step.multipliedBy(count));
        double px = 100;
        for (int i = 0; i < count; i++) {
            out.add(new Candle(t, px, px + 1, px - 0.2, px + 0.8, 1));
            px += 0.8;
            t = t.plus(step);
        }
        return out;
    }
}
