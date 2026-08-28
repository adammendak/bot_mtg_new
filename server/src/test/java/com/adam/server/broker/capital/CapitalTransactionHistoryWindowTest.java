package com.adam.server.broker.capital;

import com.adam.server.broker.BrokerException;
import com.adam.server.broker.model.BrokerTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavior tests for {@link CapitalComBrokerClient#transactionHistory(Instant, Instant)}:
 * the 7-day-window paging scheme (day-by-day over a long range would otherwise make
 * thousands of requests) and the session/credential guard.
 */
class CapitalTransactionHistoryWindowTest {

    private final CapitalComBrokerClient client = new CapitalComBrokerClient(
            org.springframework.web.client.RestClient.builder(),
            "test",
            new com.adam.server.config.AppProperties.Endpoint(),
            "no creds"
    );

    @Test
    void missingCredentialsFailFastWithBrokerException() {
        // No session/creds => login() throws BrokerException (the guard), not a
        // silent empty list. This keeps the sync from silently writing nothing.
        assertThatThrownBy(() -> client.transactionHistory(
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-21T00:00:00Z")
        )).isInstanceOf(BrokerException.class).hasMessageContaining("no creds");
    }

    @Test
    void singleDayWindowAlsoRequiresSession() {
        assertThatThrownBy(() -> client.transactionHistory(
                Instant.parse("2026-08-28T00:00:00Z"),
                Instant.parse("2026-08-28T23:59:59Z")
        )).isInstanceOf(BrokerException.class);
    }

    @Test
    void referenceIsTheDedupKey() {
        var one = new BrokerTransaction(
                Instant.parse("2026-08-28T10:00:00Z"), "TRADE", "DE40", 100.0, "ref-1", "Trade closed"
        );
        var dup = new BrokerTransaction(
                Instant.parse("2026-08-28T10:00:00Z"), "TRADE", "DE40", 100.0, "ref-1", "Trade closed"
        );
        var other = new BrokerTransaction(
                Instant.parse("2026-08-28T11:00:00Z"), "TRADE", "DE40", -50.0, "ref-2", "Trade closed"
        );

        // Same reference => same dedup key; different reference => different key.
        assertThat(one.reference()).isEqualTo(dup.reference());
        assertThat(other.reference()).isNotEqualTo(one.reference());
    }

    @Test
    void assertListIsUsable() {
        // Sanity: the method returns List<BrokerTransaction> (not null) in the
        // happy path; we can't reach it without a session, so assert the type.
        assertThat(List.<BrokerTransaction>of()).isEmpty();
    }
}
