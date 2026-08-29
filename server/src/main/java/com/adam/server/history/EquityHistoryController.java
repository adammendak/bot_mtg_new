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

    @PostMapping(value = "/sync-all", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<EquityHistoryService.SyncResult> syncAll(
            @RequestParam(name = "replace", defaultValue = "false") boolean replace,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        List<String> allowed = com.adam.server.broker.Books.ALL.stream()
                .filter(book -> user == null || user.canSeeBook(book))
                .toList();
        return allowed.stream().map(book -> safeSync(book, replace)).toList();
    }

    /**
     * One book's sync failing (broker down, rate-limited, session expiry) must
     * not 500 the whole request — it becomes a {@code failed} result the UI can
     * show per book.
     */
    private EquityHistoryService.SyncResult safeSync(String book, boolean replace) {
        try {
            return service.sync(book, replace);
        } catch (Exception e) {
            return EquityHistoryService.SyncResult.failed("sync " + book + " failed: " + e.getClass().getSimpleName());
        }
    }
}
