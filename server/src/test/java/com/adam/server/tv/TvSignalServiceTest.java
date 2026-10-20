package com.adam.server.tv;

import com.adam.server.config.AppProperties;
import com.adam.server.persistence.TvSignalEntity;
import com.adam.server.persistence.TvSignalRepository;
import com.adam.server.scan.Mailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TvSignalServiceTest {

    TvSignalRepository repo;
    Mailer mailer;
    AppProperties props;
    TvSignalService service;

    @BeforeEach
    void setUp() {
        repo = mock(TvSignalRepository.class);
        mailer = mock(Mailer.class);
        props = new AppProperties();
        props.getTv().setWebhookSecret("s3cret");
        when(repo.save(any(TvSignalEntity.class))).thenAnswer(i -> {
            TvSignalEntity e = i.getArgument(0);
            e.setId(7L);
            return e;
        });
        service = new TvSignalService(props, repo, mailer, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC));
    }

    private static String body(String secret, String event, String side, String sym, String price) {
        return "{\"secret\":\"" + secret + "\",\"strat\":\"algopro\",\"event\":\"" + event + "\",\"side\":\""
                + side + "\",\"sym\":\"" + sym + "\",\"tf\":\"30\",\"price\":" + price
                + ",\"t\":\"2026-10-20T09:30:00Z\"}";
    }

    @Test
    void disabledWithoutSecret() {
        props.getTv().setWebhookSecret("");
        assertThat(service.ingest(body("x", "entry", "BUY", "US500", "7684.8")).status())
                .isEqualTo(TvSignalService.Status.DISABLED);
        verify(repo, never()).save(any());
    }

    @Test
    void rejectsBadSecretAndNeverStores() {
        assertThat(service.ingest(body("nope", "entry", "BUY", "US500", "7684.8")).status())
                .isEqualTo(TvSignalService.Status.UNAUTHORIZED);
        verify(repo, never()).save(any());
    }

    @Test
    void storesAnEntryNormalisesTheSymbolAndMails() {
        TvSignalService.Result r = service.ingest(body("s3cret", "entry", "buy", "CAPITALCOM:SPX500", "7684.8"));

        assertThat(r.status()).isEqualTo(TvSignalService.Status.ACCEPTED);
        ArgumentCaptor<TvSignalEntity> c = ArgumentCaptor.forClass(TvSignalEntity.class);
        verify(repo).save(c.capture());
        TvSignalEntity e = c.getValue();
        assertThat(e.getSymbol()).isEqualTo("US500");
        assertThat(e.getSide()).isEqualTo("BUY");
        assertThat(e.getEvent()).isEqualTo("entry");
        assertThat(e.getPrice()).isEqualTo(7684.8);
        verify(mailer).send(anyString(), anyString());
    }

    @Test
    void tpEventIsStoredButNotMailed() {
        service.ingest(body("s3cret", "tp", "SELL", "ETHUSD", "2650"));
        verify(repo).save(any());
        verify(mailer, never()).send(anyString(), anyString());
    }

    @Test
    void duplicateDeliveryIsIdempotent() {
        when(repo.existsByDedupeKey(anyString())).thenReturn(true);
        assertThat(service.ingest(body("s3cret", "entry", "BUY", "US500", "7684.8")).status())
                .isEqualTo(TvSignalService.Status.DUPLICATE);
        verify(repo, never()).save(any());
    }

    @Test
    void rejectsMalformedPayloads() {
        assertThat(service.ingest("not json").status()).isEqualTo(TvSignalService.Status.BAD_REQUEST);
        assertThat(service.ingest(body("s3cret", "bogus", "BUY", "US500", "1")).status())
                .isEqualTo(TvSignalService.Status.BAD_REQUEST);
        assertThat(service.ingest(body("s3cret", "entry", "LONG", "US500", "1")).status())
                .isEqualTo(TvSignalService.Status.BAD_REQUEST);
        assertThat(service.ingest(body("s3cret", "entry", "BUY", "US500", "0")).status())
                .isEqualTo(TvSignalService.Status.BAD_REQUEST);
        verify(repo, never()).save(any());
    }

    @Test
    void symbolNormalisationHandlesPrefixesAndPerps() {
        assertThat(TvSymbols.normalize("CAPITALCOM:GER40")).isEqualTo("GER40");
        assertThat(TvSymbols.normalize("OKX:ETHUSDT.P")).isEqualTo("ETH");
        assertThat(TvSymbols.normalize("xauusd")).isEqualTo("XAU");
        assertThat(TvSymbols.normalize("EURUSD")).isEqualTo("EURUSD");
        assertThat(TvSymbols.normalize(null)).isNull();
    }
}
