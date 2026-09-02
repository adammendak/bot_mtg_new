package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Resample an H1 candle series into fixed N-hour UTC buckets (H4, H12).
 *
 * <p>Bucket key = {@code floor(epochSeconds / (hours*3600)) * (hours*3600)}.
 * A bucket is only returned once its window has fully elapsed by {@code now}
 * ({@code bucketStart + hours*3600 <= now}), so callers always see closed bars.
 * Open = first H1 open in the bucket, high/low = extremes, close = last H1 close,
 * time = bucket start. Input must be time-ascending (as {@code HtsCandles.fetch}
 * returns it).
 */
public final class Resample {

    private Resample() {
    }

    public static List<Candle> toHours(List<Candle> h1, int hours, Instant now) {
        long span = hours * 3600L;
        TreeMap<Long, double[]> buckets = new TreeMap<>(); // key -> [open, high, low, close]
        for (Candle c : h1) {
            long key = Math.floorDiv(c.time().getEpochSecond(), span) * span;
            double[] b = buckets.get(key);
            if (b == null) {
                buckets.put(key, new double[]{c.open(), c.high(), c.low(), c.close()});
            } else {
                b[1] = Math.max(b[1], c.high());
                b[2] = Math.min(b[2], c.low());
                b[3] = c.close();
            }
        }
        long nowSec = now.getEpochSecond();
        List<Candle> out = new ArrayList<>(buckets.size());
        for (var e : buckets.entrySet()) {
            long start = e.getKey();
            if (start + span > nowSec) {
                continue; // window not fully closed yet
            }
            double[] b = e.getValue();
            out.add(new Candle(Instant.ofEpochSecond(start), b[0], b[1], b[2], b[3], 0));
        }
        return out;
    }
}
