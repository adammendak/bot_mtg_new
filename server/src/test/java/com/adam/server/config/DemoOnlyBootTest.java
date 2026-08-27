package com.adam.server.config;

import com.adam.server.broker.BrokerBooks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.broker=capital",
        "app.capital.demo.api-key=demo-key",
        "app.capital.demo.email=demo@example.com",
        "app.capital.demo.password=demo-pass",
        "app.execution-enabled=false",
        "spring.task.scheduling.enabled=false",
        "app.news-calendar-url="
})
class DemoOnlyBootTest {

    @Autowired
    BrokerBooks books;

    @Test
    void appStartsWithOnlyDemoConfigured() {
        assertThat(books.demo().configured()).isTrue();
        assertThat(books.live().configured()).isFalse();
        assertThat(books.marketData()).isSameAs(books.demo());
    }
}
