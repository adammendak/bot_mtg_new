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
    void htfLabelIsNeverNull() {
        assertThat(HtsVariant.CORE.htfLabel()).isEqualTo("H4");
        assertThat(HtsVariant.SWING.htfLabel()).isEqualTo("D1");
        assertThat(HtsVariant.HA4.htfLabel()).isEqualTo("H4");   // htf() is null
        assertThat(HtsVariant.HA12.htfLabel()).isEqualTo("H12"); // htf() is null
        for (HtsVariant v : HtsVariant.values()) {
            assertThat(v.htfLabel()).as(v.name()).isNotBlank();
        }
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
