package com.adam.server.scan;

import com.adam.server.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TelegramNotifierTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private TelegramNotifier notifier(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            bodies.add(new String(raw, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        AppProperties props = new AppProperties();
        props.setTelegramBotToken("TESTTOKEN");
        props.setTelegramChatId("12345");
        TelegramNotifier notifier = new TelegramNotifier(props, RestClient.builder());
        notifier.apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
        return notifier;
    }

    @Test
    void unconfiguredIsSilentNoOp() {
        AppProperties props = new AppProperties(); // no token, no chat id
        TelegramNotifier notifier = new TelegramNotifier(props, RestClient.builder());
        assertThatCode(() -> {
            notifier.onFill("demo", "GER40", "BUY", 1.0, 18000, 17900);
            notifier.onScanError("boom");
        }).doesNotThrowAnyException();
        assertThat(bodies).isEmpty();
    }

    @Test
    void onFillPostsChatIdAndText() throws Exception {
        TelegramNotifier notifier = notifier(200);
        notifier.onFill("demo", "GER40", "BUY", 1.0, 18000, 17900);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).contains("\"chat_id\":\"12345\"");
        assertThat(bodies.getFirst()).contains("FILL");
        assertThat(bodies.getFirst()).contains("GER40");
    }

    @Test
    void haltEdgeNotifiesOnceAndAgainAfterRecovery() throws Exception {
        TelegramNotifier notifier = notifier(200);
        notifier.onHalt("demo", -35, -30, true);
        notifier.onHalt("demo", -40, -30, true); // still halted -> no duplicate
        assertThat(bodies).hasSize(1);
        notifier.onHalt("demo", -10, -30, false); // recovered
        notifier.onHalt("demo", -35, -30, true); // new halt -> alert again
        assertThat(bodies).hasSize(2);
    }

    @Test
    void scanErrorEdgeNotifiesOncePerEpisode() throws Exception {
        TelegramNotifier notifier = notifier(200);
        notifier.onScanError("broker down");
        notifier.onScanError("broker down"); // same episode -> no duplicate
        assertThat(bodies).hasSize(1);
        notifier.onScanRecovered();
        notifier.onScanError("broker down again");
        assertThat(bodies).hasSize(2);
    }

    @Test
    void http500DoesNotThrow() throws Exception {
        TelegramNotifier notifier = notifier(500);
        assertThatCode(() -> notifier.onFill("live", "US100", "SELL", 1.0, 20000, 20100))
                .doesNotThrowAnyException();
        assertThat(notifier.lastError()).isEqualTo("HTTP 500");
    }
}
