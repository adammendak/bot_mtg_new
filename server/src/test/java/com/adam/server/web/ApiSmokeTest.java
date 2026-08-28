package com.adam.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void staticDashboardServesBootstrapTables() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SDD-M15")))
                .andExpect(content().string(containsString("table table-sm table-striped table-hover")))
                .andExpect(content().string(containsString("/api/accounts")))
                .andExpect(content().string(containsString("brak pozycji")))
                .andExpect(content().string(containsString("HTTP ")))
                .andExpect(content().string(containsString("GET /api/accounts failed")))
                .andExpect(content().string(containsString("cdn.jsdelivr.net/npm/bootstrap@5.3.3")));
    }

    @Test
    void healthIsOpen() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.time").isString())
                .andExpect(jsonPath("$.broker").value("paper"))
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.demoConfigured").value(true))
                .andExpect(jsonPath("$.liveConfigured").value(false))
                .andExpect(jsonPath("$.webhookConfigured").value(false))
                .andExpect(jsonPath("$.lastWebhook").value("never"))
                .andExpect(jsonPath("$.lastWebhookAt").value(nullValue()));
    }

    @Test
    void actuatorHealthIsUpWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void actuatorDoesNotExposeSensitiveEndpoints() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }

    @Test
    void legacyV1StatesBybitBinanceWereNeverInThisRepo() throws Exception {
        mvc.perform(get("/api/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false))
                .andExpect(jsonPath("$.defaultBroker").value("capital"));
        mvc.perform(get("/api/legacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false));
    }

    @Test
    void brokerEndpointListsBothBooks() throws Exception {
        mvc.perform(get("/api/broker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.books", hasSize(3)))
                .andExpect(jsonPath("$.books[0].id").value("demo"))
                .andExpect(jsonPath("$.books[1].id").value("live"))
                .andExpect(jsonPath("$.books[2].id").value("glowne"));
    }

    @Test
    void accountsEndpointReturnsAllBooks() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value("demo"))
                .andExpect(jsonPath("$[0].connected").value(true))
                .andExpect(jsonPath("$[0].accountName").value("paper"))
                .andExpect(jsonPath("$[0].equity").value(1000))
                .andExpect(jsonPath("$[1].id").value("live"))
                .andExpect(jsonPath("$[1].connected").value(false))
                .andExpect(jsonPath("$[2].id").value("glowne"))
                .andExpect(jsonPath("$[2].connected").value(false));
    }

    @Test
    void lastScanAndSignalsAreOpen() throws Exception {
        mvc.perform(get("/api/scan/last")).andExpect(status().isOk());
        mvc.perform(get("/api/signals")).andExpect(status().isOk());
        mvc.perform(get("/api/positions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").isArray())
                .andExpect(jsonPath("$.live").isArray())
                .andExpect(jsonPath("$.glowne").isArray());
        mvc.perform(get("/api/positions").param("account", "demo")).andExpect(status().isOk());
        mvc.perform(get("/api/positions").param("account", "live")).andExpect(status().isOk());
    }

    @Test
    void historyEndpointIsOpen() throws Exception {
        mvc.perform(get("/api/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book").value("demo"))
                .andExpect(jsonPath("$.points").isArray());
        mvc.perform(get("/api/history").param("book", "live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book").value("live"));
    }

    @Test
    void manualScanWithPaperBroker() throws Exception {
        mvc.perform(post("/api/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerId").value("paper"))
                .andExpect(jsonPath("$.symbols").isArray())
                .andExpect(jsonPath("$.books", hasSize(3)))
                .andExpect(jsonPath("$.books[0].id").value("demo"))
                .andExpect(jsonPath("$.books[1].id").value("live"))
                .andExpect(jsonPath("$.books[2].id").value("glowne"));
    }
}
