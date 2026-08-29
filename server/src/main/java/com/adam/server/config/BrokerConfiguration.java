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

/**
 * Capital.com is the default live adapter ({@code BROKER=capital}). Paper proves the SPI swap.
 * Bybit/Binance beans belong here when a real implementation exists; this repo has none.
 */
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

    @Bean("glowneBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "capital", matchIfMissing = true)
    BrokerClient capitalGlowneBroker(RestClient.Builder builder, AppProperties properties) {
        AppProperties.Endpoint glowne = properties.getCapital().getGlowne();
        if (glowne.getHost() == null || glowne.getHost().isBlank()) {
            glowne.setHost("https://api-capital.backend-capital.com");
        }
        return new CapitalComBrokerClient(
                builder,
                "glowne",
                glowne,
                "Capital.com GLOWNE credentials are not set (CAPITAL_GLOWNE_API_KEY / CAPITAL_GLOWNE_EMAIL / CAPITAL_GLOWNE_PASSWORD)"
        );
    }

    @Bean("swingBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "capital", matchIfMissing = true)
    BrokerClient capitalSwingBroker(RestClient.Builder builder, AppProperties properties) {
        AppProperties.Endpoint swing = properties.getCapital().getSwing();
        if (swing.getHost() == null || swing.getHost().isBlank()) {
            swing.setHost("https://demo-api-capital.backend-capital.com");
        }
        return new CapitalComBrokerClient(
                builder,
                "swing",
                swing,
                "Capital.com SWING credentials are not set (CAPITAL_SWING_API_KEY / CAPITAL_SWING_EMAIL / CAPITAL_SWING_PASSWORD)"
        );
    }

    @Bean("htsBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "capital", matchIfMissing = true)
    BrokerClient capitalHtsBroker(RestClient.Builder builder, AppProperties properties) {
        AppProperties.Endpoint hts = properties.getCapital().getHts();
        if (hts.getHost() == null || hts.getHost().isBlank()) {
            hts.setHost("https://demo-api-capital.backend-capital.com");
        }
        return new CapitalComBrokerClient(
                builder,
                "hts",
                hts,
                "Capital.com HTS credentials are not set (CAPITAL_HTS_API_KEY / CAPITAL_HTS_EMAIL / CAPITAL_HTS_PASSWORD)"
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

    @Bean("glowneBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "paper")
    BrokerClient paperGlownePlaceholder() {
        return new UnavailableBrokerClient("glowne", "GLOWNE book is not wired in paper mode");
    }

    @Bean("swingBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "paper")
    BrokerClient paperSwingPlaceholder() {
        return new UnavailableBrokerClient("swing", "SWING book is not wired in paper mode");
    }

    @Bean("htsBroker")
    @ConditionalOnProperty(name = "app.broker", havingValue = "paper")
    BrokerClient paperHtsPlaceholder() {
        return new UnavailableBrokerClient("hts", "HTS book is not wired in paper mode");
    }
}
