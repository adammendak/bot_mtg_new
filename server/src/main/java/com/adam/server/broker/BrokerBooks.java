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
    private final BrokerClient mms;
    // TradingView AlgoPro books — setter-injected so the constructor above stays stable.
    private BrokerClient tvH1 = new UnavailableBrokerClient(Books.TV_H1, "TV H1 book is not wired");
    private BrokerClient tvH4 = new UnavailableBrokerClient(Books.TV_H4, "TV H4 book is not wired");

    public BrokerBooks(
            @Qualifier("demoBroker") BrokerClient demo,
            @Qualifier("liveBroker") BrokerClient live,
            @Qualifier("glowneBroker") BrokerClient glowne,
            @Qualifier("swingBroker") BrokerClient swing,
            @Qualifier("htsBroker") BrokerClient hts,
            @Qualifier("okxBroker") BrokerClient okx,
            @Qualifier("mmsBroker") BrokerClient mms
    ) {
        this.demo = demo;
        this.live = live;
        this.glowne = glowne;
        this.swing = swing;
        this.hts = hts;
        this.okx = okx;
        this.mms = mms;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setTvBrokers(@Qualifier("tvH1Broker") BrokerClient tvH1, @Qualifier("tvH4Broker") BrokerClient tvH4) {
        this.tvH1 = tvH1;
        this.tvH4 = tvH4;
    }

    public BrokerClient tvH1() {
        return tvH1;
    }

    public BrokerClient tvH4() {
        return tvH4;
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

    public BrokerClient mms() {
        return mms;
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
        if (id != null && id.equalsIgnoreCase(Books.MMS)) {
            return mms;
        }
        if (id != null && id.equalsIgnoreCase(Books.TV_H1)) {
            return tvH1;
        }
        if (id != null && id.equalsIgnoreCase(Books.TV_H4)) {
            return tvH4;
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
