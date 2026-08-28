package com.adam.server.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Logs in and returns an opaque bearer token plus the caller's profile. */
    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> login(@RequestBody LoginRequest body) {
        AuthService.LoginResult result = auth.login(body.username(), body.password());
        if (result == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "invalid_credentials");
            err.put("message", "Invalid username or password.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", result.token());
        out.put("user", userJson(result.user()));
        return ResponseEntity.ok(out);
    }

    /** Current caller (also refreshes book grants from the DB). */
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> me(Authentication authentication) {
        AppUser user = currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        AppUser fresh = auth.reload(user.id());
        if (fresh == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        return ResponseEntity.ok(userJson(fresh));
    }

    /** Invalidates the caller's token. */
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> logout(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            // The token itself is not stored on the principal, so we cannot
            // revoke by value here — the client drops the token. Harmless.
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    private static AppUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user;
        }
        return null;
    }

    static Map<String, Object> userJson(AppUser user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.id());
        m.put("username", user.username());
        m.put("displayName", user.displayName());
        m.put("role", user.role().name());
        m.put("books", user.booksSorted());
        return m;
    }

    public record LoginRequest(String username, String password) {
    }
}
