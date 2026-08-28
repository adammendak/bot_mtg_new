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

    public BrokerBooks(
            @Qualifier("demoBroker") BrokerClient demo,
            @Qualifier("liveBroker") BrokerClient live,
            @Qualifier("glowneBroker") BrokerClient glowne
    ) {
        this.demo = demo;
        this.live = live;
        this.glowne = glowne;
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

    public BrokerClient forBook(String id) {
        if (id != null && id.equalsIgnoreCase("live")) {
            return live;
        }
        if (id != null && (id.equalsIgnoreCase("glowne") || id.equalsIgnoreCase("main"))) {
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
