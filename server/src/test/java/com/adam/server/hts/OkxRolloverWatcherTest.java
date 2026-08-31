package com.adam.server.hts;

import com.adam.server.persistence.HtsTradeEntity;
import com.adam.server.persistence.HtsTradeRepository;
import com.adam.server.scan.Mailer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OkxRolloverWatcherTest {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private HtsTradeEntity okxTrade(String epic) {
        HtsTradeEntity t = mock(HtsTradeEntity.class);
        when(t.getBook()).thenReturn("okx");
        when(t.getEpic()).thenReturn(epic);
        when(t.getVariant()).thenReturn("FAST_OKX");
        when(t.getSymbol()).thenReturn("BTC");
        return t;
    }

    private String contract(int daysOut) {
        return "BTC-USDT-" + LocalDate.now(ZoneOffset.UTC).plusDays(daysOut).format(YYMMDD);
    }

    @Test
    void mailsWhenAnOpenOkxPositionIsNearExpiry() {
        HtsTradeRepository repo = mock(HtsTradeRepository.class);
        Mailer mailer = mock(Mailer.class);
        String near = contract(4);
        HtsTradeEntity trade = okxTrade(near);
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(trade));

        new OkxRolloverWatcher(repo, mailer).check();

        verify(mailer).sendThrottled(eq("okx-roll-" + near), contains("expiring"), anyString());
    }

    @Test
    void quietWhenExpiryIsFarOrBookIsNotOkx() {
        HtsTradeRepository repo = mock(HtsTradeRepository.class);
        Mailer mailer = mock(Mailer.class);
        HtsTradeEntity farOkx = okxTrade(contract(60));
        HtsTradeEntity capital = mock(HtsTradeEntity.class);
        when(capital.getBook()).thenReturn("hts");
        when(repo.findByStatusOrderByIdDesc("OPEN")).thenReturn(List.of(farOkx, capital));

        new OkxRolloverWatcher(repo, mailer).check();

        verify(mailer, never()).sendThrottled(anyString(), anyString(), anyString());
    }
}
