package com.adam.server.web.dto;

/**
 * Walk-forward split of a backtest (E-10): the same replayed trades split by
 * entry time at {@code splitPct} of the window — the earlier part is
 * "in-sample" (where parameters would have been fitted), the later part
 * "out-of-sample". A model that only works in-sample is overfit.
 */
public record HtsOosResult(
        double splitPct,
        String splitAt,
        Half inSample,
        Half outOfSample
) {
    public record Half(int n, double winRate, double avgR, double sumR, double maxDdR) {
    }
}
