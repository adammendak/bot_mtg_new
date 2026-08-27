package com.adam.server.config;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.capital.CapitalComBrokerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "app.broker=capital",
        "app.capital.api-key=test-key",
        "app.capital.email=user@example.com",
        "app.capital.password=not-a-real-secret"
})
class CapitalBrokerBeanSwapTest {

    @Autowired
    BrokerClient brokerClient;

    @Test
    void capitalPropertySelectsCapitalAdapter() {
        assertThat(brokerClient).isInstanceOf(CapitalComBrokerClient.class);
        assertThat(brokerClient.id()).isEqualTo("capital");
        assertThat(brokerClient.displayName()).contains("Capital.com");
        assertThat(brokerClient.isSessionOpen()).isFalse();
    }
}
