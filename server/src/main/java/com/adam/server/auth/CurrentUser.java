package com.adam.server.auth;

import org.springframework.security.core.Authentication;

/**
 * Extracts the authenticated {@link AppUser} from the Spring Security context.
 * Endpoints secured by {@code /api/**} always have one; callers should treat a
 * {@code null} as "no token" and answer 401/403.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AppUser of(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user;
        }
        return null;
    }
}
