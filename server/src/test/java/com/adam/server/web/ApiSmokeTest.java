package com.adam.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiSmokeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsOpen() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.broker").value("paper"));
    }

    @Test
    void brokerEndpointUsesSpi() throws Exception {
        mvc.perform(get("/api/broker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("paper"))
                .andExpect(jsonPath("$.name").value("Paper (stub)"))
                .andExpect(jsonPath("$.executionEnabled").value(false));
    }

    @Test
    void lastScanAndSignalsAreOpen() throws Exception {
        mvc.perform(get("/api/scan/last")).andExpect(status().isOk());
        mvc.perform(get("/api/signals")).andExpect(status().isOk());
        mvc.perform(get("/api/positions")).andExpect(status().isOk());
    }

    @Test
    void manualScanWithPaperBroker() throws Exception {
        mvc.perform(post("/api/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerId").value("paper"))
                .andExpect(jsonPath("$.symbols").isArray());
    }
}
