package com.adam.server.auth;

import com.adam.server.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAuthServiceTest {

    @Test
    void acceptsConfiguredCredentials() {
        DashboardAuthService auth = service("test-user", "test-password");
        assertThat(auth.configured()).isTrue();
        assertThat(auth.authenticate("test-user", "test-password")).isTrue();
    }

    @Test
    void rejectsWrongPasswordOrUser() {
        DashboardAuthService auth = service("test-user", "test-password");
        assertThat(auth.authenticate("test-user", "wrong")).isFalse();
        assertThat(auth.authenticate("other", "test-password")).isFalse();
        assertThat(auth.authenticate(null, "test-password")).isFalse();
        assertThat(auth.authenticate("test-user", null)).isFalse();
    }

    @Test
    void failClosedWhenUsernameOrPasswordMissing() {
        assertThat(service("", "test-password").authenticate("test-user", "test-password")).isFalse();
        assertThat(service("test-user", "").authenticate("test-user", "test-password")).isFalse();
        assertThat(service(null, null).configured()).isFalse();
        assertThat(service("  ", "  ").configured()).isFalse();
    }

    private static DashboardAuthService service(String username, String password) {
        AppProperties properties = new AppProperties();
        properties.getDashboard().setUsername(username);
        properties.getDashboard().setPassword(password);
        return new DashboardAuthService(properties);
    }
}
