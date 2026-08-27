package com.adam.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * When Heroku (or any host) sets {@code DATABASE_URL}, switch off hardcoded H2 and
 * bind PostgreSQL. Credentials are split out of the JDBC URL so they are not logged.
 */
public class HerokuDatabaseEnvironmentPostProcessor
        implements org.springframework.boot.EnvironmentPostProcessor,
        org.springframework.boot.env.EnvironmentPostProcessor,
        Ordered {

    private static final Logger log = LoggerFactory.getLogger(HerokuDatabaseEnvironmentPostProcessor.class);

    public static final String PROPERTY_SOURCE_NAME = "herokuDatabaseUrl";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        if (environment.matchesProfiles("test")) {
            return;
        }
        String raw = firstNonBlank(
                environment.getProperty("DATABASE_URL"),
                environment.getProperty("JDBC_DATABASE_URL")
        );
        if (raw == null) {
            return;
        }
        Optional<DatabaseUrl.Parsed> parsed;
        try {
            parsed = DatabaseUrl.parse(raw);
        } catch (IllegalArgumentException e) {
            log.error("DATABASE_URL is set but could not be parsed; refusing to start with H2 in production");
            throw e;
        }
        if (parsed.isEmpty()) {
            return;
        }
        DatabaseUrl.Parsed db = parsed.get();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.datasource.url", db.jdbcUrl());
        if (db.username() != null) {
            props.put("spring.datasource.username", db.username());
        }
        if (db.password() != null) {
            props.put("spring.datasource.password", db.password());
        }
        props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        props.put("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("spring.jpa.hibernate.ddl-auto", "none");
        props.put("spring.h2.console.enabled", "false");
        props.put("spring.autoconfigure.exclude", "org.springframework.boot.h2console.autoconfigure.H2ConsoleAutoConfiguration");
        props.put("app.db", "postgres");
        props.put("logging.level.org.hibernate.orm.connections.pooling", "WARN");
        props.put("logging.level.com.zaxxer.hikari.HikariConfig", "WARN");
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        log.info("Using PostgreSQL because DATABASE_URL is set (Liquibase owns the schema)");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
