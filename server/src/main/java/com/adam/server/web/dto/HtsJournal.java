package com.adam.server.web.dto;

import java.util.List;

/**
 * HTS trade journal (E-8) — closed {@code hts_trades} sliced for the analysis
 * views: a per-day R/P-L series (calendar heatmap), an R-multiple histogram, and
 * per-reason / per-symbol breakdowns. Filtered by variant / symbol / date range.
 */
public record HtsJournal(
        int trades,
        int wins,
        double winRate,
        double avgR,
        double sumR,
        List<Day> byDay,
        List<Bucket> rHistogram,
        List<Group> byReason,
        List<Group> bySymbol
) {
    public record Day(String date, double r, Double pnl, int trades) {
    }

    public record Bucket(String label, int count) {
    }

    public record Group(String key, int trades, int wins, double winRate, double avgR, double sumR) {
    }
}
