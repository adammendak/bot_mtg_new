package com.adam.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.dashboard.username=",
        "app.dashboard.password="
})
class DashboardAuthFailClosedTest {

    @Autowired
    MockMvc mvc;

    @Test
    void loginRejectedWhenDashboardEnvVarsMissing() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-user\",\"password\":\"test-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"anyone\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndScanStayOpenWhenLoginIsFailClosed() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(post("/api/scan")).andExpect(status().isOk());
        mvc.perform(get("/api/history")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("actuator/health must not be 401/403, was " + status);
                    }
                });
    }
}
