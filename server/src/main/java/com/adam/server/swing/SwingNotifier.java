package com.adam.server.swing;

/**
 * Sink for SDD-SWING (H1) entry signals. Implementations are best-effort and
 * must never throw to the scan. All registered notifiers are called for every
 * signal.
 */
public interface SwingNotifier {

    /**
     * @param signal  the entry signal
     * @param context the market snapshot around it (both timeframes); may be
     *                {@code null} if it could not be assembled
     */
    void onSwingSignal(SwingScan signal, SwingSignalContext context);

    /** Convenience for callers / tests without a context. */
    default void onSwingSignal(SwingScan signal) {
        onSwingSignal(signal, null);
    }
}
