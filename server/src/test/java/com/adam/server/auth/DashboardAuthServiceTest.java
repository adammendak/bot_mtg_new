package com.adam.server.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAuthServiceTest {

    @Test
    void acceptsConfiguredCredentials() {
        DashboardAuthService auth = new DashboardAuthService("test-user", "test-password");
        assertThat(auth.configured()).isTrue();
        assertThat(auth.authenticate("test-user", "test-password")).isTrue();
    }

    @Test
    void rejectsWrongPasswordOrUser() {
        DashboardAuthService auth = new DashboardAuthService("test-user", "test-password");
        assertThat(auth.authenticate("test-user", "wrong")).isFalse();
        assertThat(auth.authenticate("other", "test-password")).isFalse();
        assertThat(auth.authenticate(null, "test-password")).isFalse();
        assertThat(auth.authenticate("test-user", null)).isFalse();
    }

    @Test
    void failClosedWhenUsernameOrPasswordMissing() {
        assertThat(new DashboardAuthService("", "test-password").authenticate("test-user", "test-password")).isFalse();
        assertThat(new DashboardAuthService("test-user", "").authenticate("test-user", "test-password")).isFalse();
        assertThat(new DashboardAuthService(null, null).configured()).isFalse();
        assertThat(new DashboardAuthService("  ", "  ").configured()).isFalse();
    }
}
