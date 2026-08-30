package com.adam.server.hts;

import com.adam.server.broker.BrokerClient;
import com.adam.server.broker.Resolution;
import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Candle fetch for HTS, chunked by resolution. Capital.com rejects a single
 * history request whose date range is too wide for the resolution
 * ({@code error.invalid.max.daterange}), so {@code [from, to]} is walked in
 * per-resolution windows and the results de-duplicated. Shared by the backtest
 * and the live scan.
 */
public final class HtsCandles {

    private HtsCandles() {
    }

    /** Safe single-request window per resolution, in days. */
    public static int chunkDays(Resolution res) {
        return switch (res) {
            case M5 -> 3;
            case M15 -> 10;
            case H1 -> 30;
            case H4 -> 60;
            case D1 -> 365;
        };
    }

    public static List<Candle> fetch(BrokerClient market, String epic, Resolution res, Instant from, Instant to) {
        List<Candle> all = new ArrayList<>();
        long chunk = (long) chunkDays(res) * 86400L;
        Instant chunkTo = to;
        int guard = 0;
        while (chunkTo.isAfter(from) && guard++ < 500) {
            Instant chunkFrom = chunkTo.minusSeconds(chunk);
            if (chunkFrom.isBefore(from)) {
                chunkFrom = from;
            }
            all.addAll(market.candles(epic, res, chunkFrom, chunkTo, 1000));
            chunkTo = chunkFrom;
        }
        all.sort(Comparator.comparing(Candle::time));
        List<Candle> out = new ArrayList<>();
        Instant prev = null;
        for (Candle cc : all) {
            if (prev == null || !cc.time().equals(prev)) {
                out.add(cc);
                prev = cc.time();
            }
        }
        return out;
    }
}
