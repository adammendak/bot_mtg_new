package com.adam.server.auth;

import com.adam.server.persistence.AppUserEntity;
import com.adam.server.persistence.AppUserRepository;
import com.adam.server.persistence.UserBookEntity;
import com.adam.server.persistence.UserBookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Login/logout and the (in-memory) bearer-token store. Tokens are opaque and
 * <b>expire</b> (E-7): an absolute lifetime ({@code app.auth.token-ttl-hours},
 * default 7 days) and a sliding idle window ({@code app.auth.token-idle-hours},
 * default 24 h) that resets on each authenticated request. A stale token is
 * evicted on next use and by an hourly sweep. One server instance only, so the
 * map is fine; a restart logs everyone out.
 *
 * <p>When a user has TOTP enabled, {@link #login} does not return a session
 * token — it returns a short-lived {@code mfaToken}; the caller must then post
 * the 6-digit code to {@link #completeMfa}.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration MFA_TTL = Duration.ofMinutes(5);

    private final AppUserRepository users;
    private final UserBookRepository userBooks;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration absoluteTtl;
    private final Duration idleTtl;

    private final Map<String, Session> tokens = new ConcurrentHashMap<>();
    private final Map<String, PendingMfa> mfa = new ConcurrentHashMap<>();

    public AuthService(AppUserRepository users, UserBookRepository userBooks, PasswordEncoder passwordEncoder,
                       Clock clock,
                       @Value("${app.auth.token-ttl-hours:168}") long ttlHours,
                       @Value("${app.auth.token-idle-hours:24}") long idleHours) {
        this.users = users;
        this.userBooks = userBooks;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.absoluteTtl = Duration.ofHours(Math.max(1, ttlHours));
        this.idleTtl = Duration.ofHours(Math.max(1, idleHours));
    }

    /**
     * First factor. Returns {@code MFA_REQUIRED} (token null, mfaToken set) when
     * the account has TOTP; a full session otherwise; {@code null} on bad creds.
     */
    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        AppUserEntity entity = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (entity == null || !passwordEncoder.matches(password, entity.getPasswordHash())) {
            return null;
        }
        if (entity.isTotpEnabled()) {
            String mfaToken = newToken();
            mfa.put(mfaToken, new PendingMfa(entity.getId(), clock.instant()));
            return LoginResult.mfaRequired(mfaToken);
        }
        return LoginResult.session(issue(toAppUser(entity)), toAppUser(entity));
    }

    /** Second factor. {@code code} is a TOTP code or an unused backup code. */
    public LoginResult completeMfa(String mfaToken, boolean codeOk) {
        PendingMfa pending = mfaToken == null ? null : mfa.get(mfaToken);
        if (pending == null || Duration.between(pending.issuedAt(), clock.instant()).compareTo(MFA_TTL) > 0) {
            mfa.remove(mfaToken);
            return null;
        }
        if (!codeOk) {
            return null;
        }
        mfa.remove(mfaToken);
        AppUser user = reload(pending.userId());
        if (user == null) {
            return null;
        }
        return LoginResult.session(issue(user), user);
    }

    /** Resolve the pending MFA login to its user id (for the code check), or null if expired. */
    public Long pendingMfaUserId(String mfaToken) {
        PendingMfa p = mfaToken == null ? null : mfa.get(mfaToken);
        if (p == null || Duration.between(p.issuedAt(), clock.instant()).compareTo(MFA_TTL) > 0) {
            return null;
        }
        return p.userId();
    }

    public AppUser authenticate(String token) {
        Session s = token == null ? null : tokens.get(token);
        if (s == null) {
            return null;
        }
        Instant now = clock.instant();
        if (Duration.between(s.issuedAt, now).compareTo(absoluteTtl) > 0
                || Duration.between(s.lastSeenAt, now).compareTo(idleTtl) > 0) {
            tokens.remove(token);
            return null;
        }
        s.lastSeenAt = now;
        return s.user;
    }

    /** Rotate: mint a new token for the same user and revoke the old one. */
    public String refresh(String oldToken) {
        AppUser user = authenticate(oldToken);
        if (user == null) {
            return null;
        }
        tokens.remove(oldToken);
        return issue(user);
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

    @Transactional(readOnly = true)
    public boolean mfaEnabled(Long userId) {
        return users.findById(userId).map(AppUserEntity::isTotpEnabled).orElse(false);
    }

    @Scheduled(fixedDelayString = "${app.auth.sweep-ms:3600000}")
    public void sweep() {
        Instant now = clock.instant();
        int gone = 0;
        for (Iterator<Map.Entry<String, Session>> it = tokens.entrySet().iterator(); it.hasNext(); ) {
            Session s = it.next().getValue();
            if (Duration.between(s.issuedAt, now).compareTo(absoluteTtl) > 0
                    || Duration.between(s.lastSeenAt, now).compareTo(idleTtl) > 0) {
                it.remove();
                gone++;
            }
        }
        mfa.entrySet().removeIf(e -> Duration.between(e.getValue().issuedAt(), now).compareTo(MFA_TTL) > 0);
        if (gone > 0) {
            log.info("Auth sweep: evicted {} expired token(s)", gone);
        }
    }

    private String issue(AppUser user) {
        String token = newToken();
        Instant now = clock.instant();
        tokens.put(token, new Session(user, now, now));
        return token;
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

    private static final class Session {
        private final AppUser user;
        private final Instant issuedAt;
        private volatile Instant lastSeenAt;

        Session(AppUser user, Instant issuedAt, Instant lastSeenAt) {
            this.user = user;
            this.issuedAt = issuedAt;
            this.lastSeenAt = lastSeenAt;
        }
    }

    private record PendingMfa(Long userId, Instant issuedAt) {
    }

    public record LoginResult(String token, AppUser user, String mfaToken) {
        static LoginResult session(String token, AppUser user) {
            return new LoginResult(token, user, null);
        }

        static LoginResult mfaRequired(String mfaToken) {
            return new LoginResult(null, null, mfaToken);
        }

        public boolean mfaRequired() {
            return token == null && mfaToken != null;
        }
    }

    /**
     * One shared encoder so the seed / admin-created hash format can never drift
     * from what the {@code PasswordEncoder} bean in {@code SecurityConfiguration}
     * verifies. Plain bcrypt, no {@code {bcrypt}} prefix.
     */
    private static final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder HASH_ENCODER =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public static String hashFor(String raw) {
        return HASH_ENCODER.encode(raw);
    }
}
