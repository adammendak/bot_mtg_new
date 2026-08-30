package com.adam.server.hts;

import com.adam.server.persistence.HtsTradeEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Baseline sink — logs every open and close. Always on. */
@Component
public class LogHtsTradeSink implements HtsTradeSink {

    private static final Logger log = LoggerFactory.getLogger(LogHtsTradeSink.class);

    @Override
    public void onOpen(HtsTradeEntity t) {
        log.info("HTS trade OPEN [{}] {} {} {} size {} @ {} stop {} target {} (deal {})",
                t.getVariant(), t.getBook(), t.getSymbol(), t.getDirection(), t.getSize(),
                t.getEntry(), t.getStopLevel(), t.getTargetLevel(), t.getDealId());
    }

    @Override
    public void onClose(HtsTradeEntity t) {
        log.info("HTS trade CLOSE [{}] {} {} {} exit {} R {} pnl {} {} ({})",
                t.getVariant(), t.getBook(), t.getSymbol(), t.getDirection(), t.getExitPrice(),
                t.getRMultiple(), t.getPnl(), t.getPnlCcy(), t.getCloseReason());
    }
}
