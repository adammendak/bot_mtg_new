package com.adam.server.auth;

import com.adam.server.ops.ErrorLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final TotpService totp;
    private final ErrorLog errorLog;

    public AuthController(AuthService auth, TotpService totp, ErrorLog errorLog) {
        this.auth = auth;
        this.totp = totp;
        this.errorLog = errorLog;
    }

    /**
     * First factor. Returns {@code {token, user}} normally, or
     * {@code {mfaRequired:true, mfaToken}} when the account has TOTP enabled —
     * the client then posts the code to {@code /api/auth/login/totp}.
     */
    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> login(@RequestBody LoginRequest body) {
        AuthService.LoginResult result = auth.login(body.username(), body.password());
        if (result == null) {
            errorLog.record("auth", body == null ? null : body.username(), "login-fail",
                    "BadCredentials", "invalid username or password");
            return unauthorized("invalid_credentials", "Invalid username or password.");
        }
        if (result.mfaRequired()) {
            return ResponseEntity.ok(Map.of("mfaRequired", true, "mfaToken", result.mfaToken()));
        }
        return session(result);
    }

    /** Second factor: 6-digit TOTP code or a one-time backup code. */
    @PostMapping(value = "/login/totp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> loginTotp(@RequestBody TotpLoginRequest body) {
        Long userId = auth.pendingMfaUserId(body == null ? null : body.mfaToken());
        boolean ok = userId != null && totp.verify(userId, body.code());
        AuthService.LoginResult result = auth.completeMfa(body == null ? null : body.mfaToken(), ok);
        if (result == null) {
            errorLog.record("auth", userId == null ? null : String.valueOf(userId), "totp-fail",
                    "BadTotpCode", "invalid or expired MFA challenge");
            return unauthorized("invalid_code", "Invalid or expired code.");
        }
        return session(result);
    }

    /** Rotate the caller's token (old one revoked). */
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> refresh(@RequestHeader(value = "Authorization", required = false) String authz) {
        String fresh = auth.refresh(bearer(authz));
        if (fresh == null) {
            return unauthorized("unauthorized", "Missing or expired token.");
        }
        return ResponseEntity.ok(Map.of("token", fresh));
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> me(Authentication authentication) {
        AppUser user = currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        AppUser fresh = auth.reload(user.id());
        if (fresh == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        Map<String, Object> j = userJson(fresh);
        j.put("mfaEnabled", auth.mfaEnabled(fresh.id()));
        j.put("backupCodesLeft", totp.unusedBackupCodeCount(fresh.id()));
        return ResponseEntity.ok(Map.of("user", j));
    }

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authz) {
        auth.logout(bearer(authz));
        return Map.of("ok", true);
    }

    // ---- TOTP enrolment (authenticated) ----

    @PostMapping(value = "/totp/setup", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> totpSetup(Authentication authentication) {
        AppUser user = currentUser(authentication);
        if (user == null) {
            return unauthorized("unauthorized", "Login required.");
        }
        TotpService.Enrolment e = totp.startEnrolment(user.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secret", e.secret());
        out.put("otpauthUri", e.otpauthUri());
        out.put("qrDataUri", e.qrDataUri());
        return ResponseEntity.ok(out);
    }

    @PostMapping(value = "/totp/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> totpEnable(@RequestBody CodeRequest body, Authentication authentication) {
        AppUser user = currentUser(authentication);
        if (user == null) {
            return unauthorized("unauthorized", "Login required.");
        }
        List<String> codes = totp.enable(user.id(), body == null ? null : body.code());
        if (codes == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad_code", "message", "Kod niepoprawny."));
        }
        return ResponseEntity.ok(Map.of("backupCodes", codes));
    }

    @PostMapping(value = "/totp/disable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> totpDisable(@RequestBody CodeRequest body, Authentication authentication) {
        AppUser user = currentUser(authentication);
        if (user == null) {
            return unauthorized("unauthorized", "Login required.");
        }
        if (!totp.disable(user.id(), body == null ? null : body.code())) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad_code", "message", "Kod niepoprawny."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---- helpers ----

    private ResponseEntity<Object> session(AuthService.LoginResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", result.token());
        out.put("user", userJson(result.user()));
        return ResponseEntity.ok(out);
    }

    private static ResponseEntity<Object> unauthorized(String error, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", error);
        err.put("message", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
    }

    private static String bearer(String authz) {
        if (authz != null && authz.startsWith("Bearer ")) {
            return authz.substring("Bearer ".length()).trim();
        }
        return null;
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

    public record TotpLoginRequest(String mfaToken, String code) {
    }

    public record CodeRequest(String code) {
    }
}
