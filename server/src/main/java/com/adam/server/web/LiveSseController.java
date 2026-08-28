package com.adam.server.web;

import com.adam.server.scan.AccountQueryService;
import com.adam.server.scan.MonitoringService;
import com.adam.server.web.dto.OverviewView;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-Sent Events (#11): a single endpoint that streams the latest overview
 * snapshot to every connected dashboard tab, so the UI updates without polling.
 * The scheduler refreshes the payload every 30s; each client receives it on a
 * heartbeat, keeping a live view with zero manual refresh.
 */
@RestController
public class LiveSseController {

    private final AccountQueryService accounts;
    private final MonitoringService monitor;
    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastPayload = new AtomicReference<>("{}");

    public LiveSseController(AccountQueryService accounts, MonitoringService monitor) {
        this.accounts = accounts;
        this.monitor = monitor;
    }

    @GetMapping(value = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // never time out
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        clients.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("overview").data(lastPayload.get()));
        } catch (IOException ignored) {
            clients.remove(emitter);
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 30_000)
    public void push() {
        try {
            List<OverviewView> overview = accounts.overview();
            String json = overviewToJson(overview);
            lastPayload.set(json);
            for (SseEmitter emitter : clients) {
                try {
                    emitter.send(SseEmitter.event().name("overview").data(json));
                } catch (IOException e) {
                    clients.remove(emitter);
                }
            }
        } catch (Exception e) {
            // never let the scheduler die
        }
    }

    private String overviewToJson(List<OverviewView> overview) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < overview.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            OverviewView o = overview.get(i);
            sb.append('{')
                    .append("\"id\":\"").append(esc(o.id())).append('"')
                    .append(",\"kind\":\"").append(esc(o.kind())).append('"')
                    .append(",\"displayName\":\"").append(esc(o.displayName())).append('"')
                    .append(",\"equity\":").append(num(o.equity()))
                    .append(",\"dayPnl\":").append(num(o.dayPnl()))
                    .append(",\"positionsCount\":").append(o.positionsCount())
                    .append(",\"positionsPnl\":").append(num(o.positionsPnl()))
                    .append(",\"maxLossPln\":").append(num(o.maxLossPln()))
                    .append(",\"positionsWithoutStop\":").append(o.positionsWithoutStop())
                    .append(",\"remainingToHaltPln\":").append(num(o.remainingToHaltPln()))
                    .append(",\"connected\":").append(o.connected())
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private static String num(Double d) {
        return d == null ? "null" : d.toString();
    }
}
