package com.adam.server.web.dto;

import java.util.List;

/**
 * Result of replaying historical candles through the SDD engine: how often a
 * fullStack/flip signal led to a winning trade, the average R-multiple, and the
 * profit factor — per symbol, over the replayed window.
 */
public record BacktestResult(
        String symbol,
        String epic,
        int signals,
        int wins,
        int losses,
        double winRate,
        double avgR,
        double expectancy,
        double profitFactor
) {
}
