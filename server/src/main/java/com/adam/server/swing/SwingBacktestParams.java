package com.adam.server.swing;

/**
 * Knobs for the SDD-SWING per-trade backtest ({@link SwingBacktestService#runTrades}).
 *
 * @param days       length of the evaluation window
 * @param offsetDays end the window this many days before "now" — for a
 *                   non-overlapping train / test split
 * @param stopMult   stop distance in H4 ATR (2.5 = current live model)
 * @param targetAtr  target distance in H4 ATR (1.0 = current live model)
 * @param lookAhead  H1 bars to resolve a trade before marking to market
 * @param maxNames   &gt;0 = live bot slot gate (max concurrent names + no pyramid)
 * @param htfFilter  swap the entry for the HTF mean-reversion model (RMA33 reclaim
 *                   + H4 WaveTrend extreme + H1 Supertrend flip)
 * @param runner     two-ticket exit: half takes the fixed target, half is the runner
 * @param bandEntry  swap the entry for the HTS band model: H1 close body above the
 *                   fast RMA band ({@code fastLen} open/close), fast band fully
 *                   above the slow band ({@code slowLen}), H4 band in trend. With
 *                   {@code bandEntry} the runner exit is "close below the slow
 *                   band" instead of "below the RMA133 line".
 * @param fastLen    fast band RMA length (33)
 * @param slowLen    slow band RMA length (144 per the TradingView HTS setting)
 */
public record SwingBacktestParams(
        int days,
        int offsetDays,
        double stopMult,
        double targetAtr,
        int lookAhead,
        int maxNames,
        boolean htfFilter,
        boolean runner,
        boolean bandEntry,
        int fastLen,
        int slowLen
) {
    public static SwingBacktestParams of(int days, double stopMult, double targetAtr, int lookAhead, int maxNames) {
        return new SwingBacktestParams(days, 0, stopMult, targetAtr, lookAhead, maxNames,
                false, false, false, 33, 144);
    }

    public SwingBacktestParams with(boolean htfFilter, boolean runner, boolean bandEntry) {
        return new SwingBacktestParams(days, offsetDays, stopMult, targetAtr, lookAhead, maxNames,
                htfFilter, runner, bandEntry, fastLen, slowLen);
    }

    public SwingBacktestParams window(int days, int offsetDays) {
        return new SwingBacktestParams(days, offsetDays, stopMult, targetAtr, lookAhead, maxNames,
                htfFilter, runner, bandEntry, fastLen, slowLen);
    }

    public SwingBacktestParams bands(int fastLen, int slowLen) {
        return new SwingBacktestParams(days, offsetDays, stopMult, targetAtr, lookAhead, maxNames,
                htfFilter, runner, bandEntry, fastLen, slowLen);
    }

    public int fastLen() {
        return fastLen <= 0 ? 33 : fastLen;
    }

    public int slowLen() {
        return slowLen <= 0 ? 144 : slowLen;
    }
}
