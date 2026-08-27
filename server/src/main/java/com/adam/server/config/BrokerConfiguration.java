package com.adam.server.config;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.UnavailableBrokerClient;
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

    @Bean("demoBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "capital", matchIfMissing = true)
    BrokerClient capitalDemoBroker(RestClient.Builder builder, AppProperties properties) {
        AppProperties.Endpoint demo = properties.getCapital().getDemo();
        if (demo.getHost() == null || demo.getHost().isBlank()) {
            demo.setHost("https://demo-api-capital.backend-capital.com");
        }
        return new CapitalComBrokerClient(
                builder,
                "demo",
                demo,
                "Capital.com DEMO credentials are not set (CAPITAL_API_KEY / CAPITAL_EMAIL / CAPITAL_API_PASSWORD, or CAPITAL_DEMO_*)"
        );
    }

    @Bean("liveBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "capital", matchIfMissing = true)
    BrokerClient capitalLiveBroker(RestClient.Builder builder, AppProperties properties) {
        AppProperties.Endpoint live = properties.getCapital().getLive();
        if (live.getHost() == null || live.getHost().isBlank()) {
            live.setHost("https://api-capital.backend-capital.com");
        }
        return new CapitalComBrokerClient(
                builder,
                "live",
                live,
                "Capital.com LIVE credentials are not set (CAPITAL_LIVE_API_KEY, CAPITAL_LIVE_EMAIL, CAPITAL_LIVE_PASSWORD)"
        );
    }

    @Bean("demoBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "paper")
    BrokerClient paperDemoBroker(Clock clock) {
        return new PaperBrokerClient(clock);
    }

    @Bean("liveBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "paper")
    BrokerClient paperLivePlaceholder() {
        return new UnavailableBrokerClient("live", "LIVE book is not wired in paper mode");
    }
}
