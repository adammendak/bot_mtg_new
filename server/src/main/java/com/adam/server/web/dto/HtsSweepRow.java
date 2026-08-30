package com.adam.server.web.dto;

/**
 * One cell of a backtest parameter sweep (E-9): the swept values plus the
 * portfolio-level result over all symbols (trades in entry order). {@code maxDdR}
 * is the deepest peak-to-trough of the cumulative-R curve.
 */
public record HtsSweepRow(
        double rr,
        double stopBuf,
        double runnerLock,
        boolean adxPermit,
        int n,
        double winRate,
        double avgR,
        double sumR,
        double maxDdR
) {
}
