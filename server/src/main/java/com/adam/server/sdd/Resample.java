package com.adam.server.sdd;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Resample a candle series into fixed-span UTC buckets — H1 into N-hour
 * buckets (H4, H12), or a finer series (M5) into N-minute buckets (M15).
 *
 * <p>Bucket key = {@code floor(epochSeconds / span) * span}. A bucket is only
 * returned once its window has fully elapsed by {@code now}
 * ({@code bucketStart + span <= now}), so callers always see closed bars.
 * Open = first source open in the bucket, high/low = extremes, close = last
 * source close, time = bucket start. Input must be time-ascending (as
 * {@code HtsCandles.fetch} returns it).
 */
public final class Resample {

    private Resample() {
    }

    public static List<Candle> toHours(List<Candle> source, int hours, Instant now) {
        return bucket(source, hours * 3600L, now);
    }

    public static List<Candle> toMinutes(List<Candle> source, int minutes, Instant now) {
        return bucket(source, minutes * 60L, now);
    }

    private static List<Candle> bucket(List<Candle> source, long spanSeconds, Instant now) {
        TreeMap<Long, double[]> buckets = new TreeMap<>(); // key -> [open, high, low, close]
        for (Candle c : source) {
            long key = Math.floorDiv(c.time().getEpochSecond(), spanSeconds) * spanSeconds;
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
            if (start + spanSeconds > nowSec) {
                continue; // window not fully closed yet
            }
            double[] b = e.getValue();
            out.add(new Candle(Instant.ofEpochSecond(start), b[0], b[1], b[2], b[3], 0));
        }
        return out;
    }
}
