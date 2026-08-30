package com.adam.server.web;

import com.adam.server.auth.AppUser;
import com.adam.server.auth.CurrentUser;
import com.adam.server.ops.FeatureFlags;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Runtime feature flags (E-6), admin-only ({@code /api/admin/** -> hasRole(ADMIN)}).
 * <ul>
 *   <li>{@code GET /api/admin/flags} — every known flag with its effective value.</li>
 *   <li>{@code PUT /api/admin/flags/{name}} {@code {"enabled":true}} — override.</li>
 *   <li>{@code DELETE /api/admin/flags/{name}} — revert to the env default.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/flags")
public class FeatureFlagController {

    private final FeatureFlags flags;

    public FeatureFlagController(FeatureFlags flags) {
        this.flags = flags;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FeatureFlags.FlagView> list() {
        return flags.list();
    }

    @PutMapping(path = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> set(@PathVariable String name, @RequestBody Toggle body,
                                      Authentication authentication) {
        if (!flags.isKnown(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown_flag", "name", name));
        }
        AppUser user = CurrentUser.of(authentication);
        flags.set(name, body != null && body.enabled(), user == null ? "admin" : user.username());
        return ResponseEntity.ok(flags.list());
    }

    @DeleteMapping(path = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> reset(@PathVariable String name) {
        if (!flags.isKnown(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown_flag", "name", name));
        }
        flags.reset(name);
        return ResponseEntity.ok(flags.list());
    }

    public record Toggle(boolean enabled) {
    }
}
