package com.adam.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUrlTest {

    @Test
    void convertsHerokuPostgresUrlAndStripsPassword() {
        DatabaseUrl.Parsed parsed = DatabaseUrl.parse(
                "postgres://bot:s3cret%21@ec2-1-2-3-4.compute-1.amazonaws.com:5432/dabc"
        ).orElseThrow();
        assertThat(parsed.jdbcUrl())
                .isEqualTo("jdbc:postgresql://ec2-1-2-3-4.compute-1.amazonaws.com:5432/dabc?sslmode=require");
        assertThat(parsed.jdbcUrl()).doesNotContain("s3cret");
        assertThat(parsed.username()).isEqualTo("bot");
        assertThat(parsed.password()).isEqualTo("s3cret!");
        assertThat(parsed.toString()).doesNotContain("s3cret");
    }

    @Test
    void convertsJdbcDatabaseUrlAndRemovesEmbeddedCredentials() {
        DatabaseUrl.Parsed parsed = DatabaseUrl.parse(
                "jdbc:postgresql://ec2-host:5432/dabc?sslmode=require&user=bot&password=hidden"
        ).orElseThrow();
        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://ec2-host:5432/dabc?sslmode=require");
        assertThat(parsed.jdbcUrl()).doesNotContain("hidden");
        assertThat(parsed.username()).isEqualTo("bot");
        assertThat(parsed.password()).isEqualTo("hidden");
    }

    @Test
    void localhostDoesNotForceSsl() {
        DatabaseUrl.Parsed parsed = DatabaseUrl.parse("postgres://u:p@localhost:5432/bot").orElseThrow();
        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/bot");
        assertThat(parsed.jdbcUrl()).doesNotContain("sslmode");
    }

    @Test
    void emptyIsAbsent() {
        assertThat(DatabaseUrl.parse(null)).isEmpty();
        assertThat(DatabaseUrl.parse(" ")).isEmpty();
        assertThat(DatabaseUrl.parse("jdbc:h2:mem:botdb")).isEmpty();
    }

    @Test
    void invalidThrowsWithoutEchoingSecret() {
        assertThatThrownBy(() -> DatabaseUrl.parse("postgres://only-user-no-host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid DATABASE_URL")
                .hasMessageNotContaining("only-user");
    }
}
