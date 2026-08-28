package com.adam.server.web.dto;

/**
 * An open position with the monitoring extras: exact cash risk (1R), how long
 * it has been open, a flag when the stop has drifted / vanished since entry
 * (trail moved it or it is missing), and a "sleeping" flag when the position is
 * open long without management activity. Used by the Dashboard positions table
 * so every row carries its audit + alert state in one response.
 */
public record PositionMonitorView(
        String dealId,
        String epic,
        String direction,
        double size,
        double level,
        Double stopLevel,
        double unrealizedPnl,
        String currency,
        Double riskPln,
        long openMinutes,
        String openedAt,
        boolean stopDrifted,
        boolean sleeping
) {
}
