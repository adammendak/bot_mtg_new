package com.adam.server.hts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtsVariantTest {

    @Test
    void fastSkipsBtcAndEurusdButTradesEverythingElse() {
        assertThat(HtsVariant.FAST.tradesSymbol("BTC")).isFalse();
        assertThat(HtsVariant.FAST.tradesSymbol("btc")).isFalse();
        assertThat(HtsVariant.FAST.tradesSymbol("EURUSD")).isFalse();
        assertThat(HtsVariant.FAST.tradesSymbol("eurusd")).isFalse();
        assertThat(HtsVariant.FAST.tradesSymbol("GER40")).isTrue();
        assertThat(HtsVariant.FAST.tradesSymbol("XAU")).isTrue();
        assertThat(HtsVariant.FAST.tradesSymbol("US100")).isTrue();
    }

    @Test
    void higherTimeframeModelsStillTradeBtcAndEurusd() {
        assertThat(HtsVariant.CORE.tradesSymbol("BTC")).isTrue();
        assertThat(HtsVariant.SWING.tradesSymbol("BTC")).isTrue();
        assertThat(HtsVariant.CORE_LIVE.tradesSymbol("BTC")).isTrue();
        assertThat(HtsVariant.CORE.tradesSymbol("EURUSD")).isTrue();
        assertThat(HtsVariant.SWING.tradesSymbol("EURUSD")).isTrue();
        assertThat(HtsVariant.CORE_LIVE.tradesSymbol("EURUSD")).isTrue();
    }
}
