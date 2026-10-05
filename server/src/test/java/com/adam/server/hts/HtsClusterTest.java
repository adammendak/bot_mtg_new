package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtsClusterTest {

    @Test
    void equityIndicesShareAClusterPerDirection() {
        assertThat(HtsCluster.key("US100", Direction.BUY)).isEqualTo(HtsCluster.key("GER40", Direction.BUY));
        assertThat(HtsCluster.key("US100", Direction.BUY)).isNotEqualTo(HtsCluster.key("US100", Direction.SELL));
    }

    @Test
    void usdSideIsDerivedFromQuoteVersusBase() {
        // BUY EURUSD and SELL USDCAD are both short-USD; BUY USDCAD is long-USD.
        assertThat(HtsCluster.key("EURUSD", Direction.BUY)).isEqualTo("USD|SHORT");
        assertThat(HtsCluster.key("NZDUSD", "BUY")).isEqualTo("USD|SHORT");
        assertThat(HtsCluster.key("USDCAD", Direction.SELL)).isEqualTo("USD|SHORT");
        assertThat(HtsCluster.key("USDCHF", Direction.BUY)).isEqualTo("USD|LONG");
        assertThat(HtsCluster.key("EURUSD", Direction.SELL)).isEqualTo("USD|LONG");
    }

    @Test
    void metalsCryptoAndUnknownInstruments() {
        assertThat(HtsCluster.key("XAU", Direction.BUY)).isEqualTo(HtsCluster.key("XAG", Direction.BUY));
        assertThat(HtsCluster.key("BTC", Direction.SELL)).isEqualTo("CRYPTO|SELL");
        assertThat(HtsCluster.key("EURJPY", Direction.BUY)).isNull(); // no USD leg, not clustered
        assertThat(HtsCluster.key(null, Direction.BUY)).isNull();
    }

    @Test
    void sessionWindowParsesAndRejectsMalformed() {
        AppProperties p = new AppProperties();
        p.setHtsSessionFilters("M15_ST_V3:7-14; H1_ST_V3:22-4");
        assertThat(p.htsSessionWindow("M15_ST_V3")).containsExactly(7, 14);
        assertThat(p.htsSessionWindow("h1_st_v3")).containsExactly(22, 4);
        assertThat(p.htsSessionWindow("M5_ST_V3")).isNull();
        p.setHtsSessionFilters("M15_ST_V3:9-9;M5_ST_V3:x-3;H1_ST_V3:5");
        assertThat(p.htsSessionWindow("M15_ST_V3")).isNull();
        assertThat(p.htsSessionWindow("M5_ST_V3")).isNull();
        assertThat(p.htsSessionWindow("H1_ST_V3")).isNull();
    }

    @Test
    void observeOnlySetIsCaseInsensitiveAndTolerantOfBlanks() {
        AppProperties p = new AppProperties();
        p.setHtsObserveOnlySymbols(" gER40, XAU ,,btc ");
        assertThat(p.htsObserveOnlySet()).containsExactlyInAnyOrder("GER40", "XAU", "BTC");
        p.setHtsObserveOnlySymbols(null);
        assertThat(p.htsObserveOnlySet()).isEmpty();
    }
}
