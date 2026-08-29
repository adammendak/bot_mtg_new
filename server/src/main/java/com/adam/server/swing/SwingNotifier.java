package com.adam.server.swing;

/**
 * Sink for SDD-SWING (H1) entry signals. Implementations are best-effort and
 * must never throw to the scan. All registered notifiers are called for every
 * signal.
 */
public interface SwingNotifier {

    void onSwingSignal(SwingScan signal);
}
