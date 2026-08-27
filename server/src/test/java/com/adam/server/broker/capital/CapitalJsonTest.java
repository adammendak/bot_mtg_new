package com.adam.server.broker.capital;

import com.adam.server.broker.model.Account;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalJsonTest {

    @Test
    void parseOfficialAccountsSampleWithExtraFields() {
        String json = """
                {"accounts": [
                  {"accountId": "12345678901234567","accountName": "USD","status": "ENABLED",
                   "accountType": "CFD","preferred": true,
                   "balance": {"balance": 92.89,"deposit": 90.38,"profitLoss": 2.51,"available": 64.66},
                   "currency": "USD","symbol": "$"},
                  {"accountId": "12345678907654321","accountName": "EUR","status": "ENABLED",
                   "accountType": "CFD","preferred": false,
                   "balance": {"balance": 0,"deposit": 0,"profitLoss": 0,"available": 0},
                   "currency": "EUR","symbol": "€"}
                ]}
                """;

        List<Account> accounts = CapitalJson.parseAccounts(json);

        assertThat(accounts).hasSize(2);
        assertThat(accounts.getFirst().id()).isEqualTo("12345678901234567");
        assertThat(accounts.getFirst().name()).isEqualTo("USD");
        assertThat(accounts.getFirst().currency()).isEqualTo("USD");
        assertThat(accounts.getFirst().preferred()).isTrue();
        assertThat(accounts.getFirst().balance()).isEqualTo(92.89);
        assertThat(accounts.getFirst().available()).isEqualTo(64.66);
        assertThat(accounts.getFirst().profitLoss()).isEqualTo(2.51);
        assertThat(accounts.get(1).preferred()).isFalse();
    }

    @Test
    void parseAccountsMissingPreferredAndBalance() {
        String json = """
                {"accounts": [
                  {"accountId": "abc","accountName": "Demo","currency": "PLN"}
                ]}
                """;

        List<Account> accounts = CapitalJson.parseAccounts(json);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().id()).isEqualTo("abc");
        assertThat(accounts.getFirst().name()).isEqualTo("Demo");
        assertThat(accounts.getFirst().currency()).isEqualTo("PLN");
        assertThat(accounts.getFirst().preferred()).isFalse();
        assertThat(accounts.getFirst().balance()).isZero();
    }

    @Test
    void errorCodeReadsNotFoundEpic() {
        assertThat(CapitalJson.errorCode("{\"errorCode\":\"error.not-found.epic\"}"))
                .isEqualTo("error.not-found.epic");
        assertThat(CapitalJson.isNotFoundEpic("{\"errorCode\":\"error.not-found.epic\"}")).isTrue();
    }
}
