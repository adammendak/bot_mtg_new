package com.adam.server.hts;

import com.adam.server.broker.Direction;
import com.adam.server.scan.Mailer;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MailHtsNotifierTest {

    private HtsScan signal(HtsVariant v, String symbol) {
        return new HtsScan(v, Instant.parse("2026-08-31T07:00:00Z"), symbol, symbol + "USD",
                Direction.BUY, 100.0, 98.0, 104.0, true);
    }

    @Test
    void mailsOncePerSetupThenSuppressesWithinCooldown() {
        Mailer mailer = mock(Mailer.class);
        MailHtsNotifier n = new MailHtsNotifier(mailer, 120);

        n.onHtsSignal(signal(HtsVariant.FAST, "BTC"), null);
        n.onHtsSignal(signal(HtsVariant.FAST, "BTC"), null);
        n.onHtsSignal(signal(HtsVariant.FAST, "BTC"), null);

        verify(mailer, times(1)).send(anyString(), anyString());
    }

    @Test
    void differentSetupsEachGetAMail() {
        Mailer mailer = mock(Mailer.class);
        MailHtsNotifier n = new MailHtsNotifier(mailer, 120);

        n.onHtsSignal(signal(HtsVariant.FAST, "BTC"), null);
        n.onHtsSignal(signal(HtsVariant.CORE, "BTC"), null);   // different variant
        n.onHtsSignal(signal(HtsVariant.FAST, "GER40"), null); // different symbol

        verify(mailer, times(3)).send(anyString(), anyString());
    }

    @Test
    void zeroCooldownMailsEveryTime() {
        Mailer mailer = mock(Mailer.class);
        MailHtsNotifier n = new MailHtsNotifier(mailer, 0);

        n.onHtsSignal(signal(HtsVariant.FAST, "BTC"), null);
        n.onHtsSignal(signal(HtsVariant.FAST, "BTC"), null);

        verify(mailer, times(2)).send(anyString(), anyString());
    }

    @Test
    void subjectCarriesVariantAndTimeframe() {
        Mailer mailer = mock(Mailer.class);
        MailHtsNotifier n = new MailHtsNotifier(mailer, 120);

        n.onHtsSignal(signal(HtsVariant.CORE, "GER40"), null);

        verify(mailer).send(contains("[CORE H4/M15]"), anyString());
    }

    @Test
    void haHuntSignalsMailEveryTimeIgnoringTheCooldown() {
        Mailer mailer = mock(Mailer.class);
        MailHtsNotifier n = new MailHtsNotifier(mailer, 120);

        n.onHtsSignal(signal(HtsVariant.HA4, "XAU"), null);
        n.onHtsSignal(signal(HtsVariant.HA4, "XAU"), null);
        n.onHtsSignal(signal(HtsVariant.HA12, "US100"), null);

        verify(mailer, times(3)).send(anyString(), anyString());
    }

    @Test
    void haHuntSubjectUsesTheHuntLabelAndDoesNotNpeOnNullHtf() {
        Mailer mailer = mock(Mailer.class);
        MailHtsNotifier n = new MailHtsNotifier(mailer, 120);

        n.onHtsSignal(signal(HtsVariant.HA4, "USDJPY"), null);

        verify(mailer).send(contains("[HA4 H4-hunt/M15]"), contains("HA-hunt cloud entry"));
    }
}
