package com.adam.server.hts;

/**
 * Sink for HTS ("wstęgi") entry signals. Implementations are best-effort and
 * must never throw to the scan. Every registered notifier is called for every
 * signal.
 */
public interface HtsNotifier {

    /**
     * @param signal  the entry signal
     * @param context the market snapshot around it (both timeframes); may be
     *                {@code null} if it could not be assembled
     */
    void onHtsSignal(HtsScan signal, HtsSignalContext context);

    /** Convenience for callers / tests without a context. */
    default void onHtsSignal(HtsScan signal) {
        onHtsSignal(signal, null);
    }
}
