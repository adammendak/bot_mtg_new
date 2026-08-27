package com.adam.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSmokeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void liquibaseCreatedAppTables() {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.tables where lower(table_name) in ('payments','sdd_scans','sdd_signals','broker_snapshots')",
                Integer.class
        );
        assertThat(n).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from payments", Integer.class)).isZero();
    }

    @Test
    void healthIsOpen() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.broker").value("paper"))
                .andExpect(jsonPath("$.demoConfigured").value(true))
                .andExpect(jsonPath("$.liveConfigured").value(false));
    }

    @Test
    void brokerEndpointListsBothBooks() throws Exception {
        mvc.perform(get("/api/broker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.books", hasSize(2)))
                .andExpect(jsonPath("$.books[0].id").value("demo"))
                .andExpect(jsonPath("$.books[1].id").value("live"));
    }

    @Test
    void accountsEndpointReturnsDemoAndLive() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("demo"))
                .andExpect(jsonPath("$[0].connected").value(true))
                .andExpect(jsonPath("$[0].accountName").value("paper"))
                .andExpect(jsonPath("$[0].equity").value(1000))
                .andExpect(jsonPath("$[1].id").value("live"))
                .andExpect(jsonPath("$[1].connected").value(false));
    }

    @Test
    void lastScanAndSignalsAreOpen() throws Exception {
        mvc.perform(get("/api/scan/last")).andExpect(status().isOk());
        mvc.perform(get("/api/signals")).andExpect(status().isOk());
        mvc.perform(get("/api/positions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").isArray())
                .andExpect(jsonPath("$.live").isArray());
        mvc.perform(get("/api/positions").param("account", "demo")).andExpect(status().isOk());
        mvc.perform(get("/api/positions").param("account", "live")).andExpect(status().isOk());
    }

    @Test
    void manualScanWithPaperBroker() throws Exception {
        mvc.perform(post("/api/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerId").value("paper"))
                .andExpect(jsonPath("$.symbols").isArray())
                .andExpect(jsonPath("$.books", hasSize(2)))
                .andExpect(jsonPath("$.books[0].id").value("demo"))
                .andExpect(jsonPath("$.books[1].id").value("live"));
    }
}
