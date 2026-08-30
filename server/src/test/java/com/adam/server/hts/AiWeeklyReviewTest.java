package com.adam.server.hts;

import com.adam.server.config.AppProperties;
import com.adam.server.ops.ErrorLog;
import com.adam.server.scan.Mailer;
import com.adam.server.web.dto.HtsJournal;
import com.adam.server.web.dto.HtsScorecardRow;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWeeklyReviewTest {

    private final HtsTradeService trades = mock(HtsTradeService.class);
    private final Mailer mailer = mock(Mailer.class);
    private final ErrorLog errorLog = mock(ErrorLog.class);
    private MockWebServer server;

    private static final HtsJournal EMPTY =
            new HtsJournal(0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
    private static final HtsJournal ONE_WEEK = new HtsJournal(
            5, 3, 0.6, 0.4, 2.0,
            List.of(new HtsJournal.Day("2026-09-01", 2.0, 40.0, 3)),
            List.of(new HtsJournal.Bucket("≤ −1R", 2), new HtsJournal.Bucket("> 3R", 1)),
            List.of(new HtsJournal.Group("STOP", 2, 0, 0, -1.0, -2.0)),
            List.of(new HtsJournal.Group("GER40", 5, 3, 0.6, 0.4, 2.0)));

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private AiWeeklyReview review(boolean enabled, String key) {
        return new AiWeeklyReview(trades, mailer, errorLog, JsonMapper.builder().build(),
                new AppProperties(), enabled, key, "claude-sonnet-5",
                server.url("/v1/messages").toString());
    }

    @Test
    void disabledDoesNothing() {
        review(false, "sk-test").run();
        verify(trades, never()).journal(any(), any(), any(), any());
        verify(mailer, never()).send(any(), any());
    }

    @Test
    void noApiKeyDoesNothing() {
        review(true, "").run();
        verify(trades, never()).journal(any(), any(), any(), any());
    }

    @Test
    void noClosedTradesSendsAShortNoteWithoutCallingTheApi() {
        when(trades.journal(any(), any(), any(), any())).thenReturn(EMPTY);
        review(true, "sk-test").run();
        verify(mailer).send(contains("przegląd tygodnia"), contains("Brak zamkniętych"));
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void callsAnthropicAndMailsTheReview() throws Exception {
        when(trades.journal(any(), any(), any(), any())).thenReturn(ONE_WEEK);
        when(trades.scorecard()).thenReturn(List.<HtsScorecardRow>of());
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"CORE zagrał, FAST odstaje.\"}]}"));

        review(true, "sk-test").run();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/messages");
        assertThat(req.getHeader("x-api-key")).isEqualTo("sk-test");
        assertThat(req.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(req.getBody().readUtf8()).contains("claude-sonnet-5").contains("DANE:");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq("HTS — przegląd tygodnia (AI)"), body.capture());
        assertThat(body.getValue()).contains("CORE zagrał, FAST odstaje.").contains("Dane wejściowe:");
    }

    @Test
    void anApiFailureIsSwallowedAndLogged() {
        when(trades.journal(any(), any(), any(), any())).thenReturn(ONE_WEEK);
        when(trades.scorecard()).thenReturn(List.<HtsScorecardRow>of());
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        review(true, "sk-test").run();

        verify(errorLog).record(eq("ai-review"), any(), any(), any(Throwable.class));
        verify(mailer, never()).send(eq("HTS — przegląd tygodnia (AI)"), any());
    }
}
