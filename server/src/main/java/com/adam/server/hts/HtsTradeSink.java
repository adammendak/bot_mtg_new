package com.adam.server.hts;

import com.adam.server.persistence.HtsTradeEntity;

/**
 * Extension point for HTS trade events. Every registered sink is called
 * best-effort on open and on close; a sink must never throw back into the
 * execution gate or the monitor. Built-ins: {@link LogHtsTradeSink}. Later:
 * a Notion sink (E-2), a mail sink, a webhook sink — each just implements this.
 */
public interface HtsTradeSink {

    /** A trade was just opened (row persisted with status OPEN). */
    void onOpen(HtsTradeEntity trade);

    /**
     * A trade was just closed (status CLOSED, outcome fields filled where the
     * broker gave us enough to compute them). If the sink writes back a handle
     * (e.g. a Notion page id) it may set it on {@code trade}; the caller saves.
     */
    void onClose(HtsTradeEntity trade);
}
