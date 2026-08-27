package com.adam.server.config;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.paper.PaperBrokerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PaperBrokerBeanTest {

    @Autowired
    @Qualifier("demoBroker")
    BrokerClient demoBroker;

    @Autowired
    BrokerBooks books;

    @Test
    void testProfileWiresPaperAdapterOnDemoBook() {
        assertThat(demoBroker).isInstanceOf(PaperBrokerClient.class);
        assertThat(demoBroker.id()).isEqualTo("paper");
        assertThat(books.demo()).isSameAs(demoBroker);
        assertThat(books.live().configured()).isFalse();
        assertThat(books.live().book()).isEqualTo("live");
    }
}
