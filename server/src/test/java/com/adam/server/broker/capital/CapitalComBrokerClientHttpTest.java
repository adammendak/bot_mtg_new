package com.adam.server.broker.capital;

import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Resolution;
import com.adam.server.config.AppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP-level behaviour of {@link CapitalComBrokerClient} against a stub server
 * (E-11): the session handshake, error mapping (429 / 400 daterange), the
 * credential guard, and token rotation on {@code selectAccount}.
 */
class CapitalComBrokerClientHttpTest {

    private MockWebServer server;
    private CapitalComBrokerClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        AppProperties.Endpoint ep = new AppProperties.Endpoint();
        ep.setHost(server.url("/").toString().replaceAll("/$", ""));
        ep.setApiKey("test-key");
        ep.setEmail("bot@example.com");
        ep.setPassword("s3cr3t-pw");
        client = new CapitalComBrokerClient(RestClient.builder(), "demo", ep, "no creds configured");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse session(String cst, String token) {
        return new MockResponse().setResponseCode(200)
                .addHeader("CST", cst).addHeader("X-SECURITY-TOKEN", token)
                .addHeader("Content-Type", "application/json").setBody("{}");
    }

    @Test
    void loginOpensASessionAndSendsTheApiKeyAndEmail() throws Exception {
        server.enqueue(session("cst-1", "tok-1"));

        client.login();

        assertThat(client.isSessionOpen()).isTrue();
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v1/session");
        assertThat(req.getHeader("X-CAP-API-KEY")).isEqualTo("test-key");
        assertThat(req.getBody().readUtf8()).contains("bot@example.com");
    }

    @Test
    void a429OnLoginIsRetriedAndThenMapsToBrokerExceptionWithoutLeakingThePassword() {
        // login() retries a 429 a few times before giving up; enqueue enough
        // that every attempt is rate-limited.
        for (int i = 0; i < 6; i++) {
            server.enqueue(new MockResponse().setResponseCode(429)
                    .setBody("{\"errorCode\":\"error.too-many.requests\"}"));
        }

        assertThatThrownBy(() -> client.login())
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("login failed")
                .hasMessageNotContaining("s3cr3t-pw");
        assertThat(client.isSessionOpen()).isFalse();
    }

    @Test
    void aTransient429OnLoginIsRetriedThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"errorCode\":\"error.too-many.requests\"}"));
        server.enqueue(session("cst-1", "tok-1"));

        client.login();

        assertThat(client.isSessionOpen()).isTrue();
    }

    @Test
    void a200LoginWithoutTokensIsRejected() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody("{}"));

        assertThatThrownBy(() -> client.login()).isInstanceOf(BrokerException.class);
        assertThat(client.isSessionOpen()).isFalse();
    }

    @Test
    void missingCredentialsFailFast() {
        AppProperties.Endpoint bare = new AppProperties.Endpoint();
        bare.setHost(server.url("/").toString());
        CapitalComBrokerClient noCreds =
                new CapitalComBrokerClient(RestClient.builder(), "demo", bare, "no creds configured");
        assertThatThrownBy(noCreds::login)
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("no creds configured");
    }

    @Test
    void invalidMaxDaterangeOnCandlesPropagatesAsBrokerException() throws Exception {
        server.enqueue(session("cst-1", "tok-1"));
        client.login();
        server.takeRequest();
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"errorCode\":\"error.invalid.max.daterange\"}"));

        assertThatThrownBy(() -> client.candles("DE40", Resolution.M15,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), 1000))
                .isInstanceOf(BrokerException.class);
    }

    @Test
    void selectAccountRotatesTheSessionTokens() throws Exception {
        server.enqueue(session("cst-1", "tok-1"));           // login
        server.enqueue(session("cst-2", "tok-2"));           // PUT /session -> new tokens
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"accounts\":[]}"));               // GET /accounts

        client.login();
        server.takeRequest(); // consume login
        client.selectAccount("acc-9");
        server.takeRequest(); // consume PUT /session

        client.accounts();
        RecordedRequest accountsReq = server.takeRequest();
        assertThat(accountsReq.getPath()).isEqualTo("/api/v1/accounts");
        assertThat(accountsReq.getHeader("CST")).isEqualTo("cst-2");
        assertThat(accountsReq.getHeader("X-SECURITY-TOKEN")).isEqualTo("tok-2");
    }
}
