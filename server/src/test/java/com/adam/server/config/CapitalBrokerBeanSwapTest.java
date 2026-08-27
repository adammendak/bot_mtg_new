package com.adam.server.config;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.capital.CapitalComBrokerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.broker=capital",
        "app.capital.demo.api-key=test-key",
        "app.capital.demo.email=user@example.com",
        "app.capital.demo.password=not-a-real-secret"
})
class CapitalBrokerBeanSwapTest {

    @Autowired
    @Qualifier("demoBroker")
    BrokerClient demoBroker;

    @Autowired
    @Qualifier("liveBroker")
    BrokerClient liveBroker;

    @Autowired
    BrokerBooks books;

    @Test
    void capitalPropertySelectsTwoCapitalAdapters() {
        assertThat(demoBroker).isInstanceOf(CapitalComBrokerClient.class);
        assertThat(demoBroker.id()).isEqualTo("capital");
        assertThat(demoBroker.book()).isEqualTo("demo");
        assertThat(demoBroker.displayName()).contains("demo");
        assertThat(demoBroker.configured()).isTrue();
        assertThat(demoBroker.isSessionOpen()).isFalse();

        assertThat(liveBroker).isInstanceOf(CapitalComBrokerClient.class);
        assertThat(liveBroker.book()).isEqualTo("live");
        assertThat(liveBroker.configured()).isFalse();
        assertThat(books.live()).isSameAs(liveBroker);
    }
}
