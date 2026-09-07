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
    void coreLiveDoesNotTradeGer40OnRealMoney() {
        assertThat(HtsVariant.CORE_LIVE.tradesSymbol("GER40")).isFalse();
        assertThat(HtsVariant.CORE_LIVE.tradesSymbol("ger40")).isFalse();
        assertThat(HtsVariant.CORE_LIVE.tradesSymbol("XAU")).isTrue();
        assertThat(HtsVariant.CORE_LIVE.tradesSymbol("US100")).isTrue();
        // other variants still trade GER40
        assertThat(HtsVariant.FAST.tradesSymbol("GER40")).isTrue();
        assertThat(HtsVariant.CORE.tradesSymbol("GER40")).isTrue();
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
    void ha12IsActiveAndHa4xIsParkedOnTheSameBook() {
        assertThat(HtsVariant.HA12.parked()).isFalse();
        assertThat(HtsVariant.HA4X.parked()).isTrue();
        assertThat(HtsVariant.HA4X.book()).isEqualTo(HtsVariant.HA12.book()); // "Account H1"
    }

    @Test
    void haOkxIsAHaHuntVariantOnTheOkxBookEthAndXrp() {
        assertThat(HtsVariant.HA_OKX.strategy()).isEqualTo(HtsVariant.Strategy.HA_HUNT);
        assertThat(HtsVariant.HA_OKX.book()).isEqualTo(com.adam.server.broker.Books.OKX);
        assertThat(HtsVariant.HA_OKX.parked()).isFalse();
        assertThat(HtsVariant.HA_OKX.universe()).containsExactly("ETH", "XRP");
        assertThat(HtsVariant.HA_OKX.universe()).doesNotContain("BTC");
        assertThat(HtsVariant.HA_OKX.longOnly()).isTrue();
        assertThat(HtsVariant.HA_OKX.entryTrigger()).isEqualTo(HtsVariant.EntryTrigger.HA_FLIP);
        assertThat(HtsVariant.HA_OKX.huntHours()).isEqualTo(4);
        assertThat(HtsVariant.HA_OKX.slowLen()).isEqualTo(100);
        assertThat(HtsVariant.HA_OKX.ltf()).isEqualTo(com.adam.server.broker.Resolution.M15);
        assertThat(HtsVariant.HA_OKX.htfLabel()).isEqualTo("H4");
    }

    @Test
    void haHuntUniverseAddsSilverAndNikkeiAndDropsGer40() {
        java.util.List<String> expected = java.util.List.of("XAU", "XAG", "J225", "USDJPY", "US100");
        assertThat(HtsVariant.HA4.universe()).isEqualTo(expected);
        assertThat(HtsVariant.HA12.universe()).isEqualTo(expected);
        assertThat(HtsVariant.HA4X.universe()).isEqualTo(expected);
        assertThat(HtsVariant.HA4.universe()).doesNotContain("GER40");
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
    }

    @Test
    void onlyHaOkxTradesTheOkxBookNowRibbonOkxParked() {
        // One OKX account: only the M15 HA-hunt runs there. The OKX ribbon
        // variants are parked (FAST_OKX would churn LTC/BTC on M5 once live is armed).
        assertThat(HtsVariant.CORE_OKX.parked()).isTrue();
        assertThat(HtsVariant.FAST_OKX.parked()).isTrue();
        assertThat(HtsVariant.HA_OKX.parked()).isFalse();
        assertThat(HtsVariant.HA_OKX.book()).isEqualTo(com.adam.server.broker.Books.OKX);
    }

    @Test
    void mmsIsAnUnparkedObserveOnlyMeanReversionVariantOnItsOwnBook() {
        assertThat(HtsVariant.MMS.strategy()).isEqualTo(HtsVariant.Strategy.MMS);
        assertThat(HtsVariant.MMS.parked()).isFalse(); // observe-only forward test
        assertThat(HtsVariant.MMS.live()).isFalse();
        assertThat(HtsVariant.MMS.longOnly()).isFalse();
        assertThat(HtsVariant.MMS.book()).isEqualTo(com.adam.server.broker.Books.MMS);
        assertThat(HtsVariant.MMS.ltf()).isEqualTo(com.adam.server.broker.Resolution.M15);
        assertThat(HtsVariant.MMS.ltfMinutes()).isEqualTo(15);
        assertThat(HtsVariant.MMS.universe()).containsExactly("BTC", "XAU", "US100");
        assertThat(HtsVariant.MMS.tradesSymbol("BTC")).isTrue();
        assertThat(HtsVariant.MMS.tradesSymbol("XAU")).isTrue();
        assertThat(HtsVariant.MMS.tradesSymbol("US100")).isTrue();
        assertThat(HtsVariant.MMS.tradesSymbol("GER40")).isFalse();
        assertThat(HtsVariant.MMS.htfLabel()).isEqualTo("M15");
        assertThat(HtsVariant.MMS.label()).contains("TMA-ATR");
        assertThat(HtsVariant.MMS.mailsSignals()).isTrue();
        assertThat(HtsVariant.MMS.dueAtMinute(0)).isTrue();
        assertThat(HtsVariant.MMS.dueAtMinute(16)).isTrue();
        assertThat(HtsVariant.MMS.dueAtMinute(7)).isFalse();
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
