package com.adam.server.hts;

/**
 * Sink for HTS ("wstęgi") entry signals. Implementations are best-effort and
 * must never throw to the scan. Every registered notifier is called for every
 * signal. A rich e-mail notifier is a follow-up (T10 in {@code HTS-ROADMAP.md}).
 */
public interface HtsNotifier {

    void onHtsSignal(HtsScan signal);
}
