package com.adam.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogPostgresSqlTest {

    @Test
    void masterChangelogIsXmlAndCreatesTablesWithPostgresTypes() throws Exception {
        String master = read("db/changelog/db.changelog-master.xml");
        String tables = read("db/changelog/changes/003-app-tables.xml");
        assertThat(master).contains("003-app-tables.xml");
        assertThat(tables).contains("sdd_scans", "sdd_signals", "payments");
        assertThat(tables).contains("TEXT", "DOUBLE PRECISION");
        assertThat(tables).doesNotContain("type=\"CLOB\"", "type=\"clob\"", "type=\"DOUBLE\"", "type=\"double\"");
    }

    private static String read(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
