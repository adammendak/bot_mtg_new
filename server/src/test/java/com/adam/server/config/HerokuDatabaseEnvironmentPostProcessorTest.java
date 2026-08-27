package com.adam.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class HerokuDatabaseEnvironmentPostProcessorTest {

    @Test
    void switchesToPostgresWithoutLoggingPasswordInUrl() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "postgres://bot:super-secret@db.example.com:5432/app");
        new HerokuDatabaseEnvironmentPostProcessor()
                .postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/app?sslmode=require");
        assertThat(env.getProperty("spring.datasource.url")).doesNotContain("super-secret");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("bot");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("super-secret");
        assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
        assertThat(env.getProperty("spring.jpa.database-platform")).contains("PostgreSQL");
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(env.getProperty("app.db")).isEqualTo("postgres");
        assertThat(env.getProperty("spring.h2.console.enabled")).isEqualTo("false");
    }

    @Test
    void testProfileKeepsH2() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("DATABASE_URL", "postgres://bot:super-secret@db.example.com:5432/app");
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:botdb");
        new HerokuDatabaseEnvironmentPostProcessor()
                .postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:botdb");
    }

    @Test
    void missingDatabaseUrlDoesNothing() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:botdb");
        new HerokuDatabaseEnvironmentPostProcessor()
                .postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:botdb");
        assertThat(env.getProperty("app.db")).isNull();
    }
}
