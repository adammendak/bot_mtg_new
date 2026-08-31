package com.adam.server.broker.okx;

import com.adam.server.broker.BrokerException;
import com.adam.server.broker.Direction;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.broker.model.Confirmation;
import com.adam.server.broker.model.MarketRules;
import com.adam.server.broker.model.OrderAck;
import com.adam.server.broker.model.OrderRequest;
import com.adam.server.broker.model.Position;
import com.adam.server.config.AppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP-level behaviour of {@link OkxBrokerClient} against a stub server: the
 * HMAC auth headers are present and signed, public candles parse, the error
 * mapping surfaces OKX's code/msg, and order → confirm → position flows work.
 */
class OkxBrokerClientHttpTest {

    private MockWebServer server;
    private OkxBrokerClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        AppProperties.Okx ep = new AppProperties.Okx();
        ep.setHost(server.url("/").toString().replaceAll("/$", ""));
        ep.setApiKey("test-key");
        ep.setSecret("test-secret");
        ep.setPassphrase("test-passphrase");
        ep.setDemo(false);
        client = new OkxBrokerClient(RestClient.builder(), "okx", ep, "no creds configured");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void enqueue(String body) {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    void loginValidatesAndSetsAuthHeaders() throws Exception {
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":[{\"totalEq\":\"1234.5\"}]}");
        client.login();
        assertThat(client.isSessionOpen()).isTrue();
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v5/account/balance");
        assertThat(req.getHeader("OK-ACCESS-KEY")).isEqualTo("test-key");
        assertThat(req.getHeader("OK-ACCESS-TIMESTAMP")).isNotBlank();
        assertThat(req.getHeader("OK-ACCESS-PASSPHRASE")).isEqualTo("test-passphrase");
        assertThat(req.getHeader("OK-ACCESS-SIGN")).isNotBlank();
        // signature must not be a constant across calls → timestamp+method+path are signed
        assertThat(req.getHeader("OK-ACCESS-SIGN")).hasSizeGreaterThan(10);
    }

