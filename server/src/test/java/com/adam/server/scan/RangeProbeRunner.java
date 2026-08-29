package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;
import com.adam.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Probes which from/to ranges Capital.com accepts per resolution.
 */
@Configuration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.adam.server")
public class RangeProbeRunner {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RangeProbeRunner.class);
        app.setAdditionalProfiles("local", "probe");
        app.run(args);
    }

    @Component
    @Profile("probe")
    public static class Run implements ApplicationRunner {
        private static final Logger log = LoggerFactory.getLogger(Run.class);
        private final BrokerBooks books;
        private final AppProperties properties;

        public Run(BrokerBooks books, AppProperties properties) {
            this.books = books;
            this.properties = properties;
        }

        @Override
        public void run(ApplicationArguments args) {
            BrokerClient market = books.marketData();
            Instant to = Instant.now().minusSeconds(15 * 60L);
            for (Resolution res : List.of(Resolution.M15, Resolution.H1, Resolution.H4)) {
                for (int days : new int[]{1, 3, 5, 7, 10, 14, 21, 30, 60}) {
                    try {
                        List<Candle> c = market.candles("DE40", res,
                                to.minusSeconds(days * 86400L), to, 1000);
                        log.warn("PROBE {} days={} => {} candles", res, days, c.size());
                    } catch (Exception e) {
                        log.warn("PROBE {} days={} => FAIL {}", res, days, publicMsg(e));
                    }
                }
            }
            System.exit(0);
        }

        private static String publicMsg(Throwable e) {
            String m = e.getMessage();
            if (m == null && e.getCause() != null) {
                m = e.getCause().getMessage();
            }
            if (m == null) {
                m = e.getClass().getSimpleName();
            }
            return m;
        }
    }
}
