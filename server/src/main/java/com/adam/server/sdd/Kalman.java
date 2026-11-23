package com.adam.server.sdd;

/**
 * The scalar Kalman filter from BigBeluga's "Kalman Trend Levels", ported
 * line-for-line from the Pine {@code kalman_filter} helper: measurement
 * noise {@code R·length}, process noise {@code Q/length}, seeded from the
 * previous close on the second bar (the Pine {@code nz(est[1], src[1])}).
 *
 * <p>Short length = faster filter. The trend is "up" while the short filter
 * sits above the long one; the strategy ({@code KAL_H4}) is stop-and-reverse
 * on that crossover.
 */
public final class Kalman {

    /** Pine defaults: {@code R = 0.01}, {@code Q = 0.1}. */
    public static final double R = 0.01;
    public static final double Q = 0.1;

    private Kalman() {
    }

    /**
     * @return one filtered value per input close; index 0 is {@code NaN}
     *         (no previous close to seed from), like the Pine original
     */
    public static double[] filter(double[] close, int length) {
        double[] out = new double[close.length];
        double est = Double.NaN;
        double err = 1.0;
        double meas = R * length;
        double process = Q / length;
        for (int i = 0; i < close.length; i++) {
            if (Double.isNaN(est)) {
                est = i > 0 ? close[i - 1] : Double.NaN;
            }
            double pred = est;
            double gain = err / (err + meas);
            est = pred + gain * (close[i] - pred);
            err = (1 - gain) * err + process;
            out[i] = est;
        }
        return out;
    }
}
