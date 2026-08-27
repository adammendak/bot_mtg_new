package com.adam.server.scan;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class RecordingWebhookServer implements AutoCloseable {

    record Recorded(String method, String path, String query, String authorization, String webhookSecret, String body) {
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    volatile int status = 200;

    RecordingWebhookServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Headers headers = exchange.getRequestHeaders();
            requests.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    headers.getFirst("Authorization"),
                    headers.getFirst("X-Webhook-Secret"),
                    new String(raw, StandardCharsets.UTF_8)
            ));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    String urlWithQuery(String query) {
        return url() + "?" + query;
    }

    List<Recorded> requests() {
        return new ArrayList<>(requests);
    }

    List<Recorded> ofType(String type) {
        String needle = "\"type\":\"" + type + "\"";
        return requests().stream().filter(r -> r.body().contains(needle)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
