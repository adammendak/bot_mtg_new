package com.adam.server.web;

import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    private String adminToken;
    private String testToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = login("adam", "dupa1234");
        testToken = login("test", "dupa1234");
        assertThat(adminToken).isNotBlank();
        assertThat(testToken).isNotBlank();
    }

    private String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        String resp = mvc.perform(post("/api/auth/login").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return resp.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void liquibaseCreatedAppTables() {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.tables where lower(table_name) in ('payments','sdd_scans','sdd_signals','broker_snapshots','app_users','user_books')",
                Integer.class
        );
        assertThat(n).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from payments", Integer.class)).isZero();
    }

    @Test
    void seedCreatedUsers() {
        assertThat(jdbc.queryForObject("select count(*) from app_users", Integer.class)).isEqualTo(2);
        // adam: live + glowne (006) + swing (008) + hts (010); test: demo (006) + swing (008) + hts (010)
        assertThat(jdbc.queryForObject(
                "select count(*) from user_books where user_id = (select id from app_users where username='adam')",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "select count(*) from user_books where user_id = (select id from app_users where username='test')",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void loginIsCaseInsensitiveOnUsername() throws Exception {
        String body = "{\"username\":\"Adam\",\"password\":\"dupa1234\"}";
        mvc.perform(post("/api/auth/login").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("adam"));
    }

    @Test
    void staticDashboardServesBootstrapTables() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loadAccounts")));
    }

    @Test
    void healthIsOpen() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.broker").value("paper"))
                .andExpect(jsonPath("$.demoConfigured").value(true))
                .andExpect(jsonPath("$.liveConfigured").value(false));
    }

    @Test
    void actuatorHealthIsUpWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void actuatorDoesNotExposeSensitiveEndpoints() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
    }

    @Test
    void legacyV1StatesBybitBinanceWereNeverInThisRepo() throws Exception {
        mvc.perform(get("/api/v1").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false))
                .andExpect(jsonPath("$.defaultBroker").value("capital"));
        mvc.perform(get("/api/legacy").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false));
    }

    @Test
    void brokerEndpointListsBooksForAdmin() throws Exception {
        // admin sees every book (ADMIN bypasses the user_books grants)
        mvc.perform(get("/api/broker").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.books", hasSize(5)))
                .andExpect(jsonPath("$.books[0].id").value("demo"))
                .andExpect(jsonPath("$.books[1].id").value("live"))
                .andExpect(jsonPath("$.books[2].id").value("glowne"))
                .andExpect(jsonPath("$.books[3].id").value("swing"))
                .andExpect(jsonPath("$.books[4].id").value("hts"));
    }

    @Test
    void accountsEndpointReturnsAllBooksForAdmin() throws Exception {
        mvc.perform(get("/api/accounts").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].id").value("demo"))
                .andExpect(jsonPath("$[0].connected").value(true))
                .andExpect(jsonPath("$[0].equity").value(1000))
                .andExpect(jsonPath("$[1].id").value("live"))
                .andExpect(jsonPath("$[1].connected").value(false))
                .andExpect(jsonPath("$[2].id").value("glowne"))
                .andExpect(jsonPath("$[3].id").value("swing"))
                .andExpect(jsonPath("$[3].connected").value(false))
                .andExpect(jsonPath("$[4].id").value("hts"))
                .andExpect(jsonPath("$[4].connected").value(false));
    }

    @Test
    void testUserSeesOnlyGrantedBooks() throws Exception {
        // test is granted demo (006) + swing (008) + hts (010) — not live / glowne
        mvc.perform(get("/api/accounts").header("Authorization", bearer(testToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value("demo"))
                .andExpect(jsonPath("$[0].connected").value(true))
                .andExpect(jsonPath("$[0].equity").value(1000))
                .andExpect(jsonPath("$[1].id").value("swing"))
                .andExpect(jsonPath("$[1].connected").value(false))
                .andExpect(jsonPath("$[2].id").value("hts"))
                .andExpect(jsonPath("$[2].connected").value(false));
        mvc.perform(get("/api/positions").header("Authorization", bearer(testToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").isArray())
                .andExpect(jsonPath("$.swing").isArray())
                .andExpect(jsonPath("$.hts").isArray())
                .andExpect(jsonPath("$.live").doesNotExist())
                .andExpect(jsonPath("$.glowne").doesNotExist());
    }

    @Test
    void anonymousIsRejectedFromProtectedApi() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/broker")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/positions")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        String body = "{\"username\":\"adam\",\"password\":\"nope\"}";
        mvc.perform(post("/api/auth/login").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swingLastIsOkBeforeAnyScan() throws Exception {
        // Regression: SwingController.last() used Map.of, which NPEs on the null
        // scannedAt before the first scan -> 500.
        mvc.perform(get("/api/swing/last").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals").isArray());
        mvc.perform(get("/api/swing/last")).andExpect(status().isUnauthorized());
    }

    @Test
    void lastScanAndSignalsRequireAuth() throws Exception {
        mvc.perform(get("/api/scan/last").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/signals").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/positions").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").isArray())
                .andExpect(jsonPath("$.live").isArray())
                .andExpect(jsonPath("$.glowne").isArray())
                .andExpect(jsonPath("$.swing").isArray());
    }

    @Test
    void historyEndpointIsAuthProtectedAndIsolated() throws Exception {
        mvc.perform(get("/api/history").param("book", "live").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book").value("live"));
        // test user cannot read live history — empty response
        mvc.perform(get("/api/history").param("book", "live").header("Authorization", bearer(testToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book").doesNotExist());
    }

    @Test
    void manualScanWithPaperBroker() throws Exception {
        mvc.perform(post("/api/scan").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerId").value("paper"))
                .andExpect(jsonPath("$.symbols").isArray())
                .andExpect(jsonPath("$.books", hasSize(3)));
    }

    @Test
    void adminUserCrud() throws Exception {
        String createBody = "{\"username\":\"newuser\",\"displayName\":\"New User\",\"password\":\"secret1\",\"role\":\"USER\",\"books\":[\"demo\"]}";
        mvc.perform(post("/api/admin/users").contentType("application/json").content(createBody)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.books", hasSize(1)));
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
        // non-admin cannot access
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(testToken)))
                .andExpect(status().isForbidden());
        // cleanup
        Long id = jdbc.queryForObject("select id from app_users where username='newuser'", Long.class);
        mvc.perform(delete("/api/admin/users/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
    }
}
