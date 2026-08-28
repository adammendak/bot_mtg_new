package com.adam.server.broker.model;

import java.time.Instant;

/**
 * A single broker transaction (closed trade, deposit, withdrawal, swap) used to
 * reconstruct historical equity. {@code amount} is the cash impact in the account
 * currency (positive = credit, negative = debit).
 */
public record BrokerTransaction(
        Instant time,
        String type,        // TRADE | DEPOSIT | WITHDRAWAL | SWAP | ...
        String instrument,  // epic or instrument name, may be null
        double amount,      // cash impact (P/L for trades, +/- for deposits)
        String reference,   // broker reference/deal id, may be null
        String note
) {
}
