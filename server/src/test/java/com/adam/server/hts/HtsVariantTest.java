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

    @Test
    void ha12IsParkedInFavourOfHa4xOnTheSameBook() {
        assertThat(HtsVariant.HA12.parked()).isTrue();
        assertThat(HtsVariant.HA4X.parked()).isFalse();
        assertThat(HtsVariant.HA4X.book()).isEqualTo(HtsVariant.HA12.book()); // "Account H1"
    }

    @Test
    void ha1ReplacesTheParkedFastOnTheSameBook() {
        assertThat(HtsVariant.FAST.parked()).isTrue();
        assertThat(HtsVariant.HA1.parked()).isFalse();
        assertThat(HtsVariant.HA1.book()).isEqualTo(HtsVariant.FAST.book()); // "Account m5"
        assertThat(HtsVariant.HA1.ltf()).isEqualTo(com.adam.server.broker.Resolution.M5);
        assertThat(HtsVariant.HA1.huntHours()).isEqualTo(1);
        assertThat(HtsVariant.HA1.atrHours()).isEqualTo(0);
        assertThat(HtsVariant.HA1.atrMinutes()).isEqualTo(15); // M15 WITH/stop, resampled from M5
        assertThat(HtsVariant.FAST_OKX.parked()).isFalse(); // crypto FAST unaffected
    }

    @Test
    void ha4xMirrorsHa4ExceptTheEntryTrigger() {
        assertThat(HtsVariant.HA4.entryTrigger()).isEqualTo(HtsVariant.EntryTrigger.HA_FLIP);
        assertThat(HtsVariant.HA4X.entryTrigger()).isEqualTo(HtsVariant.EntryTrigger.BAND_CROSS);
        assertThat(HtsVariant.HA4X.huntHours()).isEqualTo(HtsVariant.HA4.huntHours());
        assertThat(HtsVariant.HA4X.atrHours()).isEqualTo(HtsVariant.HA4.atrHours());
        assertThat(HtsVariant.HA4X.slowLen()).isEqualTo(HtsVariant.HA4.slowLen());
        assertThat(HtsVariant.HA4X.ltf()).isEqualTo(HtsVariant.HA4.ltf());
        assertThat(HtsVariant.HA4X.universe()).isEqualTo(HtsVariant.HA4.universe());
        assertThat(HtsVariant.HA4X.longOnly()).isEqualTo(HtsVariant.HA4.longOnly());
        assertThat(HtsVariant.HA4X.label()).contains("band-cross");
        assertThat(HtsVariant.HA4.label()).doesNotContain("band-cross");
    }
}
