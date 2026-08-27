package com.adam.server.config;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.paper.PaperBrokerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PaperBrokerBeanTest {

    @Autowired
    BrokerClient brokerClient;

    @Test
    void testProfileWiresPaperAdapter() {
        assertThat(brokerClient).isInstanceOf(PaperBrokerClient.class);
        assertThat(brokerClient.id()).isEqualTo("paper");
        assertThat(brokerClient.displayName()).contains("Paper");
    }
}
