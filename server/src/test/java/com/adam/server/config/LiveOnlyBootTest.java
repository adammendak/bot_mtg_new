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
        "app.capital.live.api-key=live-key",
        "app.capital.live.email=live@example.com",
        "app.capital.live.password=live-pass",
        "app.execution-enabled=false",
        "spring.task.scheduling.enabled=false",
        "app.news-calendar-url="
})
class LiveOnlyBootTest {

    @Autowired
    BrokerBooks books;

    @Test
    void appStartsWithOnlyLiveConfigured() {
        assertThat(books.demo().configured()).isFalse();
        assertThat(books.live().configured()).isTrue();
        assertThat(books.marketData()).isSameAs(books.live());
    }
}
