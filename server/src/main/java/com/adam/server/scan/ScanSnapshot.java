package com.adam.server.scan;

import com.adam.server.sdd.SddScan;

import java.time.Instant;
import java.util.List;

public record ScanSnapshot(
        Instant scannedAt,
        String brokerId,
        String brokerName,
        boolean executionEnabled,
        boolean newsBlackout,
        List<SddScan> symbols,
        String error,
        List<BookScan> books
) {
    public record BookScan(String id, String broker, String halt, String error) {
    }

    public static ScanSnapshot empty(Instant at, String brokerId, String brokerName, boolean executionEnabled) {
        return new ScanSnapshot(at, brokerId, brokerName, executionEnabled, false, List.of(), null, List.of());
    }
}
