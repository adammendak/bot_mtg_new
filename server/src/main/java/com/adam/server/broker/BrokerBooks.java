package com.adam.server.broker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Demo and live books side by side. Market data is fetched once (demo preferred).
 * Execution, if ever enabled, uses demo only — never dual-fire.
 */
@Component
public class BrokerBooks {

    private final BrokerClient demo;
    private final BrokerClient live;
    private final BrokerClient glowne;
    private final BrokerClient swing;
    private final BrokerClient hts;
    private final BrokerClient okx;

    public BrokerBooks(
            @Qualifier("demoBroker") BrokerClient demo,
            @Qualifier("liveBroker") BrokerClient live,
            @Qualifier("glowneBroker") BrokerClient glowne,
            @Qualifier("swingBroker") BrokerClient swing,
            @Qualifier("htsBroker") BrokerClient hts,
            @Qualifier("okxBroker") BrokerClient okx
    ) {
        this.demo = demo;
        this.live = live;
        this.glowne = glowne;
        this.swing = swing;
        this.hts = hts;
        this.okx = okx;
    }

    public BrokerClient demo() {
        return demo;
    }

    public BrokerClient live() {
        return live;
    }

    public BrokerClient glowne() {
        return glowne;
    }

    public BrokerClient swing() {
        return swing;
    }

    public BrokerClient hts() {
        return hts;
    }

    public BrokerClient okx() {
        return okx;
    }

    public BrokerClient forBook(String id) {
        if (id != null && id.equalsIgnoreCase(Books.LIVE)) {
            return live;
        }
        if (id != null && id.equalsIgnoreCase(Books.SWING)) {
            return swing;
        }
        if (id != null && id.equalsIgnoreCase(Books.HTS)) {
            return hts;
        }
        if (id != null && id.equalsIgnoreCase(Books.OKX)) {
            return okx;
        }
        if (Books.isGlowne(id)) {
            return glowne;
        }
        return demo;
    }

    /** Shared candles: demo if configured, otherwise live, otherwise the demo stub. */
    public BrokerClient marketData() {
        if (demo.configured()) {
            return demo;
        }
        if (live.configured()) {
            return live;
        }
        return demo;
    }
}
