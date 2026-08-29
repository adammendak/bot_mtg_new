package com.adam.server.history;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {

    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    @GetMapping(value = "/api/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public HistoryResponse history(
            @RequestParam(name = "book", required = false, defaultValue = "demo") String book,
            Authentication authentication
    ) {
        AppUser user = CurrentUser.of(authentication);
        if (user != null && !user.canSeeBook(book)) {
            return HistoryResponse.empty();
        }
        return history.daily(book);
    }
}
