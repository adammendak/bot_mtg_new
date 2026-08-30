package com.adam.server.history;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manual trigger for the equity history sync (reconstructs daily equity from the
 * broker's transaction history into {@code broker_snapshots}).
 */
@RestController
@RequestMapping("/api/history")
public class EquityHistoryController {

    private final EquityHistoryService service;

    public EquityHistoryController(EquityHistoryService service) {
        this.service = service;
    }

    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public EquityHistoryService.SyncResult sync(
            @RequestParam(name = "book", defaultValue = "live") String book,
            @RequestParam(name = "replace", defaultValue = "false") boolean replace,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return EquityHistoryService.SyncResult.failed("no access to book " + book);
        }
        return safeSync(book, replace);
    }

    /**
     * Overall wall-clock cap for {@code /sync-all}: even with each book's own
     * transaction walk bounded, five books back to back can still blow Heroku's
     * 30 s router timeout. Once this is spent the remaining books come back as
     * {@code failed("skipped …")} and the caller can sync them one at a time.
     */
    private static final long SYNC_ALL_BUDGET_MS = 24_000;

    @PostMapping(value = "/sync-all", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<EquityHistoryService.SyncResult> syncAll(
            @RequestParam(name = "replace", defaultValue = "false") boolean replace,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        List<String> allowed = com.adam.server.broker.Books.ALL.stream()
                .filter(book -> user == null || user.canSeeBook(book))
                .toList();
        long deadline = System.currentTimeMillis() + SYNC_ALL_BUDGET_MS;
        List<EquityHistoryService.SyncResult> results = new java.util.ArrayList<>();
        for (String book : allowed) {
            if (System.currentTimeMillis() > deadline) {
                results.add(EquityHistoryService.SyncResult.failed(
                        "skipped " + book + " — sync-all time budget spent; retry with /sync?book=" + book));
            } else {
                results.add(safeSync(book, replace));
            }
        }
        return results;
    }

    /**
     * One book's sync failing (broker down, rate-limited, session expiry, or a
     * linkage/Error from a stale class on a rolling deploy) must not 500 the
     * whole request — it becomes a {@code failed} result the UI can show per
     * book. {@code Throwable}, not {@code Exception}, on purpose.
     */
    private EquityHistoryService.SyncResult safeSync(String book, boolean replace) {
        try {
            return service.sync(book, replace);
        } catch (Throwable t) {
            return EquityHistoryService.SyncResult.failed("sync " + book + " failed: " + t.getClass().getSimpleName());
        }
    }
}
