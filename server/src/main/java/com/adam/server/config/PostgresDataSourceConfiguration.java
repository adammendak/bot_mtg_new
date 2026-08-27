package com.adam.server.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.sql.DataSource;

/**
 * When DATABASE_URL is set (Heroku Postgres), bind a Postgres DataSource before
 * Liquibase/JPA start. Local runs without DATABASE_URL keep the H2 properties.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(PostgresDataSourceConfiguration.OnDatabaseUrl.class)
@AutoConfigureBefore({
        DataSourceAutoConfiguration.class,
        LiquibaseAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
public class PostgresDataSourceConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(DataSource.class)
    DataSource postgresDataSource(Environment environment) {
        String raw = firstNonBlank(
                environment.getProperty("DATABASE_URL"),
                environment.getProperty("JDBC_DATABASE_URL")
        );
        DatabaseUrl.Parsed parsed = DatabaseUrl.parse(raw)
                .orElseThrow(() -> new IllegalStateException(
                        "DATABASE_URL is set but is not postgres:// or jdbc:postgresql://"));
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("sdd-postgres");
        ds.setJdbcUrl(parsed.jdbcUrl());
        ds.setUsername(parsed.username());
        ds.setPassword(parsed.password());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @Bean
    HibernatePropertiesCustomizer postgresHibernateCustomizer() {
        return hibernate -> {
            hibernate.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            hibernate.put("hibernate.hbm2ddl.auto", "none");
        };
    }

    static final class OnDatabaseUrl implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (context.getEnvironment().matchesProfiles("test")) {
                return false;
            }
            Environment env = context.getEnvironment();
            return notBlank(env.getProperty("DATABASE_URL"))
                    || notBlank(env.getProperty("JDBC_DATABASE_URL"));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