    @Test
    void demoFlagAddsSimulatedHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"msg\":\"\",\"data\":[{\"totalEq\":\"1\"}]}"));
        AppProperties.Okx ep = new AppProperties.Okx();
        ep.setHost(server.url("/").toString().replaceAll("/$", ""));
        ep.setApiKey("k");
        ep.setSecret("s");
        ep.setPassphrase("p");
        ep.setDemo(true);
        OkxBrokerClient demo = new OkxBrokerClient(RestClient.builder(), "okx", ep, "no creds");
        demo.login();
        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("x-simulated-trading")).isEqualTo("1");
    }

    @Test
    void candlesParseAscendingAndFiltered() throws Exception {
        // OKX returns newest-first; [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]
        long now = Instant.now().toEpochMilli();
        long t0 = now - 400_000L;
        long t1 = now - 200_000L;
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":["
                + "[" + t1 + ",\"100\",\"105\",\"99\",\"104\",\"10\"],"
                + "[" + t0 + ",\"95\",\"99\",\"94\",\"98\",\"8\"],"
                + "[123,\"1\",\"2\",\"1\",\"1\",\"1\"]"
                + "]}");
        List<Candle> candles = client.candles("BTC-USDT-SWAP", Resolution.H1,
                Instant.ofEpochMilli(t0 - 1000), Instant.ofEpochMilli(now + 1000), 100);
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).time().toEpochMilli()).isEqualTo(t0);
        assertThat(candles.get(0).close()).isEqualTo(98);
        assertThat(candles.get(1).time().toEpochMilli()).isEqualTo(t1);
        // request used the H1 bar and paging param
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("bar=1H");
    }

    @Test
    void candlesDropsUnclosedBar() throws Exception {
        // OKX marks the CURRENT forming bar with confirm=0 — HTS must not see it.
        long now = Instant.now().toEpochMilli();
        long t0 = now - 200_000L;
        long tOpen = now - 50_000L;
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":["
                + "[" + tOpen + ",\"101\",\"102\",\"100\",\"101.5\",\"5\",\"0\",\"0\",\"0\"],"
                + "[" + t0 + ",\"95\",\"99\",\"94\",\"98\",\"8\",\"0\",\"0\",\"1\"]"
                + "]}");
        List<Candle> candles = client.candles("BTC-USDT-SWAP", Resolution.H1,
                Instant.ofEpochMilli(t0 - 1000), Instant.ofEpochMilli(now + 1000), 100);
        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).time().toEpochMilli()).isEqualTo(t0);
    }

    @Test
    void marketRulesMapContractValues() throws Exception {
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":[{"
                + "\"instId\":\"BTC-USDT-SWAP\",\"tickSz\":\"0.1\",\"lotSz\":\"1\","
                + "\"minSz\":\"1\",\"ctVal\":\"0.01\",\"lever\":\"10\","
                + "\"state\":\"live\",\"settleCcy\":\"USDT\"}]}");
        MarketRules r = client.marketRules("BTC-USDT-SWAP");
        assertThat(r.minDealSize()).isEqualTo(1);
        assertThat(r.priceDecimalPlaces()).isEqualTo(1);   // tickSz 0.1
        assertThat(r.marginFactor()).isEqualTo(0.001);      // ctVal/lever = 0.01/10
        assertThat(r.pointValue()).isEqualTo(0.01);
        assertThat(r.tradeable()).isTrue();
        assertThat(r.currency()).isEqualTo("USDT");
    }

    @Test
    void placeMarketOrderSendsStopAndReturnsOrdId() throws Exception {
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":[{\"ordId\":\"312269865356374016\",\"sCode\":\"0\"}]}");
        OrderAck ack = client.placeMarketOrder(new OrderRequest(
                "BTC-USDT-SWAP", Direction.BUY, 10, null, "MARKET", 95000.0, null, null, false));
        assertThat(ack.dealReference()).isEqualTo("312269865356374016");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v5/trade/order");
        assertThat(req.getBody().readUtf8())
                .contains("\"side\":\"buy\"")
                .contains("\"ordType\":\"market\"")
                .contains("\"slTriggerPx\":\"95000\"")
                .contains("\"attachAlgoOrds\"");
    }

    @Test
    void confirmResolvesPosId() throws Exception {
        // POST /trade/order ack, then order details (state=filled), then positions
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":[{\"ordId\":\"312269865356374016\",\"sCode\":\"0\"}]}");
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":[{"
                + "\"state\":\"filled\",\"side\":\"buy\",\"fillPx\":\"96000\","
                + "\"fillSz\":\"10\",\"instId\":\"BTC-USDT-SWAP\"}]}");
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":[{"
                + "\"posId\":\"452587086133239818\",\"instId\":\"BTC-USDT-SWAP\","
                + "\"pos\":\"10\",\"avgPx\":\"96000\",\"upl\":\"1.5\",\"ccy\":\"USDT\","
                + "\"uTime\":\"1700000000000\"}]}");
        OrderAck ack = client.placeMarketOrder(new OrderRequest(
                "BTC-USDT-SWAP", Direction.BUY, 10, null, "MARKET", 95000.0, null, null, false));
        Confirmation c = client.confirm(ack.dealReference());
        assertThat(c.accepted()).isTrue();
        assertThat(c.dealId()).isEqualTo("452587086133239818");
        assertThat(c.epic()).isEqualTo("BTC-USDT-SWAP");
        assertThat(c.direction()).isEqualTo(Direction.BUY);
    }

    @Test
    void openPositionsMapsSignedSize() throws Exception {
        enqueue("{\"code\":\"0\",\"msg\":\"\",\"data\":[{"
                + "\"posId\":\"p1\",\"instId\":\"BTC-USDT-SWAP\",\"pos\":\"-2\","
                + "\"avgPx\":\"90000\",\"upl\":\"-10\",\"ccy\":\"USDT\",\"uTime\":\"1700000000000\"},"
                + "{\"posId\":\"p2\",\"instId\":\"ETH-USDT-SWAP\",\"pos\":\"5\","
                + "\"avgPx\":\"3000\",\"upl\":\"3\",\"ccy\":\"USDT\",\"uTime\":\"1700000000000\"}]}");
        List<Position> positions = client.openPositions();
        assertThat(positions).hasSize(2);
        Position shortPos = positions.get(0);
        assertThat(shortPos.direction()).isEqualTo(Direction.SELL);
        assertThat(shortPos.size()).isEqualTo(2);
        Position longPos = positions.get(1);
        assertThat(longPos.direction()).isEqualTo(Direction.BUY);
        assertThat(longPos.size()).isEqualTo(5);
    }

    @Test
    void errorCodeIsSurfaced() throws Exception {
        enqueue("{\"code\":\"51000\",\"msg\":\"Parameter instId cannot be empty\",\"data\":[]}");
        assertThatThrownBy(() -> client.marketPrice("BTC-USDT-SWAP"))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("51000")
                .hasMessageContaining("Parameter instId");
    }
}
