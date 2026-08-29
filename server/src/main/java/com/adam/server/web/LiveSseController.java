package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.scan.AccountQueryService;
import com.adam.server.web.dto.OverviewView;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-Sent Events (#11): streams the latest overview snapshot to every
 * connected dashboard tab. The scheduler refreshes the payload every 30s and
 * each client receives it filtered to that client's book grants, so a USER
 * never sees books they are not allowed to.
 */
@RestController
public class LiveSseController {

    private final AccountQueryService accounts;
    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final Map<SseEmitter, AppUser> clientUsers = new ConcurrentHashMap<>();
    private final AtomicReference<List<OverviewView>> lastSnapshot = new AtomicReference<>(List.of());

    public LiveSseController(AccountQueryService accounts) {
        this.accounts = accounts;
    }

    @GetMapping(value = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        AppUser user = CurrentUser.of(authentication);
        SseEmitter emitter = new SseEmitter(0L); // never time out
        emitter.onCompletion(() -> {
            clients.remove(emitter);
            clientUsers.remove(emitter);
        });
        emitter.onTimeout(() -> {
            clients.remove(emitter);
            clientUsers.remove(emitter);
        });
        emitter.onError(e -> {
            clients.remove(emitter);
            clientUsers.remove(emitter);
        });
        clients.add(emitter);
        clientUsers.put(emitter, user);
        try {
            emitter.send(SseEmitter.event().name("overview").data(payloadFor(user)));
        } catch (IOException ignored) {
            clients.remove(emitter);
            clientUsers.remove(emitter);
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 30_000)
    public void push() {
        try {
            lastSnapshot.set(accounts.overview());
            for (SseEmitter emitter : clients) {
                try {
                    AppUser user = clientUsers.get(emitter);
                    emitter.send(SseEmitter.event().name("overview").data(payloadFor(user)));
                } catch (IOException e) {
                    clients.remove(emitter);
                    clientUsers.remove(emitter);
                }
            }
        } catch (Exception e) {
            // never let the scheduler die
        }
    }

    private String payloadFor(AppUser user) {
        List<OverviewView> visible = lastSnapshot.get();
        if (user != null) {
            visible = visible.stream().filter(o -> user.canSeeBook(o.id())).toList();
        }
        return overviewToJson(visible);
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
