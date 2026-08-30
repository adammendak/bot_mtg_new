package com.adam.server.auth;

import com.adam.server.persistence.AppUserEntity;
import com.adam.server.persistence.AppUserRepository;
import com.adam.server.persistence.UserBookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-09-01T08:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
    }

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserBookRepository userBooks = mock(UserBookRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final MovableClock clock = new MovableClock();
    private AuthService auth;

    private AppUserEntity adam;

    @BeforeEach
    void setUp() {
        adam = new AppUserEntity();
        adam.setId(1L);
        adam.setUsername("adam");
        adam.setPasswordHash("hash");
        adam.setRole(UserRole.ADMIN);
        when(users.findByUsernameIgnoreCase("adam")).thenReturn(Optional.of(adam));
        when(users.findById(1L)).thenReturn(Optional.of(adam));
        when(userBooks.findByUserId(1L)).thenReturn(List.of());
        when(encoder.matches(anyString(), any())).thenReturn(false);
        when(encoder.matches("pw", "hash")).thenReturn(true);
        auth = new AuthService(users, userBooks, encoder, clock, 168, 24);
    }

    @Test
    void loginWithoutTotpIssuesAWorkingToken() {
        AuthService.LoginResult r = auth.login("adam", "pw");
        assertThat(r.mfaRequired()).isFalse();
        assertThat(auth.authenticate(r.token()).username()).isEqualTo("adam");
    }

    @Test
    void badCredentialsReturnNull() {
        assertThat(auth.login("adam", "nope")).isNull();
    }

    @Test
    void tokenExpiresOnTheAbsoluteLifetime() {
        String token = auth.login("adam", "pw").token();
        clock.advance(Duration.ofDays(8)); // ttl = 168h = 7d
        assertThat(auth.authenticate(token)).isNull();
    }

    @Test
    void tokenExpiresWhenIdlePastTheSlidingWindow() {
        String token = auth.login("adam", "pw").token();
        clock.advance(Duration.ofHours(23));
        assertThat(auth.authenticate(token)).isNotNull(); // resets lastSeen
        clock.advance(Duration.ofHours(23));
        assertThat(auth.authenticate(token)).isNotNull(); // still alive — kept active
        clock.advance(Duration.ofHours(25));
        assertThat(auth.authenticate(token)).isNull();    // idle too long
    }

    @Test
    void refreshRotatesTheTokenAndRevokesTheOld() {
        String old = auth.login("adam", "pw").token();
        String fresh = auth.refresh(old);
        assertThat(fresh).isNotEqualTo(old);
        assertThat(auth.authenticate(old)).isNull();
        assertThat(auth.authenticate(fresh)).isNotNull();
    }

    @Test
    void logoutRevokes() {
        String token = auth.login("adam", "pw").token();
        auth.logout(token);
        assertThat(auth.authenticate(token)).isNull();
    }

    @Test
    void totpAccountGetsAnMfaChallengeNotASession() {
        adam.setTotpEnabled(true);
        AuthService.LoginResult r = auth.login("adam", "pw");
        assertThat(r.mfaRequired()).isTrue();
        assertThat(r.token()).isNull();
        assertThat(auth.pendingMfaUserId(r.mfaToken())).isEqualTo(1L);

        assertThat(auth.completeMfa(r.mfaToken(), false)).isNull();       // wrong code
        AuthService.LoginResult done = auth.completeMfa(r.mfaToken(), true);
        assertThat(done.token()).isNotNull();
        assertThat(auth.completeMfa(r.mfaToken(), true)).isNull();        // challenge is single-use
    }

    @Test
    void sweepEvictsExpiredTokens() {
        String token = auth.login("adam", "pw").token();
        clock.advance(Duration.ofDays(9));
        auth.sweep();
        assertThat(auth.authenticate(token)).isNull();
    }
}
