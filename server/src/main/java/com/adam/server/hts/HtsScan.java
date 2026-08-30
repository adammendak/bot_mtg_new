package com.adam.server.hts;

import com.adam.server.broker.Direction;

import java.time.Instant;

/**
 * One HTS ("wstęgi") entry signal: taken after the execution-timeframe candle
 * closes back beyond the fast RMA band, with the fast band clear of the slow
 * band on both the execution and the higher timeframe.
 *
 * @param timestamp  execution-TF bar close that triggered the entry
 * @param symbol     instrument code (GER40, XAU, …)
 * @param epic       Capital.com epic
 * @param direction  BUY / SELL
 * @param entry      entry level (execution-TF close)
 * @param stopLevel  structural stop — far edge of the fast band + buffer
 * @param targetLevel  fixed R:R target (TP1)
 * @param htfUp      HTF fast band above the HTF slow band at decision time
 */
public record HtsScan(
        Instant timestamp,
        String symbol,
        String epic,
        Direction direction,
        double entry,
        double stopLevel,
        double targetLevel,
        boolean htfUp
) {
}
