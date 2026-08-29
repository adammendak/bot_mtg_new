package com.adam.server.scan;

import com.adam.server.broker.BrokerBooks;
import com.adam.server.broker.BrokerClient;
import com.adam.server.web.dto.BacktestResult;
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

import java.util.List;

/**
 * One-off backtest runner (test scope, never in prod): boots the app with the
 * real Capital.com demo market data and prints the SDD backtest for the last N
 * days across the whole universe.
 */
@Configuration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.adam.server")
public class BacktestRunner {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BacktestRunner.class);
        app.setAdditionalProfiles("local", "dev");
        app.run(args);
    }

    @Component
    @Profile("dev")
    public static class Run implements ApplicationRunner {
        private static final Logger log = LoggerFactory.getLogger(Run.class);
        private final BacktestService backtest;
        private final BrokerBooks books;

        public Run(BacktestService backtest, BrokerBooks books) {
            this.backtest = backtest;
            this.books = books;
        }

        @Override
        public void run(ApplicationArguments args) {
            int days = 30;
            java.util.List<String> dayOpts = args.getOptionValues("days");
            if (dayOpts != null && !dayOpts.isEmpty()) {
                days = Integer.parseInt(dayOpts.get(0));
            }
            BrokerClient market = books.marketData();
            log.warn("BACKTEST-RESULTS market={} id={} configured={} days={}",
                    market.displayName(), market.id(), market.configured(), days);

            log.warn("BACKTEST-RESULTS START");
            List<BacktestResult> results = backtest.run("demo", days);
            for (BacktestResult r : results) {
                log.warn("BACKTEST-RESULTS {} {} signals={} wins={} losses={} winRate={} avgR={} pf={}",
                        r.symbol(), r.epic(), r.signals(), r.wins(), r.losses(),
                        String.format("%.3f", r.winRate()),
                        String.format("%.3f", r.avgR()),
                        String.format("%.2f", r.profitFactor()));
            }
            log.warn("BACKTEST-RESULTS END");
            System.exit(0);
        }
    }
}
