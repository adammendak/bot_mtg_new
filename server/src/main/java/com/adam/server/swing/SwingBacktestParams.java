package com.adam.server.swing;

/**
 * Knobs for the SDD-SWING per-trade backtest ({@link SwingBacktestService#runTrades}).
 *
 * @param days       length of the evaluation window
 * @param offsetDays end the window this many days before "now" — for a
 *                   non-overlapping train / test split (train: days=60 offset=30,
 *                   test: days=30 offset=0)
 * @param stopMult   stop distance in H4 ATR (2.5 = current live model)
 * @param targetAtr  target distance in H4 ATR (1.0 = current live model)
 * @param lookAhead  H1 bars to resolve a trade before marking to market
 * @param maxNames   &gt;0 = live bot slot gate (max concurrent names + no pyramid);
 *                   0 = take every qualifying signal
 * @param htfFilter  swap the SDD full-stack entry for the HTF mean-reversion
 *                   model: H1 HA flip + price reclaiming RMA33 (pullback, not
 *                   trend-follow stack) + H4 WaveTrend leaving an extreme + a
 *                   fresh H1 Supertrend flip
 * @param runner     two-ticket exit: half the position takes the fixed 1R
 *                   target, the other half (runner) is held until an H1 candle
 *                   <b>body</b> closes on the wrong side of H1 RMA133 (or the
 *                   stop is hit). Off = single ticket, fixed target only.
 */
public record SwingBacktestParams(
        int days,
        int offsetDays,
        double stopMult,
        double targetAtr,
        int lookAhead,
        int maxNames,
        boolean htfFilter,
        boolean runner
) {
    public static SwingBacktestParams of(int days, double stopMult, double targetAtr, int lookAhead, int maxNames) {
        return new SwingBacktestParams(days, 0, stopMult, targetAtr, lookAhead, maxNames, false, false);
    }

    public SwingBacktestParams withHtfFilter(boolean on) {
        return new SwingBacktestParams(days, offsetDays, stopMult, targetAtr, lookAhead, maxNames, on, runner);
    }

    public SwingBacktestParams withRunner(boolean on) {
        return new SwingBacktestParams(days, offsetDays, stopMult, targetAtr, lookAhead, maxNames, htfFilter, on);
    }

    public SwingBacktestParams withWindow(int days, int offsetDays) {
        return new SwingBacktestParams(days, offsetDays, stopMult, targetAtr, lookAhead, maxNames, htfFilter, runner);
    }
}
