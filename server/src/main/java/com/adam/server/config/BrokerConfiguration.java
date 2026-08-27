package com.adam.server.config;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.capital.CapitalComBrokerClient;
import com.adam.server.broker.paper.PaperBrokerClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class BrokerConfiguration {

    @Bean
    Clock appClock(AppProperties properties) {
        return Clock.system(ZoneId.of(properties.getTimezone()));
    }

    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @ConditionalOnProperty(name = "app.broker", havingValue = "capital", matchIfMissing = true)
    BrokerClient capitalComBrokerClient(RestClient.Builder builder, AppProperties properties) {
        return new CapitalComBrokerClient(builder, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.broker", havingValue = "paper")
    BrokerClient paperBrokerClient(Clock clock) {
        return new PaperBrokerClient(clock);
    }
}
