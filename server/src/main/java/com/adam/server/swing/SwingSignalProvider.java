package com.adam.server.swing;

import com.adam.server.broker.model.Candle;

import java.time.Instant;
import java.util.List;

/**
 * Contract for producing swing signals. Kept separate so the swing strategy can
 * be backtested or surfaced in the dashboard without any broker-account wiring.
 * Nothing implements this yet.
 */
public interface SwingSignalProvider {

    /**
     * @param symbol   instrument
     * @param epic     Capital.com epic
     * @param h1Closed closed H1 candles (entry trigger frame)
     * @param h4Closed closed H4 candles (context frame)
     * @param now      evaluation instant
     * @return swing scan for the symbol, or {@code null} when no setup
     */
    SwingScan evaluate(SwingSymbol symbol, String epic, List<Candle> h1Closed, List<Candle> h4Closed, Instant now);
}
