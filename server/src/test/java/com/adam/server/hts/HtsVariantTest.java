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
    void ha12AndHa4xAreBothParkedOnTheSameBookNowH1StV3Runs() {
        assertThat(HtsVariant.HA12.parked()).isTrue();
        assertThat(HtsVariant.HA4X.parked()).isTrue();
        assertThat(HtsVariant.HA4X.book()).isEqualTo(HtsVariant.HA12.book()); // "Account H1"
        assertThat(HtsVariant.H1_ST_V3.book()).isEqualTo(HtsVariant.HA12.book());
        assertThat(HtsVariant.H1_ST_V3.parked()).isFalse();
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
    void fastAndHa1AreBothParkedOnTheSameBookNowM5StV3Runs() {
        assertThat(HtsVariant.FAST.parked()).isTrue();
        assertThat(HtsVariant.HA1.parked()).isTrue();
        assertThat(HtsVariant.HA1.book()).isEqualTo(HtsVariant.FAST.book()); // "Account m5"
        assertThat(HtsVariant.HA1.ltf()).isEqualTo(com.adam.server.broker.Resolution.M5);
        assertThat(HtsVariant.HA1.huntHours()).isEqualTo(1);
        assertThat(HtsVariant.HA1.atrHours()).isEqualTo(0);
        assertThat(HtsVariant.HA1.atrMinutes()).isEqualTo(15); // M15 WITH/stop, resampled from M5
        assertThat(HtsVariant.M5_ST_V3.book()).isEqualTo(HtsVariant.HA1.book());
        assertThat(HtsVariant.M5_ST_V3.parked()).isFalse();
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
    void mmsIsParkedNoBacktestEdge() {
        assertThat(HtsVariant.MMS.strategy()).isEqualTo(HtsVariant.Strategy.MMS);
        assertThat(HtsVariant.MMS.parked()).isTrue();
        assertThat(HtsVariant.MMS.live()).isFalse();
        assertThat(HtsVariant.MMS.longOnly()).isFalse();
        assertThat(HtsVariant.MMS.book()).isEqualTo(com.adam.server.broker.Books.MMS);
        // other HTS parked/active flags unchanged
        assertThat(HtsVariant.CORE.parked()).isTrue();
        assertThat(HtsVariant.FAST.parked()).isTrue();
        assertThat(HtsVariant.SWING.parked()).isTrue();
        assertThat(HtsVariant.HA4.parked()).isTrue();  // M15_ST_V3B took its book
        assertThat(HtsVariant.HA12.parked()).isTrue(); // H1_ST_V3 took its book
        assertThat(HtsVariant.HA1.parked()).isTrue();  // M5_ST_V3 took its book
        // CORE_LIVE (the only real-money variant) is detached — parked now
        assertThat(HtsVariant.CORE_LIVE.parked()).isTrue();
        assertThat(HtsVariant.HA4.book()).isEqualTo(com.adam.server.broker.Books.DEMO);
        assertThat(HtsVariant.MMS.ltf()).isEqualTo(com.adam.server.broker.Resolution.M15);
        assertThat(HtsVariant.MMS.ltfMinutes()).isEqualTo(15);
        assertThat(HtsVariant.MMS.universe()).containsExactly("BTC", "XAU", "US100");
        assertThat(HtsVariant.MMS.tradesSymbol("BTC")).isTrue();
        assertThat(HtsVariant.MMS.tradesSymbol("XAU")).isTrue();
        assertThat(HtsVariant.MMS.tradesSymbol("US100")).isTrue();
        assertThat(HtsVariant.MMS.tradesSymbol("GER40")).isFalse();
        assertThat(HtsVariant.MMS.htfLabel()).isEqualTo("M15");
        assertThat(HtsVariant.MMS.label()).contains("TMA-ATR");
        // mail is H1-entry only now (see mailsSignalsIsRestrictedToH1EntryVariants) — MMS is M15
        assertThat(HtsVariant.MMS.mailsSignals()).isFalse();
        assertThat(HtsVariant.MMS.dueAtMinute(0)).isTrue();
        assertThat(HtsVariant.MMS.dueAtMinute(16)).isTrue();
        assertThat(HtsVariant.MMS.dueAtMinute(7)).isFalse();
    }

    @Test
    void m15StV3IsUnparkedOnTheMmsBookWithTheFullTenTickerUniverse() {
        assertThat(HtsVariant.M15_ST_V3.strategy()).isEqualTo(HtsVariant.Strategy.ST_V3);
        assertThat(HtsVariant.M15_ST_V3.parked()).isFalse();
        assertThat(HtsVariant.M15_ST_V3.live()).isFalse();
        assertThat(HtsVariant.M15_ST_V3.longOnly()).isFalse();
        assertThat(HtsVariant.M15_ST_V3.book()).isEqualTo(com.adam.server.broker.Books.MMS);
        assertThat(HtsVariant.M15_ST_V3.ltf()).isEqualTo(com.adam.server.broker.Resolution.M15);
        assertThat(HtsVariant.M15_ST_V3.ltfMinutes()).isEqualTo(15);
        assertThat(HtsVariant.M15_ST_V3.atrMinutes()).isEqualTo(60); // HTF span for StV3Engine's resample: H1
        assertThat(HtsVariant.M15_ST_V3.universe()).containsExactlyInAnyOrder(
                "XAU", "BTC", "US100", "GER40", "EURUSD", "US500", "US30", "XAG", "J225", "USDJPY");
        for (String code : HtsVariant.M15_ST_V3.universe()) {
            assertThat(HtsVariant.M15_ST_V3.tradesSymbol(code)).as(code).isTrue();
        }
        assertThat(HtsVariant.M15_ST_V3.tradesSymbol("ETH")).isFalse();
        assertThat(HtsVariant.M15_ST_V3.htfLabel()).isEqualTo("H1");
        assertThat(HtsVariant.M15_ST_V3.label()).contains("H1-ST");
        // M15 entry — too frequent for mail, same reasoning as HA4
        assertThat(HtsVariant.M15_ST_V3.mailsSignals()).isFalse();
        assertThat(HtsVariant.M15_ST_V3.dueAtMinute(0)).isTrue();
        assertThat(HtsVariant.M15_ST_V3.dueAtMinute(16)).isTrue();
    }

    @Test
    void m15StV3bIsAConcentratedSatelliteOnHa4sOldBook() {
        assertThat(HtsVariant.M15_ST_V3B.strategy()).isEqualTo(HtsVariant.Strategy.ST_V3);
        assertThat(HtsVariant.M15_ST_V3B.parked()).isFalse();
        assertThat(HtsVariant.M15_ST_V3B.book()).isEqualTo(com.adam.server.broker.Books.DEMO);
        assertThat(HtsVariant.M15_ST_V3B.book()).isEqualTo(HtsVariant.HA4.book());
        assertThat(HtsVariant.M15_ST_V3B.ltf()).isEqualTo(com.adam.server.broker.Resolution.M15);
        assertThat(HtsVariant.M15_ST_V3B.atrMinutes()).isEqualTo(60); // same H1 pairing as M15_ST_V3
        assertThat(HtsVariant.M15_ST_V3B.htfLabel()).isEqualTo("H1");
        assertThat(HtsVariant.M15_ST_V3B.universe())
                .containsExactlyInAnyOrder("US100", "XAU", "BTC", "GER40", "EURUSD");
        // deliberately overlaps M15_ST_V3 — same pairing, two accounts, more forward-test data
        assertThat(HtsVariant.M15_ST_V3.universe()).containsAll(HtsVariant.M15_ST_V3B.universe());
        // and shares the exact same 5-ticker universe as the other two satellites, on purpose,
        // for a direct per-ticker RR comparison across all three timeframe pairings
        assertThat(HtsVariant.M15_ST_V3B.universe()).isEqualTo(HtsVariant.M5_ST_V3.universe());
        assertThat(HtsVariant.M15_ST_V3B.universe()).isEqualTo(HtsVariant.H1_ST_V3.universe());
    }

    @Test
    void m5StV3IsM45SupertrendOnHa1sOldBook() {
        assertThat(HtsVariant.M5_ST_V3.strategy()).isEqualTo(HtsVariant.Strategy.ST_V3);
        assertThat(HtsVariant.M5_ST_V3.parked()).isFalse();
        assertThat(HtsVariant.M5_ST_V3.book()).isEqualTo(com.adam.server.broker.Books.HTS);
        assertThat(HtsVariant.M5_ST_V3.ltf()).isEqualTo(com.adam.server.broker.Resolution.M5);
        assertThat(HtsVariant.M5_ST_V3.atrMinutes()).isEqualTo(45); // M45 Supertrend
        assertThat(HtsVariant.M5_ST_V3.htfLabel()).isEqualTo("M45");
        assertThat(HtsVariant.M5_ST_V3.label()).contains("M45-ST");
        assertThat(HtsVariant.M5_ST_V3.universe())
                .containsExactlyInAnyOrder("US100", "XAU", "BTC", "GER40", "EURUSD");
    }

    @Test
    void h1StV3IsH4SupertrendOnHa12sOldBook() {
        assertThat(HtsVariant.H1_ST_V3.strategy()).isEqualTo(HtsVariant.Strategy.ST_V3);
        assertThat(HtsVariant.H1_ST_V3.parked()).isFalse();
        assertThat(HtsVariant.H1_ST_V3.book()).isEqualTo(com.adam.server.broker.Books.SWING);
        assertThat(HtsVariant.H1_ST_V3.ltf()).isEqualTo(com.adam.server.broker.Resolution.H1);
        assertThat(HtsVariant.H1_ST_V3.atrMinutes()).isEqualTo(240); // H4 Supertrend
        assertThat(HtsVariant.H1_ST_V3.htfLabel()).isEqualTo("H4");
        assertThat(HtsVariant.H1_ST_V3.label()).contains("H4-ST");
        assertThat(HtsVariant.H1_ST_V3.universe())
                .containsExactlyInAnyOrder("US100", "XAU", "BTC", "GER40", "EURUSD");
        // H1 entry, same as HA12/HA4X's own ltf — but ST_V3 strategy never mails
        // (mailsSignals() checks strategy == HA_HUNT || MMS, not just ltf == H1)
        assertThat(HtsVariant.H1_ST_V3.mailsSignals()).isFalse();
    }

    @Test
    void mailsSignalsIsRestrictedToH1EntryVariants() {
        // HA12 is the only H1-entry HA-hunt variant — the rest fire too often to mail
        assertThat(HtsVariant.HA12.ltf()).isEqualTo(com.adam.server.broker.Resolution.H1);
        assertThat(HtsVariant.HA12.mailsSignals()).isTrue();
        assertThat(HtsVariant.HA4.mailsSignals()).isFalse();   // M15
        assertThat(HtsVariant.HA1.mailsSignals()).isFalse();   // M5
        assertThat(HtsVariant.HA_OKX.mailsSignals()).isFalse(); // M15
        assertThat(HtsVariant.MMS.mailsSignals()).isFalse();   // M15
        assertThat(HtsVariant.CORE_LIVE.mailsSignals()).isFalse(); // ribbon, never mailed
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
