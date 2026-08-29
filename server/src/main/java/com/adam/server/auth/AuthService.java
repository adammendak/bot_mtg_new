package com.adam.server.auth;

import com.adam.server.persistence.AppUserEntity;
import com.adam.server.persistence.AppUserRepository;
import com.adam.server.persistence.UserBookEntity;
import com.adam.server.persistence.UserBookRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Login/logout and the (in-memory) bearer-token store. Tokens are opaque UUIDs
 * kept in a {@link ConcurrentHashMap} — one server instance only, so Heroku's
 * single-dyno footprint is fine. A restart logs everyone out, which is
 * acceptable for this dashboard.
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final UserBookRepository userBooks;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, AppUser> tokens = new ConcurrentHashMap<>();

    public AuthService(AppUserRepository users, UserBookRepository userBooks, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.userBooks = userBooks;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        AppUserEntity entity = users.findByUsername(username.trim()).orElse(null);
        if (entity == null || !passwordEncoder.matches(password, entity.getPasswordHash())) {
            return null;
        }
        AppUser user = toAppUser(entity);
        String token = newToken();
        tokens.put(token, user);
        return new LoginResult(token, user);
    }

    public AppUser authenticate(String token) {
        return token == null ? null : tokens.get(token);
    }

    public void logout(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    @Transactional(readOnly = true)
    public AppUser reload(Long userId) {
        return users.findById(userId).map(this::toAppUser).orElse(null);
    }

    private AppUser toAppUser(AppUserEntity entity) {
        Set<String> books = userBooks.findByUserId(entity.getId()).stream()
                .map(UserBookEntity::getBook)
                .collect(Collectors.toSet());
        return new AppUser(entity.getId(), entity.getUsername(), entity.getDisplayName(), entity.getRole(), books);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." + UUID.randomUUID();
    }

    public record LoginResult(String token, AppUser user) {
    }

    public static String hashFor(String raw) {
        // Plain bcrypt — matches the PasswordEncoder bean (BCryptPasswordEncoder).
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(raw);
    }
}
