package com.adam.server.swing;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Candle;
import com.adam.server.scan.Mailer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SwingSignalContextTest {

    private static List<Candle> ramp(int n, long stepSecs, double start, double perBar) {
        List<Candle> out = new ArrayList<>();
        Instant t = Instant.parse("2026-06-01T00:00:00Z");
        for (int i = 0; i < n; i++) {
            double c = start + i * perBar;
            out.add(new Candle(t.plusSeconds(i * stepSecs), c - perBar / 2, c + 1, c - 1, c, 1));
        }
        return out;
    }

    @Test
    void buildsBothTimeframeReadingsAndTheMailDoesNotThrow() {
        List<Candle> h1 = ramp(200, 3600, 100, 0.10);
        List<Candle> h4 = ramp(60, 4 * 3600, 90, 0.40);
        double entry = h1.getLast().close();
        double atrH4Guess = 2.0;
        SwingScan scan = new SwingScan(
                h1.getLast().time(), "GER40", "DE40", Direction.BUY,
                entry, entry - 2.5 * atrH4Guess, entry + atrH4Guess, SwingScan.H4Trend.UP);

        SwingSignalContext ctx = SwingSignalContext.from(h1, h4, scan, ZoneId.of("Europe/Warsaw"));

        assertThat(ctx.h4Trend()).isNotBlank();
        assertThat(ctx.h4WtZone()).isIn("oversold extreme", "oversold", "neutral", "overbought", "overbought extreme", "n/a");
        assertThat(ctx.h1RmaStack()).isNotBlank();
        assertThat(ctx.h1SupertrendTrend()).isIn(-1, 0, 1);
        assertThat(ctx.stopDistancePrice()).isGreaterThan(0);
        assertThat(ctx.riskReward()).isCloseTo(0.4, org.assertj.core.api.Assertions.within(1e-9));

        MailSwingNotifier mail = new MailSwingNotifier(Mailer.disabled());
        assertThatCode(() -> mail.onSwingSignal(scan, ctx)).doesNotThrowAnyException();
        assertThatCode(() -> mail.onSwingSignal(scan, null)).doesNotThrowAnyException();
    }
}
